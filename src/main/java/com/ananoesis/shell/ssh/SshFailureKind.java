package com.ananoesis.shell.ssh;

import com.ananoesis.shell.contract.model.ErrorCode;

/**
 * SSH 失败的内部细分。
 *
 * <p>WHY 内部枚举与契约 {@link ErrorCode} 分开：契约只有 {@code auth_failed} /
 * {@code host_unreachable} / {@code internal_error} 三个相关取值，但排障时需要区分
 * "端口拒绝连接"与"TCP 通了但握手超时"——前者多半是端口写错或服务未启动，
 * 后者多半是防火墙静默丢包。把细分留在内部、对外统一映射，
 * 既满足 spec「不泄露服务器内部细节」，又不牺牲日志的诊断价值。</p>
 *
 * <p>WHY 文案写死在这里而不是散落在各处：spec 对两种失败给了**逐字**要求
 * （"认证失败" / "连接失败：主机不可达"）。文案集中一处，改一次即可，
 * 也便于测试直接断言常量而非复制字符串。</p>
 */
public enum SshFailureKind {

    /** 凭据无效（密码错、私钥不被接受、passphrase 错、用户名不存在）。 */
    AUTH_FAILED(ErrorCode.AUTH_FAILED, "认证失败", SshCloseReason.AUTH_FAILED),

    /** 主机配置里没有可用凭据。属于可自愈的用户操作问题，不是内部错误。 */
    CREDENTIAL_MISSING(ErrorCode.VALIDATION_ERROR,
            "服务器凭据未配置，请先在服务器配置中填写密码或私钥", SshCloseReason.ERROR),

    /** TCP 层就没能建立连接（端口未监听、路由不可达、域名解析失败）。 */
    HOST_UNREACHABLE(ErrorCode.HOST_UNREACHABLE, "连接失败：主机不可达", SshCloseReason.UNREACHABLE),

    /**
     * TCP 已连通但对端在时限内没有完成 SSH 协议握手。
     * WHY 对外仍归为 host_unreachable：spec「主机不可达」Scenario 写的就是
     * "系统在**超时后**提示'连接失败：主机不可达'"，用户视角二者无从区分也无需区分。
     */
    CONNECT_TIMEOUT(ErrorCode.HOST_UNREACHABLE, "连接失败：主机不可达", SshCloseReason.UNREACHABLE),

    /** 会话已建立但通道操作失败（exec/shell 打开失败、通道被对端拒绝）。 */
    CHANNEL_FAILURE(ErrorCode.INTERNAL_ERROR, "命令通道打开失败，请查看后端日志获取详情", SshCloseReason.ERROR),

    /** 其它未预期错误。对外只给通用文案，细节仅进服务端日志。 */
    INTERNAL(ErrorCode.INTERNAL_ERROR, "服务器内部错误，请查看后端日志获取详情", SshCloseReason.ERROR);

    private final ErrorCode errorCode;
    private final String userMessage;
    private final SshCloseReason closeReason;

    SshFailureKind(ErrorCode errorCode, String userMessage, SshCloseReason closeReason) {
        this.errorCode = errorCode;
        this.userMessage = userMessage;
        this.closeReason = closeReason;
    }

    /** 契约错误码：可直接用于 REST {@code Error.code} 与 WS {@code terminal_output.error_code}。 */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /** 面向用户的固定文案，MUST NOT 夹带主机名、端口、异常类型等内部细节。 */
    public String userMessage() {
        return userMessage;
    }

    /** 写入 {@code sessions.close_reason} 时使用的取值。 */
    public SshCloseReason closeReason() {
        return closeReason;
    }
}
