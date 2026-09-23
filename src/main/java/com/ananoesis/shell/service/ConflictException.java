package com.ananoesis.shell.service;

/**
 * 请求与资源的当前状态冲突（契约 {@code ErrorCode.CONFLICT} → HTTP 409）。
 *
 * <p>WHY 需要独立的类型而不是复用 {@link InvalidRequestException}：
 * 400 说的是"你发的东西本身不合法，改了重发就行"；409 说的是"你发的东西没问题，
 * 但资源现在的状态不允许这个操作，得先改变状态"。两者的前端处置完全不同——
 * 前者要把错误挂到表单项上，后者要提示用户先去做另一件事。混成一个码，
 * 前端就只能对两种情形给出同一句无用的提示。</p>
 *
 * <p>Wave 3 的首个使用者是 TRACEABILITY Q4 的裁定：删除"当前生效"的模型配置返回 409，
 * 要求用户先切换到别的配置。若不拦，删完之后系统里就没有任何生效配置，
 * AI 能力会在没有任何界面提示的情况下静默失效。</p>
 *
 * <p>安全约束：消息由服务端自行构造，MUST NOT 拼接用户提交的凭据类内容。</p>
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
