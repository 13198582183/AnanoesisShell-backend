package com.ananoesis.shell.approval;

/**
 * 审批请求的<b>下行出口</b>（tasks 8.1）。
 *
 * <p>WHY 抽这么一个只有一个方法的接口，而不是让 {@link ApprovalGate} 直接依赖
 * {@code ApprovalWebSocketHandler}：两者会构成<b>循环依赖</b>——
 * handler 收到 {@code approval_response} 后要调 {@code gate.respond(...)}，
 * gate 受理提案后又要调 handler 广播。Spring 对构造器注入的循环依赖直接启动失败，
 * 靠 {@code @Lazy} 绕过则把这个结构问题藏进了注解里。</p>
 *
 * <p>拆出接口后依赖变成单向：{@code handler → gate}（实现关系）与
 * {@code gate → ApprovalNotifier}（注入关系），由 handler 同时扮演
 * "notifier 的实现"与"gate 的调用方"，循环在类型层面消失。</p>
 *
 * <p>WHY 不返回 boolean：广播失败（前端没连、连接刚断）<b>不应该</b>让审批流程中止。
 * 提案已经落库、时限已经开始计时，用户完全可能通过审计页面看到它；
 * 若因为一次发送失败就把提案作废，模型侧会收到一个无法解释的错误。
 * 实现方自行记录失败即可。</p>
 */
public interface ApprovalNotifier {

    /**
     * 向所有已连接的审批通道广播一个待决提案。
     *
     * <p>实现 MUST NOT 抛异常打断调用方：闸门此时正在计时，
     * 抛出会让等待方收到一个既非批准也非取消的异常，挂起的 future 泄漏。</p>
     *
     * @param ticket 已受理的提案（含闸门分配的 {@code approvalId} 与实际时限）
     */
    void notifyRequest(ApprovalGate.Ticket ticket);
}
