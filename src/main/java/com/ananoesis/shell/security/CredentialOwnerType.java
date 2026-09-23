package com.ananoesis.shell.security;

import java.util.Locale;

/**
 * 凭据宿主类型，对应 {@code credentials.owner_type} 的 CHECK 约束取值。
 *
 * <p>WHY 用枚举而非裸字符串：DDL 的 CHECK 约束是最后一道防线，但等到数据库报错才知道
 * 拼错了 {@code "model_config"} 已经太晚。枚举把取值集合前移到编译期，
 * 也让"新增一种凭据宿主"成为一次可被搜索到的显式改动。</p>
 */
public enum CredentialOwnerType {

    /** 宿主的是一台服务器（{@code hosts.id}）。 */
    HOST("host"),
    /** 宿主的是一份模型配置；api key 走这里。 */
    MODEL_CONFIG("model_config"),
    /** 宿主的是一个设置项。 */
    SETTING("setting");

    private final String columnValue;

    CredentialOwnerType(String columnValue) {
        this.columnValue = columnValue;
    }

    /** @return 落库使用的字面值 */
    public String columnValue() {
        return columnValue;
    }

    /** 由库中字面值反查枚举。 */
    public static CredentialOwnerType fromColumnValue(String value) {
        for (CredentialOwnerType type : values()) {
            if (type.columnValue.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException(
                "未知的凭据宿主类型: " + String.valueOf(value).toLowerCase(Locale.ROOT));
    }
}