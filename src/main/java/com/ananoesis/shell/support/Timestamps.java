package com.ananoesis.shell.support;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 库内时间（{@link LocalDateTime}）与契约时间（{@code date-time} → {@link OffsetDateTime}）的互转。
 *
 * <p>WHY 需要收口：V1 迁移刻意把时间列存成**不带时区**的本地时间文本
 * （见 {@code AuditMetaObjectHandler} 的注释——SQLite 的 CURRENT_TIMESTAMP 是 UTC，
 * 混用两种基准会让审计时间轴失真）；而 {@code openapi.yaml} 声明的是
 * {@code format: date-time}，openapi-generator 据此生成 {@code OffsetDateTime}。
 * 两种形态必须有个唯一的换算点，否则各处各写一套 {@code atZone(...)}，
 * 早晚会出现"列表页与详情页时间差 8 小时"这类只在特定路径复现的 bug。</p>
 *
 * <p>WHY 用 {@link ZoneId#systemDefault()}：这是单机桌面应用，写库与读库永远发生在同一台机器、
 * 同一时区，系统时区就是用户期望看到的那个基准。若将来要支持跨时区协作，
 * 应改的是存储层（改存带偏移的时间），而不是在这里换一个常量。</p>
 */
public final class Timestamps {

    private Timestamps() {
        // 工具类，禁止实例化
    }

    /** @return 契约形态的时间；入参为 null 时返回 null（可空列如 ended_at 依赖此语义） */
    public static OffsetDateTime toOffset(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    /** @return 库内形态的时间；入参为 null 时返回 null */
    public static LocalDateTime toLocal(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }
}
