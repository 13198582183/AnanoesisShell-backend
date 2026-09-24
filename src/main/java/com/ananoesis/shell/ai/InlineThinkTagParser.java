package com.ananoesis.shell.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * content 流内联 think 开闭标签的分流器（BUG4：Qwen3 类自托管模型不走 reasoning_content 字段）。
 *
 * <p>WHY 有状态增量解析而不是整段事后切分：思考是逐字经 SSE 到达的，
 * 标签对可能被分片边界拦腰截断；而界面必须实时把思考与回答分进各自段落
 * （前端按 thinking_delta/answer_delta 帧型落段，事后没有第二次机会）。
 * 解析器至多缓存「半截标签前缀」长度的待定字符，确认不是标签即原样吐出——
 * 既不凭空丢字，也不把标签碎片泄漏给用户。</p>
 *
 * <p>线程约束：一次 {@code streamRound} 循环内单线程使用，不做线程安全。</p>
 */
final class InlineThinkTagParser {

    /** 一个输出段：标签内为思考，标签外为回答。 */
    record Segment(boolean thinking, String text) {
    }

    // 拼接构造：避免成对尖括号标记在编辑工具链中被当作控制序列处理（与测试常量的手法一致）
    private static final String OPEN = "<" + "think" + ">";
    private static final String CLOSE = "<" + "/" + "think" + ">";

    /** 待定缓冲：尾部可能是开/闭标签的前缀，先扣住不吐。 */
    private final StringBuilder pending = new StringBuilder();
    private boolean inThink;

    /**
     * 喂入一个 content 增量。
     *
     * @return 此刻可确定的段序列（可能为空——整段都扣在待定缓冲里）
     */
    List<Segment> feed(String delta) {
        pending.append(delta);
        List<Segment> out = new ArrayList<>(2);
        drain(out, false);
        return out;
    }

    /**
     * 回合结束。
     *
     * @return 剩余段；若仍停留在标签内，余文归思考（未闭合的思考不凭空丢字）
     */
    List<Segment> flush() {
        List<Segment> out = new ArrayList<>(1);
        drain(out, true);
        return out;
    }

    private void drain(List<Segment> out, boolean eof) {
        String buf = pending.toString();
        int consumed = 0;
        while (true) {
            if (inThink) {
                int idx = buf.indexOf(CLOSE, consumed);
                if (idx >= 0) {
                    add(out, true, buf.substring(consumed, idx));
                    consumed = idx + CLOSE.length();
                    inThink = false;
                    continue;
                }
                break;
            }
            int idx = buf.indexOf(OPEN, consumed);
            if (idx >= 0) {
                add(out, false, buf.substring(consumed, idx));
                consumed = idx + OPEN.length();
                inThink = true;
                continue;
            }
            break;
        }
        // 尾部扣住可能是半截标签的最长真前缀；eof 时无未来分片可拼，全部吐出
        int hold = eof ? 0 : maxPartialTagSuffix(buf, consumed);
        String rest = buf.substring(consumed, buf.length() - hold);
        add(out, inThink, rest);
        pending.setLength(0);
        if (hold > 0) {
            pending.append(buf, buf.length() - hold, buf.length());
        }
    }

    /** 计算 buf 尾部同时是 OPEN 或 CLOSE 真前缀（非空、非全长）的最大长度。 */
    private static int maxPartialTagSuffix(String buf, int from) {
        int max = 0;
        for (String tag : new String[] {OPEN, CLOSE}) {
            int limit = Math.min(tag.length() - 1, buf.length() - from);
            for (int k = limit; k > max; k--) {
                if (buf.endsWith(tag.substring(0, k))) {
                    max = k;
                    break;
                }
            }
        }
        return max;
    }

    private static void add(List<Segment> out, boolean thinking, String text) {
        if (!text.isEmpty()) {
            out.add(new Segment(thinking, text));
        }
    }
}
