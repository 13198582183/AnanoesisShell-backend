package com.ananoesis.shell.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 应用设置项（表 {@code settings}，键值结构）。
 *
 * <p>安全边界（credential-store spec）：本表**只存非敏感配置**——可公开查看的行为开关与阈值。
 * api key 等敏感项一律走 {@link Credential}（{@code owner_type='model_config'}）密文保存。
 * 把这条边界做进表结构，是为了从根上杜绝"顺手把 api key 塞进 settings"的漂移。</p>
 *
 * <p>模型配置（provider / base_url / model）以 {@code valueType='json'} 存于此表，
 * 其 api key 单独密文保存——配置可导出，秘密不可导出。</p>
 *
 * <p>WHY 主键是业务键 {@code settingKey} 而非 UUID：设置项按 key 点查是唯一访问模式，
 * 用 key 作主键既省一次索引跳转，也让 {@code selectById("approval.timeout.seconds")}
 * 这样的调用自解释；因此主键策略为 {@link IdType#INPUT}（由调用方显式提供）。</p>
 */
@TableName("settings")
public class Setting {

    /** 设置键，主键，由调用方提供。 */
    @TableId(value = "setting_key", type = IdType.INPUT)
    private String settingKey;

    /** 设置值，统一以文本存储，按 {@link #valueType} 解释。 */
    private String settingValue;

    /** 值类型：{@code string} / {@code number} / {@code boolean} / {@code json}。 */
    private String valueType;

    /** 人类可读说明，直接用于设置界面与审计导出。 */
    private String description;

    /** 更新时间：插入与更新时均自动填充。 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public String getSettingKey() {
        return settingKey;
    }

    public void setSettingKey(String settingKey) {
        this.settingKey = settingKey;
    }

    public String getSettingValue() {
        return settingValue;
    }

    public void setSettingValue(String settingValue) {
        this.settingValue = settingValue;
    }

    public String getValueType() {
        return valueType;
    }

    public void setValueType(String valueType) {
        this.valueType = valueType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return "Setting{settingKey=" + settingKey + ", settingValue=" + settingValue
                + ", valueType=" + valueType + ", updatedAt=" + updatedAt + "}";
    }
}
