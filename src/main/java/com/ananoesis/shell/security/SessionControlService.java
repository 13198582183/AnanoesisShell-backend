package com.ananoesis.shell.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 会话控制凭证服务（task 4.2）。
 *
 * <p>创建连接时生成 256-bit {@code control_token}，仅存其 SHA-256 散列。
 * 前端只保留内存，不写 URL、日志、localStorage 或数据库（design.md D2）。</p>
 *
 * <h2>安全约束</h2>
 * <ul>
 *   <li>明文 token 仅在 {@link #createToken} 返回时存在一次，之后服务端只持有散列</li>
 *   <li>验证时对输入的 token 做 SHA-256 再与存储的散列比较</li>
 *   <li>使用 {@link MessageDigest#isEqual} 做常量时间比较，防止时序攻击</li>
 *   <li>每个 session 同一时刻只有一个有效 token</li>
 * </ul>
 *
 * <p>WHY 仅存散列：即使内存被转储或日志泄露，攻击者也无法还原出控制凭证。
 * 这是 credential-store spec 的核心要求之一。</p>
 */
@Service
public class SessionControlService {

    private static final Logger LOG = LoggerFactory.getLogger(SessionControlService.class);

    /** 256-bit = 32 字节。 */
    private static final int TOKEN_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    /** sessionId → token 的 SHA-256 散列（字节数组）。 */
    private final Map<UUID, byte[]> tokenHashes = new ConcurrentHashMap<>();

    /**
     * 为指定会话创建新的控制凭证。
     *
     * <p>生成 256-bit 随机 token，存储其 SHA-256 散列，返回 token 的十六进制字符串。
     * 若该 session 已有旧 token，旧 token 被替换（失效）。</p>
     *
     * <p>WHY 先转 hex 再散列、最后擦除 char/byte 副本：
     * 返回给调用方的 String 无法擦除（Java 限制），但服务内部不保留任何明文副本。
     * 堆上的临时 byte[] 在方法返回前被清零。</p>
     *
     * @param sessionId 会话标识
     * @return 64 字符的十六进制 token（256-bit）
     */
    public String createToken(UUID sessionId) {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId 不得为 null");
        }
        byte[] tokenBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(tokenBytes);

        // 先转为十六进制字符串（返回给调用方）
        String hexToken = bytesToHex(tokenBytes);

        // 存储 SHA-256 散列，不存明文
        byte[] hash = sha256(tokenBytes);
        tokenHashes.put(sessionId, hash);

        // 擦除堆上的明文凭据副本
        Arrays.fill(tokenBytes, (byte) 0);

        return hexToken;
    }

    /**
     * 验证控制凭证。
     *
     * @param sessionId 会话标识
     * @param token     待验证的十六进制 token
     * @return 凭证有效返回 true；不存在、已销毁或不匹配返回 false
     */
    public boolean verify(UUID sessionId, String token) {
        if (sessionId == null || token == null) {
            return false;
        }
        byte[] storedHash = tokenHashes.get(sessionId);
        if (storedHash == null) {
            return false;
        }
        byte[] tokenBytes;
        try {
            tokenBytes = hexToBytes(token);
        } catch (IllegalArgumentException e) {
            return false;
        }
        byte[] tokenHash = sha256(tokenBytes);
        // 擦除临时明文
        Arrays.fill(tokenBytes, (byte) 0);

        // WHY 使用 MessageDigest.isEqual 而非 Arrays.equals：常量时间比较，
        // 防止时序攻击推断散列值
        return MessageDigest.isEqual(storedHash, tokenHash);
    }

    /**
     * 销毁指定会话的控制凭证（会话关闭时调用）。
     */
    public void destroyToken(UUID sessionId) {
        if (sessionId != null) {
            tokenHashes.remove(sessionId);
        }
    }

    // ==================================================================
    // 内部辅助
    // ==================================================================

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 Java 平台必须支持的算法，不可能到达
            throw new AssertionError("SHA-256 不可用", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("十六进制字符串长度必须为偶数");
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi == -1 || lo == -1) {
                throw new IllegalArgumentException("非法十六进制字符: index=" + (i * 2));
            }
            bytes[i] = (byte) ((hi << 4) | lo);
        }
        return bytes;
    }
}
