package com.ananoesis.shell.approval;

import com.ananoesis.shell.contract.model.ApprovalDecision;

/**
 * 一次审批的<b>最终结局</b>（command-approval spec「用户批准执行」/「用户取消执行」/「审批超时自动取消」）。
 *
 * <p>WHY 需要这个内部枚举，而不是直接用契约的 {@link ApprovalDecision}
 * 或 ws 的 {@code ApprovalResponseFrame.Decision}：这两个都<b>表达不全</b>。
 * ws 侧只有 {@code approve}/{@code cancel}——那是用户的两个动作，超时不是动作；
 * 契约侧有 {@code approved}/{@code cancelled}/{@code timed_out} 三个，但它没有
 * "写进 {@code approvals.decision} 列该用什么字面值"这层知识（DDL 里是
 * {@code pending/approved/rejected/timeout}，注意 {@code cancelled} 在库里叫 {@code rejected}、
 * {@code timed_out} 在库里叫 {@code timeout}）。</p>
 *
 * <p>把三套取值域的换算集中到一处，是为了让"契约 ↔ 数据库 ↔ WebSocket"的映射
 * 只有<b>一个</b>真相来源。分散在 gate / audit / controller 里各写一遍 switch，
 * 早晚会出现"审计写的是 rejected、接口回的是 approved"这种自相矛盾的记录。</p>
 */
public enum ApprovalOutcome {

    /** 用户点了批准：命令应当被执行。 */
    APPROVED,

    /** 用户点了取消：命令 MUST NOT 执行，回喂给模型的结果是「用户已拒绝」。 */
    CANCELLED,

    /** 时限内没有拿到任何决定，按"取消"兜底（spec「审批超时自动取消」）。 */
    TIMED_OUT;

    /**
     * @return 是否应当执行命令
     *
     * <p>WHY 提供这个方法而不是让调用方写 {@code == APPROVED}：
     * 调用点分散在 gate、runner 与 agent 循环里，每处都写一遍枚举比较，
     * 将来若新增第四种"也算放行"的结局（例如"批准但需二次确认"），
     * 漏改一处就等于放过一条未经确认的命令。收进方法里，改动只有一处。</p>
     */
    public boolean approved() {
        return this == APPROVED;
    }

    /**
     * @return {@code approvals.decision} 列的字面值（受 DDL CHECK 约束）
     */
    public String dbDecision() {
        return switch (this) {
            case APPROVED -> "approved";
            case CANCELLED -> "rejected";
            case TIMED_OUT -> "timeout";
        };
    }

    /**
     * @return {@code approvals.decided_by} 列的字面值
     *
     * <p>WHY 超时记 {@code system}：spec 要求审计能区分"人不想要"与"人没来得及看"。
     * 两者在契约侧都是 {@code cancelled}/{@code timed_out}，但责任归属完全不同——
     * 前者是明确否决，后者是流程缺陷（时限设得太短、或用户离开了）。</p>
     */
    public String dbDecidedBy() {
        return this == TIMED_OUT ? "system" : "user";
    }

    /**
     * @return 契约 {@code Approval.decision} 的取值（用于 {@code GET /api/approvals}）
     */
    public ApprovalDecision toContract() {
        return switch (this) {
            case APPROVED -> ApprovalDecision.APPROVED;
            case CANCELLED -> ApprovalDecision.CANCELLED;
            case TIMED_OUT -> ApprovalDecision.TIMED_OUT;
        };
    }

    /**
     * 按 {@code approvals.decision} 列的字面值反查。
     *
     * @return 未识别（含 {@code pending}）时为 {@code null}——{@code pending} 不是一个结局，
     *         而是"还没有结局"，调用方应把它排除在审计查询之外（契约的
     *         {@code ApprovalDecision} 没有 pending 取值，硬塞进去只能选一个说谎的值）
     */
    public static ApprovalOutcome fromDbDecision(String dbDecision) {
        if (dbDecision == null) {
            return null;
        }
        for (ApprovalOutcome candidate : values()) {
            if (candidate.dbDecision().equals(dbDecision)) {
                return candidate;
            }
        }
        return null;
    }
}
