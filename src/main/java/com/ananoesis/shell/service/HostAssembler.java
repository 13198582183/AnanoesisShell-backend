package com.ananoesis.shell.service;

import java.util.UUID;

import com.ananoesis.shell.contract.model.AuthType;
import com.ananoesis.shell.contract.model.Host;
import com.ananoesis.shell.support.Timestamps;

/**
 * 服务器配置在「契约 DTO」与「数据库实体」之间的装配。
 *
 * <p>WHY 单独一个类：契约的 {@code Host} 与实体的 {@code Host} **同名不同包**，
 * 在同一个文件里同时使用两者就必须处处写全限定名，业务逻辑会被淹没在包名里。
 * 把"翻译"这件纯机械的事隔离到本类后，{@link HostService} 只需 import 契约类型，
 * 实体一律以 {@code var} 出现，读起来仍然是业务语言。</p>
 *
 * <p>WHY 需要翻译而不是直接复用实体：契约里有 {@code credential_set} 这种
 * 数据库中根本不存在的派生字段，也有 {@code password}/{@code private_key}/
 * {@code passphrase} 这种**只进不出**的字段（credential-store spec：响应不回显明文）。
 * 让实体直接对外序列化，等于把"哪些字段可以出门"的判断交给 Jackson 注解，
 * 一次注解遗漏就是一次凭据泄露。</p>
 */
final class HostAssembler {

    private HostAssembler() {
        // 纯函数集合，禁止实例化
    }

    /**
     * 实体 → 契约 DTO。
     *
     * <p>{@code password}/{@code privateKey}/{@code passphrase} 三个字段**刻意不赋值**：
     * 它们在契约中标了 {@code writeOnly}，序列化层本就不会输出；
     * 这里再保持 null 是第二道保险——万一有人日后关掉 writeOnly 或改用别的序列化器，
     * 出门的仍然是 null 而不是明文。</p>
     *
     * @param credentialSet 该主机是否已存有当前认证方式所需的凭据（由服务层查询后传入）
     */
    static Host toDto(com.ananoesis.shell.entity.Host entity, boolean credentialSet) {
        Host dto = new Host();
        dto.setId(UUID.fromString(entity.getId()));
        dto.setHost(entity.getHost());
        dto.setPort(entity.getPort());
        dto.setUsername(entity.getUsername());
        dto.setAuthType(AuthType.fromValue(entity.getAuthType()));
        dto.setGroupName(entity.getGroupName());
        dto.setNote(entity.getRemark());
        dto.setCredentialSet(credentialSet);
        dto.setCreatedAt(Timestamps.toOffset(entity.getCreatedAt()));
        dto.setUpdatedAt(Timestamps.toOffset(entity.getUpdatedAt()));
        return dto;
    }

    /**
     * 把请求中的**非敏感**配置写入实体。凭据不在此处，见 {@link HostService}。
     *
     * <p>WHY {@code name} 取"备注或主机地址"：DDL 里 {@code hosts.name} 是 NOT NULL，
     * 而契约的 {@code Host} **没有** name 字段（只有 host 与 note）。契约已冻结、
     * DDL 也已随 Wave 1 落库，两边的落差只能在这里消化。选"备注优先"是因为
     * name 的用途是列表里给人看的标识，备注正是用户为此填写的东西；
     * 没有备注时退回主机地址，保证 NOT NULL 不被违反。</p>
     */
    static void applyConfiguration(Host request, com.ananoesis.shell.entity.Host entity) {
        entity.setHost(request.getHost());
        entity.setPort(request.getPort());
        entity.setUsername(request.getUsername());
        entity.setAuthType(request.getAuthType().getValue());
        entity.setGroupName(trimToNull(request.getGroupName()));
        entity.setRemark(trimToNull(request.getNote()));
        entity.setName(displayName(request));
    }

    /** @return 列表展示名：备注优先，缺省回退到主机地址 */
    static String displayName(Host request) {
        String note = trimToNull(request.getNote());
        return note != null ? note : request.getHost();
    }

    /**
     * WHY 空白串统一归一为 null：契约把 {@code group_name}/{@code note} 声明为可空，
     * 前端清空输入框时既可能发 null 也可能发 ""。两者在库里应当是同一个意思，
     * 否则"按分组过滤"会凭空多出一个名字为空字符串的分组。
     */
    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 供服务层判断"用户这次是否提交了某个凭据"。 */
    static boolean hasText(String value) {
        return trimToNull(value) != null;
    }
}
