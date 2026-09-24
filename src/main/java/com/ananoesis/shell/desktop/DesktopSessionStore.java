package com.ananoesis.shell.desktop;

import java.security.SecureRandom;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HexFormat;

import org.springframework.lang.Nullable;

/**
 * 桌面守卫的内存态会话票仓（design D3）。
 *
 * <p>tk 引导通过后在此登记一张随机会话票；后续 REST/WS 请求凭 HttpOnly Cookie
 * 中的票号到这里验真。票只存内存——<b>进程重启即全部失效</b>是刻意设计：
 * 桌面形态下后端生命周期由壳独管，壳每次启动都会重新生成 tk 并走一遍引导，
 * 落盘票仓没有任何消费场景，反而多出一个磁盘泄露面。</p>
 *
 * <p>WHY 票号自造而非复用 tk：tk 是"引导资格"，一次性消耗后置入 URL 掩码流程；
 * 会话票是"访问资格"，短生命周期、随机、与 tk 无推导关系——爆破其一走不通另一条。</p>
 */
public final class DesktopSessionStore {

    /** 会话票号长度（字节）。256bit 随机数在本机场景下无碰撞与爆破之虞。 */
    private static final int SESSION_ID_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Set<String> validSessions = ConcurrentHashMap.newKeySet();

    /**
     * 签发一张新会话票并登记。
     *
     * @return 64 位十六进制随机票号
     */
    public String issue() {
        byte[] bytes = new byte[SESSION_ID_BYTES];
        RANDOM.nextBytes(bytes);
        String sessionId = HexFormat.of().formatHex(bytes);
        validSessions.add(sessionId);
        return sessionId;
    }

    /**
     * 验真：票号是否为本进程签发且仍有效。
     *
     * @param sessionId 来自 Cookie 的候选票号，可为 null
     * @return 有效为 true
     */
    public boolean isValid(@Nullable String sessionId) {
        return sessionId != null && validSessions.contains(sessionId);
    }

    /** 当前有效票数。仅供测试与排障断言"无票泄漏式累积"。 */
    public int activeCount() {
        return validSessions.size();
    }
}
