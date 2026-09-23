package com.ananoesis.shell.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.ananoesis.shell.entity.Credential;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * {@code credentials} 表数据访问——全库唯一的密文出入口（credential-store spec）。
 *
 * <p>安全约束：本 Mapper 读写的 {@code ciphertext} 列必须是**已加密**的信封文本。
 * 任何绕过加密服务直接写入明文的做法都违反 spec「数据库中 MUST NOT 存储明文凭据」，
 * 因此凭据的加解密统一收口在 {@code CredentialCryptoService}，Mapper 层不做任何编解码。</p>
 */
@Mapper
public interface CredentialMapper extends BaseMapper<Credential> {
}