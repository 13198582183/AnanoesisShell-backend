package com.ananoesis.shell.ai;

import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.lang.Nullable;

/**
 * 上下文超限分类器（design D7 "单次恢复"段，tasks 9.1）。
 *
 * <p>职责：分类模型返回的错误，判断是否为上下文超限，从而决定是否触发恢复。</p>
 *
 * <h2>分类纪律</h2>
 * <p>只识别结构化错误码 {@code context_length_exceeded} / {@code context_window_exceeded}
 * 或明确的 token 上限语义。以下情况<b>不</b>触发恢复：</p>
 * <ul>
 *   <li>普通 400/413 — 可能是参数错误、请求体过大等，与上下文超限无关</li>
 *   <li>401（认证失败）— 需要用户修正 API key，不是上下文问题</li>
 *   <li>网络异常（ResourceAccessException 等）— 需要检查网络，不是上下文问题</li>
 *   <li>未知参数错误 — 模型定义与调用不匹配，需要修正参数</li>
 *   <li>{@code finish_reason=length} — 输出被截断，不是输入超限</li>
 * </ul>
 *
 * <p>WHY 严格分类：恢复额度每个 run 只有一次。误触发会浪费额度，
 * 导致真正的上下文超限时无法自救。</p>
 */
public class ContextLimitClassifier {

    /** 分类结果。 */
    public enum Verdict {
        /** 明确的上下文超限，可以触发恢复。 */
        CONTEXT_LIMIT,
        /** 其它错误，不触发恢复。 */
        OTHER_ERROR
    }

    /**
     * 分类异常是否为上下文超限。
     *
     * <p>WHY 遍历 cause 链：网络异常常被 Spring 包上两三层，
     * 上下文超限的错误码也可能被嵌套在内部异常中。只看最外层会漏判。</p>
     */
    public Verdict classify(@Nullable Throwable error) {
        if (error == null) {
            return Verdict.OTHER_ERROR;
        }
        // WHY 限制深度 16 层并防御自引用 cause：与 AiAgentService.classify 同理
        Throwable cursor = error;
        for (int depth = 0; cursor != null && depth < 16; depth++) {
            if (isContextLimitMessage(cursor.getMessage())) {
                return Verdict.CONTEXT_LIMIT;
            }
            cursor = cursor.getCause() == cursor ? null : cursor.getCause();
        }
        return Verdict.OTHER_ERROR;
    }

    /**
     * 分类 ChatResponse 是否为上下文超限。
     *
     * <p>检查 Generation 的 output 元数据中的错误码和错误信息。
     * 注意：{@code finish_reason=length} 不触发恢复（输出截断 ≠ 输入超限）。</p>
     */
    public Verdict classify(@Nullable ChatResponse response) {
        if (response == null) {
            return Verdict.OTHER_ERROR;
        }
        List<Generation> generations = response.getResults();
        if (generations == null || generations.isEmpty()) {
            return Verdict.OTHER_ERROR;
        }
        Generation generation = generations.get(0);
        AssistantMessage output = generation.getOutput();
        if (output != null) {
            Map<String, Object> metadata = output.getMetadata();
            if (metadata != null) {
                // WHY 检查 error_code 和 error_message 两个字段：
                // 某些端点在 AssistantMessage 的 properties 中放入错误信息
                Object errorCode = metadata.get("error_code");
                if (errorCode instanceof String code && isContextLimitErrorCode(code)) {
                    return Verdict.CONTEXT_LIMIT;
                }
                Object errorMessage = metadata.get("error_message");
                if (errorMessage instanceof String message && isContextLimitMessage(message)) {
                    return Verdict.CONTEXT_LIMIT;
                }
            }
        }
        // WHY 不检查 finish_reason=length：
        // length 表示输出达到最大长度限制，不是输入上下文超限。
        // 混淆两者会浪费唯一的恢复额度
        return Verdict.OTHER_ERROR;
    }

    /**
     * 检查消息文本是否包含明确的上下文超限错误码或语义。
     */
    private static boolean isContextLimitMessage(@Nullable String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        // WHY 先查结构化错误码，再查语义描述：
        // 结构化错误码是确定性判断；语义描述是兜底（某些端点不返回标准错误码）
        if (isContextLimitErrorCode(message)) {
            return true;
        }
        // 明确的 token 上限语义：端点返回的自然语言描述
        // WHY 要求同时出现 "token" 和 "context"/"maximum"/"limit"：
        // 单独出现 "token" 太宽泛（如 "token usage: 50%"），会误判
        String lower = message.toLowerCase();
        boolean hasToken = lower.contains("token");
        boolean hasContextOrMax = lower.contains("context") || lower.contains("maximum")
                || lower.contains("max_tokens") || lower.contains("limit");
        return hasToken && hasContextOrMax;
    }

    /**
     * 检查字符串是否包含已知的上下文超限错误码。
     */
    private static boolean isContextLimitErrorCode(String text) {
        return text.contains("context_length_exceeded") || text.contains("context_window_exceeded");
    }
}
