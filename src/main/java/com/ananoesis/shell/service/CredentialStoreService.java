package com.ananoesis.shell.service;

import java.util.Objects;
import java.util.Optional;

import com.ananoesis.shell.entity.Credential;
import com.ananoesis.shell.mapper.CredentialMapper;
import com.ananoesis.shell.security.CredentialCryptoService;
import com.ananoesis.shell.security.CredentialOwnerType;
import com.ananoesis.shell.security.CredentialType;
import com.ananoesis.shell.security.MissingModelApiKeyException;
import com.ananoesis.shell.security.SecretText;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 凭据存取服务：加密与持久化的**唯一**收口点（credential-store spec 全部 Requirement）。
 *
 * <p>WHY 必须有这一层，而不是让业务代码直接用 {@link CredentialMapper}：
 * spec 的 MUST 级要求是"数据库中 MUST NOT 存储明文凭据"。若把加密留在各调用点自觉执行，
 * 只要有一处忘了，明文就永久落盘且不会有任何报错。把加解密焊死在唯一的写入/读取通道上，
 * 这条 MUST 就从"纪律"变成"结构"——绕过它需要显式地注入 Mapper，而这在代码评审中一眼可见。</p>
 *
 * <p>日志纪律：本类只记录宿主与类型等**非敏感坐标**，绝不记录密文，更不会记录明文。
 * 明文一律以 {@link SecretText} 形态流转，其 {@code toString()} 恒为掩码。</p>
 */
@Service
public class CredentialStoreService {

    private static final Logger LOG = LoggerFactory.getLogger(CredentialStoreService.class);

    private final CredentialMapper credentialMapper;
    private final CredentialCryptoService crypto;

    public CredentialStoreService(CredentialMapper credentialMapper, CredentialCryptoService crypto) {
        this.credentialMapper = Objects.requireNonNull(credentialMapper, "credentialMapper 不得为 null");
        this.crypto = Objects.requireNonNull(crypto, "crypto 不得为 null");
    }

    /**
     * 保存（存在则覆盖）一条凭据。
     *
     * <p>副作用：{@code plaintext} 被加密服务擦除。</p>
     *
     * <p>WHY 是 upsert 而非 insert：DDL 上 {@code (owner_type, owner_id, credential_type)}
     * 唯一。用户在界面改密码时若走 insert，会撞唯一约束报错，
     * 表现为"密码改不了"这种极难自我诊断的故障。</p>
     *
     * @return 凭据行主键
     * @throws com.ananoesis.shell.security.CredentialProtectionException 无可用主密钥；此时**不会**写入任何行
     */
    public String save(CredentialOwnerType ownerType, String ownerId,
                       CredentialType type, SecretText plaintext) {
        Objects.requireNonNull(ownerType, "ownerType 不得为 null");
        Objects.requireNonNull(type, "type 不得为 null");
        requireNonBlank(ownerId, "ownerId");

        // 先加密再落库：加密失败时数据库不会留下任何半成品行
        String ciphertext = crypto.encrypt(plaintext);

        Credential existing = selectOne(ownerType, ownerId, type);
        if (existing == null) {
            Credential row = new Credential();
            row.setOwnerType(ownerType.columnValue());
            row.setOwnerId(ownerId);
            row.setCredentialType(type.columnValue());
            row.setCiphertext(ciphertext);
            credentialMapper.insert(row);
            LOG.debug("已保存凭据密文: ownerType={} ownerId={} type={}",
                    ownerType.columnValue(), ownerId, type.columnValue());
            return row.getId();
        }
        existing.setCiphertext(ciphertext);
        credentialMapper.updateById(existing);
        LOG.debug("已更新凭据密文: ownerType={} ownerId={} type={}",
                ownerType.columnValue(), ownerId, type.columnValue());
        return existing.getId();
    }

    /**
     * 读取并解密一条凭据。
     *
     * @return 明文（调用方应以 try-with-resources 使用并在用毕擦除）；不存在时为 empty
     */
    public Optional<SecretText> find(CredentialOwnerType ownerType, String ownerId, CredentialType type) {
        Credential row = selectOne(ownerType, ownerId, type);
        if (row == null) {
            return Optional.empty();
        }
        LOG.debug("已读取凭据密文，准备在内存中解密: ownerType={} ownerId={} type={}",
                ownerType.columnValue(), ownerId, type.columnValue());
        return Optional.of(crypto.decrypt(row.getCiphertext()));
    }

    public boolean exists(CredentialOwnerType ownerType, String ownerId, CredentialType type) {
        return selectOne(ownerType, ownerId, type) != null;
    }

    /** @return 删除的行数 */
    public int delete(CredentialOwnerType ownerType, String ownerId, CredentialType type) {
        int deleted = credentialMapper.delete(ownerWrapper(ownerType, ownerId)
                .eq("credential_type", type.columnValue()));
        LOG.debug("已删除凭据: ownerType={} ownerId={} type={} rows={}",
                ownerType.columnValue(), ownerId, type.columnValue(), deleted);
        return deleted;
    }

    /**
     * 删除某宿主的全部凭据。
     * WHY 需要它：删除一台服务器配置时必须连带清理其凭据，
     * 否则会留下指向不存在宿主的孤立密文，既占空间也让审计难以解释。
     */
    public int deleteByOwner(CredentialOwnerType ownerType, String ownerId) {
        int deleted = credentialMapper.delete(ownerWrapper(ownerType, ownerId));
        LOG.debug("已按宿主删除凭据: ownerType={} ownerId={} rows={}",
                ownerType.columnValue(), ownerId, deleted);
        return deleted;
    }

    /**
     * 取回模型 api key；未配置时给出 spec 指定提示。
     *
     * <p>对应 credential-store spec「API Key 不硬编码」Scenario：
     * MUST NOT 退回任何内置默认值。</p>
     *
     * @throws MissingModelApiKeyException 未配置 api key
     */
    public SecretText requireLlmApiKey(String modelConfigId) {
        requireNonBlank(modelConfigId, "modelConfigId");
        return find(CredentialOwnerType.MODEL_CONFIG, modelConfigId, CredentialType.LLM_API_KEY)
                .orElseThrow(() -> new MissingModelApiKeyException("modelConfigId=" + modelConfigId));
    }

    private Credential selectOne(CredentialOwnerType ownerType, String ownerId, CredentialType type) {
        return credentialMapper.selectOne(ownerWrapper(ownerType, ownerId)
                .eq("credential_type", type.columnValue()));
    }

    private static QueryWrapper<Credential> ownerWrapper(CredentialOwnerType ownerType, String ownerId) {
        Objects.requireNonNull(ownerType, "ownerType 不得为 null");
        requireNonBlank(ownerId, "ownerId");
        return new QueryWrapper<Credential>()
                .eq("owner_type", ownerType.columnValue())
                .eq("owner_id", ownerId);
    }

    private static void requireNonBlank(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " 不得为空");
        }
    }
}