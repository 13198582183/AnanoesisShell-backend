package com.ananoesis.shell.ws;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 运维工具名（asyncapi.yaml {@code components/schemas/ToolName}）。
 *
 * <p>WHY 工具<b>分级</b>也定义在这里，而不是散在执行代码里：
 * ai-agent spec「工具分级执行策略」把四个工具分成两级——只读工具自动执行、
 * 副作用工具必须经人工审批。这条分级是<b>安全边界</b>：一旦某个地方把
 * {@code run_command} 当成只读工具处理，命令就会在用户毫不知情的情况下落到生产机上。
 * 把"哪些工具自动执行"收进枚举本身的一个方法，意味着新增工具时
 * <b>必须</b>显式回答这个问题——{@link #autoExecuted()} 是穷举 switch 表达式且不带 default，
 * 新增常量会让它<b>编译不过</b>，而不是"忘了写 if 于是走了自动执行那条路"。</p>
 *
 * <p>WHY 每个常量都写 {@code @JsonProperty}：同 {@link TerminalInput.Action}——
 * Jackson 默认按常量名匹配，而契约线上取值是小写下划线形式。</p>
 */
public enum ToolName {

    /** 列目录（只读）。 */
    @JsonProperty("list_dir") LIST_DIR("list_dir"),
    /** 读文件（只读，带行数上限）。 */
    @JsonProperty("read_file") READ_FILE("read_file"),
    /** 系统信息（只读）。 */
    @JsonProperty("system_info") SYSTEM_INFO("system_info"),
    /** 执行任意命令（<b>副作用</b>，必须经审批闸门）。 */
    @JsonProperty("run_command") RUN_COMMAND("run_command");

    private final String value;

    ToolName(String value) {
        this.value = value;
    }

    /** @return 契约线上取值。 */
    @com.fasterxml.jackson.annotation.JsonValue
    public String getValue() {
        return value;
    }

    /**
     * @return 是否可自动执行（无需人工审批）
     *
     * <p>WHY 写成穷举 switch 表达式且<b>不带</b> default，而不是 {@code this != RUN_COMMAND}：
     * 后者在新增工具时会<b>静默</b>把新工具判成"只读、可自动执行"——这正是最危险的失败方向
     * （一条副作用命令绕过审批直接落到远端）。不带 default 的 switch 表达式要求覆盖全部常量，
     * 漏掉任何一个都<b>编译不过</b>，逼开发者当场对新工具做出分级决定。</p>
     *
     * <p>顺带说明：线上出现的<b>未知工具名</b>到不了这里——{@link #fromValue(String)} 会返回
     * null，由调用方作为工具错误回喂给模型让它自纠。</p>
     */
    public boolean autoExecuted() {
        return switch (this) {
            case LIST_DIR, READ_FILE, SYSTEM_INFO -> true;
            case RUN_COMMAND -> false;
        };
    }

    /**
     * 按线上取值解析工具名。
     *
     * @return 未匹配时为 {@code null}——模型可能幻觉出一个不存在的工具名，
     *         调用方应把它作为工具结果里的错误回喂给模型（而不是抛异常中断整轮对话），
     *         这样模型有机会自我纠正
     */
    public static ToolName fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (ToolName candidate : values()) {
            if (candidate.value.equals(value)) {
                return candidate;
            }
        }
        return null;
    }
}
