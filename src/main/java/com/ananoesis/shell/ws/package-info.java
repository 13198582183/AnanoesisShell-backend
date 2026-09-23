/**
 * WebSocket 通道层（tasks 2.6 分层目录 / 6.3）。
 *
 * <p>职责：承载 design.md D4/D7 规定的三类实时流——交互式终端输出、
 * AI 流式增量、以及审批请求/结果推送。Wave 2a 落地的是其中的 {@code /ws/terminal}
 * （{@link TerminalWebSocketHandler}；端点路径的唯一真源是
 * {@code com.ananoesis.shell.config.WebSocketConfiguration#TERMINAL_ENDPOINT}）。
 * {@code /approval} 与 {@code /ai} 留给后续 Wave。</p>
 *
 * <p>本层只做<b>协议翻译</b>：asyncapi.yaml 的 {@code terminal_input} ⇄ 服务层调用，
 * 服务层回调 ⇄ {@code terminal_output} 帧。凭据、SSH 库、数据库都不在这里出现——
 * 一旦业务逻辑渗进来，"契约帧的形状"与"SSH 的行为"就会互相污染，两边都变得难以测试。</p>
 *
 * <h2>纪律（每条都有对应的测试钉住）</h2>
 * <ul>
 *   <li><b>明文凭据绝不出现。</b>终端流里可能回显用户在远端敲入的密码，
 *       因此任何持久化或转发都必须先经服务层脱敏。</li>
 *   <li><b>对外文案固定。</b>失败帧只带契约 {@code ErrorCode} 与
 *       {@code SshFailureKind#userMessage()}；主机名、端口、异常类型只进服务端日志。
 *       由 {@code TerminalWebSocketIntegrationTest} 对原始帧字节断言。</li>
 *   <li><b>Origin 同源限定。</b>{@code /ws/terminal} 能驱动用户配置好的 SSH 会话在远端
 *       执行任意命令，而 WebSocket 握手不走 REST 那套凭据/CSRF 防护——Origin 校验是
 *       唯一的闸门。放开成 {@code *} 等于让浏览器里任何页面都能连上本机端点敲命令（CSWSH）。</li>
 *   <li><b>{@code closed} 帧只有一个出口</b>，即 {@code TerminalWebSocketHandler} 内部
 *       {@code Bridge#onClosed}。任何路径自行补发都会让前端收到两条结束事件。</li>
 *   <li><b>入站帧一律宽容、出站帧一律严格。</b>接收方无法控制对端发什么，缺字段要翻译成
 *       {@code validation_error} 而不是让 Jackson 直接炸掉连接；发送方则必须满足契约的
 *       required。这条不对称由 {@code WsContractAlignmentTest} 分方向断言。</li>
 * </ul>
 *
 * <p>WHY 消息类型手写而非 codegen：codegen 只覆盖 {@code contract/openapi.yaml}（REST），
 * asyncapi 的 Spring 生成器生态不成熟，硬接会引入第二套平行的代码生成机制。
 * 与 asyncapi.yaml 的一致性改由 {@code WsContractAlignmentTest} 把契约当**数据**读入后
 * 逐项比对（字段名、required、枚举线上取值、channel 收发方向）。</p>
 */
package com.ananoesis.shell.ws;
