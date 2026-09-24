package com.ananoesis.shell.ai;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link InlineThinkTagParser} 的纯单元测试：只测分流算法本体，
 * 端到端帧序列断言在 {@code AiAgentServiceTest} 的内联标签用例组。
 */
class InlineThinkTagParserTest {

    // 与生产代码同样的拼接构造（避开成对尖括号标记的工具链处理）
    private static final String OPEN = "<" + "think" + ">";
    private static final String CLOSE = "<" + "/" + "think" + ">";

    private static String joined(List<InlineThinkTagParser.Segment> segments, boolean thinking) {
        StringBuilder sb = new StringBuilder();
        for (InlineThinkTagParser.Segment segment : segments) {
            if (segment.thinking() == thinking) {
                sb.append(segment.text());
            }
        }
        return sb.toString();
    }

    @Test
    @DisplayName("回答里的孤立尖括号/半截假前缀：待定后按原样吐出，一个字不丢")
    void falseTagPrefixIsReleasedVerbatim() {
        InlineThinkTagParser parser = new InlineThinkTagParser();
        StringBuilder answer = new StringBuilder();
        // 逐字符喂入最坏情况：正文含 "<"、"<th"、"x</thi" 等假前缀
        String input = "a<b <th c x</thi y" + CLOSE + "z";
        // 前半没有开标签，全部应落在回答侧；CLOSE 单独出现不改变归属
        for (int i = 0; i < ("a<b <th c x</thi y").length(); i++) {
            answer.append(joined(parser.feed(String.valueOf(("a<b <th c x</thi y").charAt(i))), false));
        }
        assertThat(answer.toString()).isEqualTo("a<b <th c x</thi y");
        // 收尾：多余的闭标签在无开标签语境下按原文吐出（不吞内容）
        answer.append(joined(parser.feed(CLOSE), false));
        answer.append(joined(parser.feed("z"), false));
        answer.append(joined(parser.flush(), false));
        assertThat(answer.toString()).isEqualTo("a<b <th c x</thi y" + CLOSE + "z");
        assertThat(input).isNotEmpty();
    }

    @Test
    @DisplayName("一对标签内的思考 + 标签外的回答，按到达顺序分段吐出")
    void singlePairSplitsInOrder() {
        InlineThinkTagParser parser = new InlineThinkTagParser();
        List<InlineThinkTagParser.Segment> segments =
                parser.feed("答复前" + OPEN + "思考中" + CLOSE + "答复后");
        segments.addAll(parser.flush());

        assertThat(segments).extracting(InlineThinkTagParser.Segment::thinking)
                .containsExactly(false, true, false);
        assertThat(joined(segments, false)).isEqualTo("答复前答复后");
        assertThat(joined(segments, true)).isEqualTo("思考中");
    }

    @Test
    @DisplayName("多对标签交替：思考/回答归属不串")
    void multiplePairsAlternateCorrectly() {
        InlineThinkTagParser parser = new InlineThinkTagParser();
        List<InlineThinkTagParser.Segment> segments = parser.feed(
                "A" + OPEN + "S1" + CLOSE + "B" + OPEN + "S2" + CLOSE + "C");
        segments.addAll(parser.flush());

        assertThat(joined(segments, false)).isEqualTo("ABC");
        assertThat(joined(segments, true)).isEqualTo("S1S2");
    }

    @Test
    @DisplayName("flush 时仍在标签内：待定尾与余文都归思考")
    void flushInsideTagYieldsThinking() {
        InlineThinkTagParser parser = new InlineThinkTagParser();
        assertThat(joined(parser.feed("回答"), false)).isEqualTo("回答");
        List<InlineThinkTagParser.Segment> rest = parser.feed(OPEN + "未尽的思考");
        rest.addAll(parser.flush());

        assertThat(joined(rest, true)).isEqualTo("未尽的思考");
        assertThat(joined(rest, false)).isEmpty();
    }
}
