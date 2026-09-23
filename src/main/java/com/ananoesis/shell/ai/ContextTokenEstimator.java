package com.ananoesis.shell.ai;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;

/**
 * 上下文 token 估算器（design D7 步骤 5，tasks 8.3）。
 *
 * <p>WHY 不用字符数除以 4 或声称精确 tokenizer：OpenAI 的 tiktoken 对中文、代码、JSON
 * 的编码比各不相同，"字符/4"只在英文自然文本上近似成立。本估算器直接计算最终请求
 * 序列化后的 UTF-8 字节数，加上每条消息 32 字节和整请求 256 字节的结构余量，
 * 作为保守估算。中文/代码/JSON 按同一实际编码计数，不猜品牌对应 tokenizer。</p>
 *
 * <p>估算策略可替换（design D7 步骤 5 末句），实际超限恢复仍必须存在。</p>
 */
public class ContextTokenEstimator {

    /** 每条消息的结构余量（role 字段、分隔符等）。 */
    static final int PER_MESSAGE_OVERHEAD = 32;

    /** 整个请求的结构余量（model、协议字段、tools 外层结构等）。 */
    static final int REQUEST_OVERHEAD = 256;

    /**
     * 估算一次请求的 token 消耗（保守估算，单位 = UTF-8 字节）。
     *
     * @param messages 即将发给模型的消息序列（含系统、用户、assistant、tool 响应）
     * @param tools    当前可用工具回调（其定义也会被序列化进请求）
     * @return 估算值（UTF-8 字节数 + 结构余量）
     */
    public int estimate(List<Message> messages, List<ToolCallback> tools) {
        int total = REQUEST_OVERHEAD;

        // WHY 按每条消息的内容 UTF-8 字节 + 32 余量计算：
        // 这是对"序列化后请求体积"的保守近似，不依赖特定 tokenizer
        for (Message message : messages) {
            total += PER_MESSAGE_OVERHEAD;
            total += utf8Length(extractContent(message));
        }

        // WHY 工具定义也占请求体积：OpenAI 协议把 tools 数组序列化进请求体，
        // 每个工具的 name/description/inputSchema 都消耗 token
        for (ToolCallback tool : tools) {
            total += utf8Length(tool.getToolDefinition().name());
            total += utf8Length(tool.getToolDefinition().description());
            total += utf8Length(tool.getToolDefinition().inputSchema());
        }

        return total;
    }

    /**
     * 提取消息中可计数的文本内容。
     *
     * <p>WHY 不同消息类型提取方式不同：
     * {@code UserMessage}/{@code SystemMessage} 直接取 text；
     * {@code AssistantMessage} 取 content（不含 tool_calls JSON，那由工具定义覆盖）；
     * {@code ToolResponseMessage} 取所有 response 的 responseData。</p>
     */
    private static String extractContent(Message message) {
        if (message instanceof UserMessage || message instanceof SystemMessage) {
            String text = message.getText();
            return text == null ? "" : text;
        }
        if (message instanceof AssistantMessage assistant) {
            String content = assistant.getText();
            return content == null ? "" : content;
        }
        if (message instanceof ToolResponseMessage toolResponse) {
            StringBuilder sb = new StringBuilder();
            for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
                String data = response.responseData();
                if (data != null) {
                    sb.append(data);
                }
            }
            return sb.toString();
        }
        return "";
    }

    /** 计算字符串的 UTF-8 编码字节数。 */
    private static int utf8Length(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
