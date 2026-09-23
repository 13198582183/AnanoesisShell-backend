package com.ananoesis.shell.ssh;

import com.ananoesis.shell.contract.model.ErrorCode;

/**
 * SSH 连接/通道失败的统一异常。
 *
 * <p>WHY 必须有自己的异常类型，而不是把 sshj 的 {@code IOException} 往上抛：
 * spec 要求"认证失败或主机不可达时 MUST 返回明确的错误原因，且不得泄露服务器内部细节"。
 * 若让底层异常直接冒泡，它会带着远端 banner、主机密钥指纹、协议版本号甚至内网地址
 * 一路走到 REST/WS 的错误响应里。把所有失败收口成本类型，
 * "对外说什么"就由 {@link #userMessage()} 一处决定，"对内记什么"由 {@link #getMessage()} 决定。</p>
 *
 * <p>WHY 是 unchecked：调用链上有 WebSocket 回调与流式读取，
 * 受检异常会迫使每一层都写无意义的 try/catch 转发。</p>
 */
public class SshConnectException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final SshFailureKind kind;

    public SshConnectException(SshFailureKind kind, String detail) {
        this(kind, detail, null);
    }

    /**
     * @param kind   失败类别，决定对外错误码与文案
     * @param detail 仅供服务端日志与 {@code sessions.error_message} 使用的细节。
     *               MUST NOT 含明文凭据——本类的 {@link #getMessage()} 会进日志，
     *               而 credential-store spec 禁止明文凭据出现在日志中。
     * @param cause  底层异常；保留它才能在后端日志里看到完整栈
     */
    public SshConnectException(SshFailureKind kind, String detail, Throwable cause) {
        super(detail, cause);
        this.kind = kind;
    }

    public SshFailureKind kind() {
        return kind;
    }

    /** 契约错误码，用于 REST {@code Error.code} 与 WS {@code terminal_output.error_code}。 */
    public ErrorCode errorCode() {
        return kind.errorCode();
    }

    /** 面向用户的固定文案；MUST NOT 直接把它替换成 {@link #getMessage()}。 */
    public String userMessage() {
        return kind.userMessage();
    }

    /** 写入 {@code sessions.close_reason} 的取值。 */
    public SshCloseReason closeReason() {
        return kind.closeReason();
    }
}
