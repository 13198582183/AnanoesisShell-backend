package com.ananoesis.shell.security;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 凭据加解密服务（credential-store spec：凭据加密存储 / 按需解密使用）。
 *
 * <p>算法选型 AES-256-GCM。WHY 必须是 AEAD 而非 CBC：GCM 自带认证标签，
 * 密文一旦被篡改就会在解密时确定性地失败。CBC 无 MAC，攻击者可以在不知道密钥的情况下
 * 翻转密文位并让明文发生可预测的变化——对一个存着 SSH 密码与私钥的库来说，
 * "能察觉改动"和"能解密"同等重要。</p>
 *
 * <p>密文信封格式（**持久化契约**，已由 {@code CredentialCryptoServiceTest} 钉死）：
 * <pre>
 * v1.&lt;base64url(JSON)&gt;
 * JSON = { "v":1, "kp":"os-keyring"|"master-password",
 *          "iv":"&lt;b64url 12B&gt;", "ct":"&lt;b64url 密文+GCM标签&gt;",
 *          "salt":"&lt;b64url 16B&gt;"?, "it":&lt;迭代次数&gt;? }
 * </pre>
 * WHY 自描述：{@code kp} 决定用哪把主密钥解密，{@code salt}/{@code it} 让主密码回退路径
 * 在将来调整 KDF 参数后仍能解开历史密文。把这些元数据放进信封而不是另建表，
 * 是为了保证"密文与解密所需信息永不分离"。</p>
 *
 * <p>WHY 版本号前缀 {@code v1.} 同时出现在字符串前缀与 JSON 内：
 * 前缀让运维在数据库里一眼识别格式代际（甚至能用 SQL LIKE 排查），
 * JSON 内的 {@code v} 则是解析时的权威依据。两者不一致时以更严格的一侧为准，即拒绝。</p>
 *
 * <p>安全纪律：本类**从不**记录明文、密钥或完整密文；所有中间缓冲在 finally 中清零；
 * 加密与解密都会擦除调用方交出的 {@link SecretText}，使明文的生命周期由类型而非纪律约束。</p>
 */
public class CredentialCryptoService {

    private static final Logger LOG = LoggerFactory.getLogger(CredentialCryptoService.class);

    /** 信封前缀，也是格式代际标识。 */
    public static final String ENVELOPE_PREFIX = "v1.";

    private static final int ENVELOPE_VERSION = 1;
    /** GCM 推荐 IV 长度（NIST SP 800-38D）。 */
    private static final int IV_BYTES = 12;
    /** 128 位认证标签：GCM 的最强档，也是唯一被广泛验证过的长度。 */
    private static final int GCM_TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<MasterKeyProvider> providers;
    private final MasterPasswordKeyDeriver deriver;
    private final int pbkdf2Iterations;

    public CredentialCryptoService(List<MasterKeyProvider> providers) {
        this(providers, new MasterPasswordKeyDeriver(), MasterPasswordKeyDeriver.DEFAULT_ITERATIONS);
    }

    public CredentialCryptoService(List<MasterKeyProvider> providers,
                                   MasterPasswordKeyDeriver deriver,
                                   int pbkdf2Iterations) {
        this.providers = List.copyOf(Objects.requireNonNull(providers, "providers 不得为 null"));
        this.deriver = Objects.requireNonNull(deriver, "deriver 不得为 null");
        this.pbkdf2Iterations = pbkdf2Iterations;
    }

    /** @return 是否存在可用的主密钥来源；为 false 时只能走主密码回退路径 */
    public boolean isAvailable() {
        for (MasterKeyProvider provider : providers) {
            if (probe(provider)) {
                return true;
            }
        }
        return false;
    }

    // ======================================================================
    // 主路径：主密钥托管于 OS 密钥库
    // ======================================================================

    /**
     * 加密凭据。
     *
     * <p>副作用：入参 {@code plaintext} 会被擦除（"交出即消费"）。
     * WHY 采用这个语义而不是"调用方自行擦除"：漏擦是安全代码里最常见的缺陷，
     * 且不会有任何测试或报错提醒。把擦除责任收进服务，明文驻留时间就由实现保证，
     * 不再依赖每个调用点的自觉。</p>
     *
     * @throws CredentialProtectionException 无可用主密钥来源；消息含「凭据保护不可用」。
     *                                       **绝不**返回明文作为降级。
     */
    public String encrypt(SecretText plaintext) {
        Objects.requireNonNull(plaintext, "待加密的明文不得为 null");
        try {
            MasterKeyProvider provider = requireAvailableProvider();
            byte[] key = provider.copyMasterKeyBytes();
            try {
                char[] chars = plaintext.revealChars();
                try {
                    return seal(provider.id(), chars, key, null, 0);
                } finally {
                    Arrays.fill(chars, '\0');
                }
            } finally {
                Arrays.fill(key, (byte) 0);
            }
        } finally {
            plaintext.wipe();
        }
    }

    /**
     * 解密由 OS 密钥库主密钥保护的信封。
     *
     * @throws CredentialCryptoException       信封非法、被篡改，或该信封需主密码路径
     * @throws CredentialProtectionException   运行期密钥库失联
     */
    public SecretText decrypt(String envelope) {
        Envelope parsed = parse(envelope);
        if (MasterPasswordKeyDeriver.ID.equals(parsed.keyProvider())) {
            throw new CredentialCryptoException(
                    "该密文由用户主密码派生密钥保护，请改用 decryptWithMasterPassword 并提供主密码");
        }
        MasterKeyProvider provider = findProvider(parsed.keyProvider());
        byte[] key = provider.copyMasterKeyBytes();
        try {
            return open(parsed, key);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    // ======================================================================
    // 回退路径：用户主密码派生密钥（tasks 4.3）
    // ======================================================================

    /**
     * 用主密码派生的密钥加密。密钥库不可用时的唯一合法出路——依然是加密，绝不降级为明文。
     *
     * <p>副作用：{@code plaintext} 与 {@code masterPassword} 均被擦除。</p>
     */
    public String encryptWithMasterPassword(SecretText plaintext, SecretText masterPassword) {
        Objects.requireNonNull(plaintext, "待加密的明文不得为 null");
        Objects.requireNonNull(masterPassword, "主密码不得为 null");
        try {
            requireNonBlank(masterPassword, "主密码");
            byte[] salt = deriver.newSalt();
            byte[] key = deriveAndConsume(masterPassword, salt, pbkdf2Iterations);
            try {
                char[] chars = plaintext.revealChars();
                try {
                    return seal(MasterPasswordKeyDeriver.ID, chars, key, salt, pbkdf2Iterations);
                } finally {
                    Arrays.fill(chars, '\0');
                }
            } finally {
                Arrays.fill(key, (byte) 0);
            }
        } finally {
            plaintext.wipe();
            masterPassword.wipe();
        }
    }

    /** 用主密码派生的密钥解密。副作用：{@code masterPassword} 被擦除。 */
    public SecretText decryptWithMasterPassword(String envelope, SecretText masterPassword) {
        Objects.requireNonNull(masterPassword, "主密码不得为 null");
        try {
            requireNonBlank(masterPassword, "主密码");
            Envelope parsed = parse(envelope);
            if (!MasterPasswordKeyDeriver.ID.equals(parsed.keyProvider())) {
                throw new CredentialCryptoException(
                        "该密文不是由主密码派生密钥保护的，无法用主密码解密");
            }
            if (parsed.salt() == null || parsed.iterations() <= 0) {
                throw new CredentialCryptoException("密文信封缺少 KDF 参数（salt/it），无法派生主密钥");
            }
            byte[] key = deriveAndConsume(masterPassword, parsed.salt(), parsed.iterations());
            try {
                return open(parsed, key);
            } finally {
                Arrays.fill(key, (byte) 0);
            }
        } finally {
            masterPassword.wipe();
        }
    }

    // ======================================================================
    // 内部实现
    // ======================================================================

    private MasterKeyProvider requireAvailableProvider() {
        for (MasterKeyProvider provider : providers) {
            if (probe(provider)) {
                return provider;
            }
        }
        // WHY 这段文案要写清出路：spec 要求"明确报错"，而明确不只是报错，
        // 还要让用户知道下一步能做什么，否则只会得到一句无法行动的失败。
        throw CredentialProtectionException.unavailable(
                "没有可用的主密钥来源——操作系统密钥库不可达，且未提供用户主密码。"
                        + "系统拒绝以明文保存凭据；请检查密钥库状态，或改用主密码派生密钥。", null);
    }

    private MasterKeyProvider findProvider(String keyProviderId) {
        for (MasterKeyProvider provider : providers) {
            if (provider.id().equals(keyProviderId)) {
                return provider;
            }
        }
        throw new CredentialCryptoException("找不到与密文信封匹配的主密钥来源: " + keyProviderId);
    }

    /** 可用性探测本身也可能抛异常（后端已崩），此处必须吞掉并继续尝试下一个来源。 */
    private boolean probe(MasterKeyProvider provider) {
        try {
            return provider.isAvailable();
        } catch (RuntimeException e) {
            LOG.warn("主密钥来源 [{}] 可用性探测失败，将跳过", provider.id());
            return false;
        }
    }

    private byte[] deriveAndConsume(SecretText masterPassword, byte[] salt, int iterations) {
        char[] password = masterPassword.revealChars();
        try {
            return deriver.derive(password, salt, iterations);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static void requireNonBlank(SecretText secret, String what) {
        char[] chars = secret.revealChars();
        try {
            for (char c : chars) {
                if (!Character.isWhitespace(c)) {
                    return;
                }
            }
            throw new CredentialProtectionException(
                    what + "不能为空：回退路径的意义在于换一把由用户掌握的密钥，而不是取消加密");
        } finally {
            Arrays.fill(chars, '\0');
        }
    }

    private String seal(String keyProviderId, char[] plaintextChars, byte[] key,
                        byte[] salt, int iterations) {
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        byte[] plainBytes = toBytes(plaintextChars);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, KEY_ALGORITHM),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plainBytes);

            ObjectNode payload = JSON.createObjectNode();
            payload.put("v", ENVELOPE_VERSION);
            payload.put("kp", keyProviderId);
            payload.put("iv", encode(iv));
            payload.put("ct", encode(ciphertext));
            if (salt != null) {
                payload.put("salt", encode(salt));
                payload.put("it", iterations);
            }
            return ENVELOPE_PREFIX + encode(JSON.writeValueAsBytes(payload));
        } catch (GeneralSecurityException | IOException e) {
            throw new CredentialCryptoException("凭据加密失败", e);
        } finally {
            Arrays.fill(plainBytes, (byte) 0);
        }
    }

    private SecretText open(Envelope envelope, byte[] key) {
        byte[] plainBytes = null;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, KEY_ALGORITHM),
                    new GCMParameterSpec(GCM_TAG_BITS, envelope.iv()));
            plainBytes = cipher.doFinal(envelope.ciphertext());
            char[] chars = toChars(plainBytes);
            try {
                return SecretText.of(chars);
            } finally {
                Arrays.fill(chars, '\0');
            }
        } catch (AEADBadTagException e) {
            // WHY 单独识别：GCM 标签不匹配是"数据被改过或密钥不对"的确定性信号，
            // 值得给出比"解密失败"更可行动的提示。消息本身不含任何密文或明文信息。
            throw new CredentialCryptoException("密文校验失败：数据可能已被篡改，或主密钥与加密时不一致", e);
        } catch (GeneralSecurityException e) {
            throw new CredentialCryptoException("凭据解密失败", e);
        } finally {
            if (plainBytes != null) {
                Arrays.fill(plainBytes, (byte) 0);
            }
        }
    }

    private Envelope parse(String envelope) {
        if (envelope == null || !envelope.startsWith(ENVELOPE_PREFIX)) {
            throw new CredentialCryptoException("密文信封格式非法：缺少 '" + ENVELOPE_PREFIX + "' 前缀");
        }
        byte[] json;
        try {
            json = Base64.getUrlDecoder().decode(envelope.substring(ENVELOPE_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new CredentialCryptoException("密文信封格式非法：不是合法的 Base64URL 编码", e);
        }
        JsonNode node;
        try {
            node = JSON.readTree(json);
        } catch (IOException e) {
            throw new CredentialCryptoException("密文信封格式非法：不是合法的 JSON", e);
        }
        if (node == null || !node.isObject()) {
            throw new CredentialCryptoException("密文信封格式非法：顶层不是 JSON 对象");
        }
        int version = node.path("v").asInt(-1);
        if (version != ENVELOPE_VERSION) {
            throw new CredentialCryptoException("不支持的密文信封版本: " + version);
        }
        String keyProvider = node.path("kp").asText(null);
        if (keyProvider == null || keyProvider.isBlank()) {
            throw new CredentialCryptoException("密文信封缺少主密钥来源标识 kp");
        }
        byte[] iv = decodeField(node, "iv");
        if (iv.length != IV_BYTES) {
            throw new CredentialCryptoException("密文信封 IV 长度非法: " + iv.length + " 字节");
        }
        byte[] ciphertext = decodeField(node, "ct");
        byte[] salt = node.has("salt") ? decodeField(node, "salt") : null;
        int iterations = node.path("it").asInt(0);
        return new Envelope(version, keyProvider, iv, ciphertext, salt, iterations);
    }

    private static byte[] decodeField(JsonNode node, String field) {
        String text = node.path(field).asText(null);
        if (text == null || text.isBlank()) {
            throw new CredentialCryptoException("密文信封缺少字段: " + field);
        }
        try {
            return Base64.getUrlDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            throw new CredentialCryptoException("密文信封字段 " + field + " 不是合法的 Base64URL", e);
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * WHY 用 CharBuffer 而不是 {@code new String(chars).getBytes(UTF_8)}：
     * 后者会在堆上留下一个**不可擦除**的明文 String，正是 SecretText 想避免的东西。
     */
    private static byte[] toBytes(char[] chars) {
        ByteBuffer buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    private static char[] toChars(byte[] bytes) {
        CharBuffer buffer = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes));
        char[] chars = new char[buffer.remaining()];
        buffer.get(chars);
        return chars;
    }

    /** 解析后的信封。数组字段由 {@link #parse} 独占构造，不对外暴露可变引用。 */
    private record Envelope(int version, String keyProvider, byte[] iv, byte[] ciphertext,
                            byte[] salt, int iterations) {
    }
}