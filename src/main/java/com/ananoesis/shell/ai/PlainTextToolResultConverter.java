package com.ananoesis.shell.ai;

import java.lang.reflect.Type;

import org.springframework.ai.tool.execution.ToolCallResultConverter;
import org.springframework.lang.Nullable;

/**
 * 把工具返回值<b>原样</b>当作纯文本交给模型（tasks 9.2）。
 *
 * <h2>WHY 需要它：Spring AI 的默认转换器会把 String 再 JSON 化一遍</h2>
 * <p>{@code DefaultToolCallResultConverter#convert} 的实现里没有"已经是字符串就原样返回"
 * 这一分支——它对任何返回值都调 {@code JsonParser.toJson(result)}。
 * 于是 {@code AgentTools} 返回的</p>
 * <pre>
 *   exit=0
 *   total 12
 *   -rw-r--r-- 1 root root 0 ... access.log
 * </pre>
 * <p>会被转成<b>一个 JSON 字符串字面量</b>：</p>
 * <pre>
 *   "exit=0\ntotal 12\n-rw-r--r-- 1 root root 0 ... access.log\n"
 * </pre>
 * <p>换行变成了两个字面字符 {@code \n}，两头还多出一对引号。这段文本有三个去处，
 * 每一处都被它破坏：</p>
 * <ol>
 *   <li><b>回喂给模型</b>：模型看到的是一坨带转义符的单行文本。多行输出（{@code ls -Al}、
 *       {@code df -hP}、日志片段）的可读性正是模型据以推理的东西，
 *       压成一行会显著降低它从中提取信息的准确率；</li>
 *   <li><b>{@code ai_stream(tool_result)} 帧</b>：前端会把 {@code result} 直接渲染到
 *       工具条目里，用户于是看到满屏 {@code \n} 字面量——"操作透明性"变成了操作乱码；</li>
 *   <li><b>{@code ai_messages.tool_result} 列</b>：历史面板刷新后同样是坏的。</li>
 * </ol>
 *
 * <p>WHY 不在 {@code AiAgentService} 里"解一次 JSON"：那是把上游的编码错误
 * 用下游的猜测去补救——一旦哪天框架修好了默认转换器，解 JSON 就会反过来把
 * 真的以引号开头的输出削掉一层。转换策略属于<b>工具的声明</b>，
 * 因此正确的位置是 {@code @Tool(resultConverter = ...)}。</p>
 *
 * <p>WHY {@code run_command} 不受影响：它的执行权在 {@code ApprovedCommandRunner} 手里，
 * 结果由 {@code CommandExecution#feedback()} 直接产出，从不经过工具回调。
 * 但本类仍给它的 {@code @Tool} 声明挂上同一个转换器——那个方法体只会抛异常，
 * 挂转换器纯粹是为了"四个工具的声明保持一致"，避免将来有人给它补上真实实现时
 * 静默继承默认行为。</p>
 *
 * <h2>线程安全</h2>
 * <p>无状态，可被框架复用为单例。</p>
 */
public final class PlainTextToolResultConverter implements ToolCallResultConverter {

    @Override
    public String convert(@Nullable Object result, @Nullable Type targetType) {
        // WHY null 归一成空串而不是字面量 "null"：工具没有返回值时，
        // 模型看到 "null" 会以为远端真的输出了这四个字符
        return result == null ? "" : result.toString();
    }
}
