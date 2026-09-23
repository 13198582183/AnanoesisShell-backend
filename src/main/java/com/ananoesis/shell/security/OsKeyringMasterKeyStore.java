package com.ananoesis.shell.security;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/**
 * 把 AES 主密钥托管在操作系统密钥库中（credential-store spec：主密钥由 OS 密钥库保护）。
 *
 * <p>生命周期语义：首次使用时生成一把 256 位随机主密钥并写入密钥库，此后一直复用。
 * 主密钥**从不**出现在配置文件、数据库或代码中——数据库里只有用它加密后的密文信封。</p>
 *
 * <p>WHY 条目损坏/长度不符时报错而不是重新生成：
 * 重新生成意味着换了一把钥匙，用户此前保存的所有密文凭据将永久无法解密，
 * 而表面上"一切正常"。这类静默数据丢失是本模块最需要杜绝的失败模式，
 * 因此这里必须显式失败，把处置权交回给人（修复密钥库，或走主密码回退）。</p>
 */
public class OsKeyringMasterKeyStore implements MasterKeyProvider {

    /** 写入密文信封 {@code kp} 字段的标识。 */
    public static final String ID = "os-keyring";

    /**
     * 主密钥在 OS 密钥库中的坐标。
     * WHY 由测试钉死：改名等于换锁——升级后的应用会生成一把新主密钥，
     * 用户既有的全部凭据随之永久失联，且不会有任何报错。
     */
    public static final String SERVICE_NAME = "ananoesis-shell";
    public static final String ACCOUNT_NAME = "credential-master-key";

    /** AES-256 所需的密钥字节数。 */
    private static final int KEY_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final OsKeyring keyring;

    public OsKeyringMasterKeyStore(OsKeyring keyring) {
        this.keyring = Objects.requireNonNull(keyring, "OsKeyring 不得为 null");
    }

    @Override
    public String id() {
        return ID;
    }

    /**
     * 探测式可用性检查：能完成一次读取即视为可用。
     *
     * <p>WHY 用真实读取而非缓存一个布尔值：密钥库可能在运行期才失联
     * （Linux 上 SecretService 退出、macOS 钥匙串被锁定），
     * 缓存的结论会让系统继续以为保护有效，进而做出错误决策。</p>
     */
    @Override
    public boolean isAvailable() {
        try {
            keyring.read(SERVICE_NAME, ACCOUNT_NAME);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public byte[] copyMasterKeyBytes() {
        String stored = readFromKeyring();
        if (stored != null) {
            return decode(stored);
        }
        return generateAndPersist();
    }

    private String readFromKeyring() {
        try {
            return keyring.read(SERVICE_NAME, ACCOUNT_NAME);
        } catch (RuntimeException e) {
            throw CredentialProtectionException.unavailable(
                    "无法读取操作系统密钥库（service=" + SERVICE_NAME + ", account=" + ACCOUNT_NAME + "）", e);
        }
    }

    private byte[] generateAndPersist() {
        byte[] fresh = new byte[KEY_BYTES];
        RANDOM.nextBytes(fresh);
        try {
            keyring.write(SERVICE_NAME, ACCOUNT_NAME, Base64.getEncoder().encodeToString(fresh));
        } catch (RuntimeException e) {
            // 写入失败意味着密钥无处安放，刚生成的密钥必须立即擦除，不留副本
            Arrays.fill(fresh, (byte) 0);
            throw CredentialProtectionException.unavailable("无法把主密钥写入操作系统密钥库", e);
        }
        return fresh;
    }

    private byte[] decode(String stored) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(stored.trim());
        } catch (IllegalArgumentException e) {
            throw CredentialProtectionException.unavailable(
                    "密钥库中的主密钥条目已损坏（不是合法 Base64）；请勿覆盖，可改用主密码回退", e);
        }
        if (key.length != KEY_BYTES) {
            Arrays.fill(key, (byte) 0);
            throw CredentialProtectionException.unavailable(
                    "密钥库中的主密钥长度为 " + key.length + " 字节，期望 " + KEY_BYTES
                            + " 字节；请勿覆盖，可改用主密码回退", null);
        }
        return key;
    }
}