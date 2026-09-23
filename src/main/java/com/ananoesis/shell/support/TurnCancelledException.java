package com.ananoesis.shell.support;

/**
 * 回合被用户停止的透传信号（BUG-B）。
 *
 * <p>WHY 需要专门类型而不是复用 {@code RuntimeException}：PTY 等待线程被
 * {@code interrupt()} 打断时抛出的 {@link InterruptedException} 一旦进入通用
 * {@code catch (RuntimeException)} 分支（工具回退、审批回落），就会被当成
 * 「基础设施失败」吞掉并<b>回落 exec 通道把同一条命令重跑一遍</b>——用户表现为
 * 「怎么停止 Agent 都还在执行命令」。需要一个类型让沿途每一层都能识别出
 * 「这不是失败，是用户主动停止」，从而：
 * <ul>
 *   <li>禁止回落到 exec 重跑（ApprovedCommandRunner / AgentTools 的吞点）；</li>
 *   <li>禁止伪装成 {@code tool_result(ERROR)} 回喂给模型驱动它继续推进；</li>
 *   <li>一路透传到回合顶层，按「受控停止」语义收尾（stopped 注记帧）。</li>
 * </ul></p>
 *
 * <p>约定：cause MUST 是（或直接包装）触发本信号的 {@link InterruptedException}，
 * 使沿 cause 链识别中断的上层逻辑（{@code causedByInterrupt}）无需特判；
 * 少数无 interrupt 源的主动停止（如权威停止标志命中）允许 cause 为 null，
 * 由识别方对本类型整体放行。</p>
 */
public class TurnCancelledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TurnCancelledException(String message, Throwable cause) {
        super(message, cause);
    }
}
