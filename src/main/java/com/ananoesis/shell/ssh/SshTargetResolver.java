package com.ananoesis.shell.ssh;

import java.util.UUID;
import java.util.function.Function;

/**
 * 由 hostId 解析出可连接的 {@link SshTarget}（含解密后的凭据）。
 *
 * <p>WHY 是"回调式"接口而不是 {@code SshTarget resolve(UUID)}：
 * 返回值里握着明文凭据，若直接返回，"用毕擦除"就成了调用方的自觉——
 * 只要有一处忘了，明文就会在堆上留到 GC，还可能被一次堆转储捞走。
 * 回调式把擦除焊死在实现内部的 {@code finally} 里，调用方**无法**忘记。</p>
 *
 * <p>WHY 是接口：SSH 服务层因此可以在测试里被喂一个指向内嵌替身服务器的固定目标，
 * 不必为了跑一个 exec 测试而启动整个 Spring 上下文与 SQLite。</p>
 */
public interface SshTargetResolver {

    /**
     * 在"凭据已解密且尚未擦除"的窗口内执行 {@code action}。
     *
     * @param hostId 主机配置主键
     * @param action 使用目标的动作；其返回值原样透传
     * @return {@code action} 的结果
     * @throws com.ananoesis.shell.service.HostNotFoundException 配置不存在
     * @throws SshConnectException                               凭据缺失（{@code CREDENTIAL_MISSING}）
     * @throws com.ananoesis.shell.security.CredentialProtectionException 主密钥不可用
     */
    <T> T withTarget(UUID hostId, Function<SshTarget, T> action);
}
