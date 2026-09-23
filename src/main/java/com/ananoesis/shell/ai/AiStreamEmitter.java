package com.ananoesis.shell.ai;

import com.ananoesis.shell.ws.AiStreamFrame;

/**
 * {@code ai_stream} 下行帧的出口（tasks 7.4 / 9.5）。
 *
 * <h2>WHY 抽这个单方法接口，而不是让智能体直接依赖 {@code AiWebSocketHandler}</h2>
 * <ol>
 *   <li><b>破循环依赖</b>：handler 收到上行 {@code user_message} 后要调
 *       {@link AiAgentService}，而智能体产出的增量又要经 handler 发出去。
 *       两个具体类互相构造器注入，Spring 启动即失败。把"下行"收进这个接口，
 *       handler 是它的<b>实现</b>、智能体是它的<b>消费方</b>，方向单一。</li>
 *   <li><b>可测性</b>：智能体的回合循环是 Wave 3 最复杂的一段（流式增量、思考模式分流、
 *       工具分级路由、审批挂起）。用一个收集帧的假 emitter 就能对帧序列做精确断言，
 *       不必启动 WebSocket 容器、不必模拟握手。</li>
 * </ol>
 *
 * <h2>契约义务</h2>
 * <p>实现 MUST 只发 {@link AiStreamFrame} 的下行工厂产物
 * （{@code thinking_delta}/{@code answer_delta}/{@code tool_call}/{@code tool_result}/
 * {@code final}/{@code error}）。{@code user_message} 是<b>上行</b>类型，
 * 服务器主动发它没有语义（asyncapi 的 {@code sendAiStream} 不含该 type 的用途说明）。</p>
 *
 * <h2>安全</h2>
 * <p>帧里的 {@code content}/{@code message}/{@code result} 会经 WebSocket 明文送达浏览器，
 * 且其中的工具结果可能被前端渲染成 HTML。实现 MUST NOT 往里塞 api key 或任何本地凭据；
 * 转义责任在前端，但后端不给出需要转义的危险内容是第一道防线。</p>
 */
public interface AiStreamEmitter {

    /**
     * 发送一帧 {@code ai_stream}。
     *
     * <p>WHY 不返回 {@code boolean}、也不声明 checked 异常：发送失败（前端已断开、
     * 缓冲积压）不应中断智能体的回合——回合的产物还要落库供用户稍后查看。
     * 失败由实现自己记日志。</p>
     *
     * @param frame 出站帧，不得为 null
     */
    void emit(AiStreamFrame frame);
}
