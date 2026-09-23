package com.ananoesis.shell.ssh;

import com.ananoesis.shell.contract.model.EndReason;

/**
 * 会话终止原因。取值与 {@code sessions.close_reason} 的 CHECK 约束逐字对应。
 *
 * <p>WHY 内部枚举比契约 {@link EndReason} 多三项
 * （{@code AUTH_FAILED}/{@code UNREACHABLE}/{@code ERROR}）：
 * DDL 允许记录"连接根本没建成"的失败原因，这对审计与排障是必要信息；
 * 但契约的 {@code EndReason} 只描述"已建立的会话为何结束"，
 * 因此这三项在对外映射时归为 {@code null}（契约把 {@code end_reason} 声明为 nullable）。</p>
 */
public enum SshCloseReason {

    /** 用户主动断开（spec「主动断开」）。 */
    USER_DISCONNECT("user_disconnect", EndReason.USER_DISCONNECT),

    /** 空闲超时被回收（spec「连接超时」）。 */
    TIMEOUT("timeout", EndReason.TIMEOUT),

    /** 远端关闭连接（spec「远端关闭连接」），含优雅 EOF 与连接被粗暴切断。 */
    REMOTE_CLOSED("remote_closed", EndReason.REMOTE_CLOSE),

    /** 认证失败：会话从未真正建立。 */
    AUTH_FAILED("auth_failed", null),

    /** 主机不可达 / 连接超时：会话从未真正建立。 */
    UNREACHABLE("unreachable", null),

    /** 其它错误。 */
    ERROR("error", null);

    private final String columnValue;
    private final EndReason contractEndReason;

    SshCloseReason(String columnValue, EndReason contractEndReason) {
        this.columnValue = columnValue;
        this.contractEndReason = contractEndReason;
    }

    /** 写入 {@code sessions.close_reason} 的字面值。 */
    public String columnValue() {
        return columnValue;
    }

    /**
     * 映射为契约 {@code EndReason}；连接期失败返回 {@code null}。
     * WHY 允许 null 而不是硬凑一个值：把"认证失败"报成"用户主动断开"
     * 会在审计里留下与事实相反的记录，比留空更糟。
     */
    public EndReason toContractEndReason() {
        return contractEndReason;
    }

    /**
     * 由 DDL 字面值反查。
     *
     * @return 匹配项；未知或 null 时返回 {@link #ERROR}（读取历史数据不应因新增取值而崩溃）
     */
    public static SshCloseReason fromColumnValue(String value) {
        if (value != null) {
            for (SshCloseReason reason : values()) {
                if (reason.columnValue.equals(value)) {
                    return reason;
                }
            }
        }
        return ERROR;
    }
}
