package com.ananoesis.shell.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 服务器连接配置（表 {@code hosts}）。
 *
 * <p>对应 ssh-connection spec「服务器配置管理」：必填主机地址、端口、登录用户名、认证方式，
 * 可选分组与备注。</p>
 *
 * <p>安全边界（credential-store spec）：本实体**刻意不含任何凭据字段**——认证材料一律以密文
 * 存放在 {@link Credential}（{@code owner_type='host'}, {@code owner_id=hosts.id}）。
 * 这样 hosts 记录可以安全地被日志化、导出与前端直读。</p>
 */
@TableName("hosts")
public class Host {

    /** 主键：应用侧生成的 32 位 UUID（见 V1 迁移「全局约定」）。 */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 显示名称，便于用户在服务器列表中识别。 */
    private String name;

    /** 主机地址（IP 或域名）。 */
    private String host;

    /** SSH 端口，DDL 默认 22。 */
    private Integer port;

    /** 登录用户名。 */
    private String username;

    /**
     * 认证方式：{@code password} 或 {@code private_key}（DDL CHECK 约束）。
     * WHY 用字符串而非枚举：Wave 1 只落数据层，枚举校验留待 Wave 2 的 DTO 层，
     * 避免实体与传输契约耦合。
     */
    private String authType;

    /** 分组名（可选），用于服务器列表折叠展示。 */
    private String groupName;

    /** 备注（可选）。 */
    private String remark;

    /** 创建时间：仅插入时自动填充，更新不得覆盖。 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间：插入与更新时均自动填充。 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getAuthType() {
        return authType;
    }

    public void setAuthType(String authType) {
        this.authType = authType;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
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
     * WHY 手写 toString 而非 IDE 生成：hosts 本身不含凭据，可安全打印；
     * 但显式实现能防止后续有人加了敏感字段后被默认 toString 静默带进日志。
     */
    @Override
    public String toString() {
        return "Host{id=" + id + ", name=" + name + ", host=" + host + ", port=" + port
                + ", username=" + username + ", authType=" + authType + ", groupName=" + groupName + "}";
    }
}
