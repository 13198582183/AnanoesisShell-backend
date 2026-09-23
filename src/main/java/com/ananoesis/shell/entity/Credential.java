package com.ananoesis.shell.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 凭据密文记录（表 {@code credentials}）——全库**唯一**的凭据落点。
 *
 * <p>对应 credential-store spec：SSH 密码、私钥、私钥 passphrase、大模型 api key 一律
 * 以 AES-GCM 密文信封形式存放在 {@link #ciphertext}，信封自带 IV 与主密钥来源标识，
 * 因此本表不存（也 MUST NOT 存）任何密钥材料。</p>
 *
 * <p>WHY 用 {@code (ownerType, ownerId, credentialType)} 三元组而非固定外键：凭据宿主既可能是
 * 主机，也可能是模型配置或设置项；泛化宿主让新增凭据种类无需改表结构。</p>
 */
@TableName("credentials")
public class Credential {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 宿主类型：{@code host} / {@code model_config} / {@code setting}。 */
    private String ownerType;

    /** 宿主标识（如 hosts.id）。 */
    private String ownerId;

    /**
     * 凭据类型：{@code ssh_password} / {@code ssh_private_key} / {@code ssh_passphrase}
     * / {@code llm_api_key} / {@code generic_secret}。
     * WHY 类型必须落列：审计需能在**不解密**的前提下回答"哪类凭据被谁持有"。
     */
    private String credentialType;

    /** AES-GCM 密文信封（Base64，形如 {@code v1.<payload>}）。 */
    private String ciphertext;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOwnerType() {
        return ownerType;
    }

    public void setOwnerType(String ownerType) {
        this.ownerType = ownerType;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getCredentialType() {
        return credentialType;
    }

    public void setCredentialType(String credentialType) {
        this.credentialType = credentialType;
    }

    public String getCiphertext() {
        return ciphertext;
    }

    public void setCiphertext(String ciphertext) {
        this.ciphertext = ciphertext;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * WHY 显式实现且不打印完整密文：只留长度，避免密文被整段复制进日志后
     * 成为离线爆破语料；同时确保任何情况下都不会出现明文。
     */
    @Override
    public String toString() {
        return "Credential{id=" + id + ", ownerType=" + ownerType + ", ownerId=" + ownerId
                + ", credentialType=" + credentialType
                + ", ciphertext=<len=" + (ciphertext == null ? 0 : ciphertext.length()) + ">}";
    }
}
