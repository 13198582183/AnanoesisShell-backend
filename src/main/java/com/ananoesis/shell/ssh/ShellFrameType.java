package com.ananoesis.shell.ssh;

/**
 * Shell 帧类型枚举（task 5.1 桩）。
 * WHY 桩：ShellFrameDecoderTest 引用此类，编译需要它存在。
 */
public enum ShellFrameType {
    CMD_START,
    CMD_END,
    CWD,
    PROMPT
}
