package com.ananoesis.shell.ai;

import java.net.URI;
import java.util.Objects;

import com.ananoesis.shell.contract.model.OutputLimitField;
import com.ananoesis.shell.contract.model.ThinkingMode;
import com.ananoesis.shell.contract.model.ThinkingRequestFormat;
import com.ananoesis.shell.security.MissingModelApiKeyException;
import com.ananoesis.shell.security.SecretText;

/**
 * "当前该连哪个模型端点"的取值来源（tasks 7.2 的可测试缝隙）。
 *
 * <h2>WHY 要单独抽这个接口，而不是让装配器直接依赖 {@code ModelConfigService}</h2>
 * <p>{@code ModelConfigService} 背后是 MyBatis-Plus 的 Mapper 与 SQLite；要给它一个假实现，
 * 就得连 Mapper 一起造假。装配逻辑（{@link OpenAiCompatibleChatModelProvider}）恰恰是
 * Wave 3 里最容易出错、又最需要快速反复验证的一段——base_url 拼接、思考模式开关、
 * 工具执行策略，错一个都表现为"发出去就没反应"。把取值收进这个两方法的窄接口，
 * 装配逻辑就能用 lambda 喂假配置做纯单元测试，不启上下文、不碰数据库、不发网络请求。</p>
 *
 * <h2>安全边界</h2>
 * <p>{@link ResolvedEndpoint#apiKey()} 是 {@link SecretText}：打印即掩码。
 * 消费方 MUST 以 try-with-resources 持有它，装配一结束就擦除。
 * 本接口的任何实现都 MUST NOT 把 api key 写进日志、异常消息或返回值以外的地方。</p>
 */
public interface ModelEndpointResolver {

    /**
     * 解析当前生效的模型端点。
     *
     * @throws MissingModelApiKeyException 尚无生效配置，或生效配置未设置 api key。
     *                                     两种情形合并成同一个异常，因为用户的处置完全相同
     *                                     （去设置页把模型配好），而冻结契约的
     *                                     {@code ErrorCode} 里没有"未配置模型"这一项。
     */
    ResolvedEndpoint resolve();

    /**
     * 装配一个 {@code ChatModel} 所需的全部信息（不含密钥之外的任何秘密）。
     *
     * @param configId              生效配置的主键，仅用于日志定位
     * @param provider              provider 名称（mindie/openai/ollama…），决定请求体的方言差异
     * @param baseUrl               OpenAI 兼容端点 base URL，形如 {@code http://host:port/v1}
     * @param model                 模型名
     * @param thinkingMode          已按 TRACEABILITY Q3 解析完毕的思考模式（per-config 优先，否则全局默认）
     * @param contextWindowTokens   上下文窗口 token 容量（design D7）
     * @param maxOutputTokens       最大输出预留 token（design D7）
     * @param outputLimitField      Chat Completions 输出上限字段名（design D7）
     * @param thinkingRequestFormat 思考模式请求扩展格式（design D8）
     * @param apiKey                解密后的 api key；消费方负责擦除
     */
    record ResolvedEndpoint(String configId, String provider, URI baseUrl, String model,
                            ThinkingMode thinkingMode,
                            int contextWindowTokens, int maxOutputTokens,
                            OutputLimitField outputLimitField,
                            ThinkingRequestFormat thinkingRequestFormat,
                            SecretText apiKey) {

        public ResolvedEndpoint {
            Objects.requireNonNull(configId, "configId 不得为 null");
            Objects.requireNonNull(provider, "provider 不得为 null");
            Objects.requireNonNull(baseUrl, "baseUrl 不得为 null");
            Objects.requireNonNull(model, "model 不得为 null");
            Objects.requireNonNull(thinkingMode, "thinkingMode 不得为 null");
            Objects.requireNonNull(outputLimitField, "outputLimitField 不得为 null");
            Objects.requireNonNull(thinkingRequestFormat, "thinkingRequestFormat 不得为 null");
            Objects.requireNonNull(apiKey, "apiKey 不得为 null；'未配置'应以异常表达，而非 null");
        }
    }
}
