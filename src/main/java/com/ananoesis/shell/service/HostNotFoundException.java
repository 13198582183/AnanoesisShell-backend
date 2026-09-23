package com.ananoesis.shell.service;

/**
 * 请求的服务器配置不存在（ssh-connection spec「服务器配置管理」）。
 *
 * <p>WHY 单独建类型而不是复用 {@code NotFoundException}：调用方（尤其是 SSH 连接服务）
 * 需要能精确 catch "主机配置没了" 这一种情况——例如终端会话持有 hostId 而配置被并发删除时，
 * 应给出"服务器配置已删除"而不是笼统的"资源不存在"。</p>
 */
public class HostNotFoundException extends NotFoundException {

    public HostNotFoundException(String message) {
        super(message);
    }

    /** @param hostId 未命中的主键，仅用于消息拼装（非敏感坐标） */
    public static HostNotFoundException forId(String hostId) {
        return new HostNotFoundException("服务器配置不存在: id=" + hostId);
    }
}
