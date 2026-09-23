package com.ananoesis.shell.ssh;

/**
 * 解析出的 OSC 1337 控制帧（task 5.1）。
 *
 * <p>WHY 用 record：帧是不可变的事实，不需要 setter；
 * 且 {@code toString()} 天然可用于日志（内容不含凭据）。</p>
 *
 * @param type      帧类型
 * @param nonce     会话随机标识，用于减少误识别
 * @param commandId 命令标识
 * @param payload   帧负载（cmd_end 为退出码，cwd 为目录路径，其他为空）
 */
public record ShellFrame(ShellFrameType type, String nonce, String commandId, String payload) {

    public ShellFrame {
        if (type == null) {
            throw new IllegalArgumentException("type 不得为 null");
        }
        nonce = nonce == null ? "" : nonce;
        commandId = commandId == null ? "" : commandId;
        payload = payload == null ? "" : payload;
    }
}
