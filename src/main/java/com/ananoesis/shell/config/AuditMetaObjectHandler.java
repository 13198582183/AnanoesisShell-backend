package com.ananoesis.shell.config;

import java.time.LocalDateTime;

import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;

/**
 * 审计时间戳自动填充：插入时写入 {@code createdAt}/{@code updatedAt}，更新时刷新 {@code updatedAt}。
 *
 * <p>WHY 不用 DDL 的 {@code DEFAULT CURRENT_TIMESTAMP}：
 * SQLite 的 CURRENT_TIMESTAMP 返回 **UTC**，而应用与审计展示使用本地时间；
 * 混用两种时区基准会让"审批发生在几点"这类审计问题失去意义。统一由应用侧生成，
 * 时区语义只有一处定义。</p>
 *
 * <p>WHY 插入时 created 与 updated 共用**同一个** {@link LocalDateTime} 实例：
 * 新建行的"创建即最后修改"是语义事实。若各取一次 {@code now()}，亚秒级差异会让刚插入的
 * 行看起来已被修改过，测试与审计都无法区分"真被改过"与"填充抖动"。</p>
 *
 * <p>注册方式：{@code MybatisPlusAutoConfiguration} 在构建 {@code SqlSessionFactory} 时
 * 通过 {@code applicationContext.getBeanProvider(MetaObjectHandler.class)} 自动拾取本 bean，
 * 无需额外配置（已核对 3.5.16 字节码确认）。</p>
 */
@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {

    private static final String CREATED_AT = "createdAt";
    private static final String UPDATED_AT = "updatedAt";

    /**
     * 插入填充。
     *
     * <p>WHY 用 {@code strictInsertFill}（仅当字段为 null 时填充）：
     * 插入路径上"字段为 null"等价于"调用方没有指定"，此时由框架补时间戳是正确的；
     * 而调用方显式赋值（例如数据导入需保留原始创建时间）必须被尊重，不能被静默覆盖。</p>
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        this.strictInsertFill(metaObject, CREATED_AT, LocalDateTime.class, now);
        this.strictInsertFill(metaObject, UPDATED_AT, LocalDateTime.class, now);
    }

    /**
     * 更新填充。
     *
     * <p>WHY **必须**用 {@code setFieldValByName}（无条件覆盖）而不能用 {@code strictUpdateFill}：
     * strict 系列的语义是"仅当字段为 null 时填充"，而更新路径上最常见的用法恰恰是
     * 先 {@code selectById} 读出实体、改几个字段再 {@code updateById} 回写——
     * 此时 {@code updatedAt} 已带着上次的时间值、并非 null，strict 填充会被**静默跳过**，
     * 结果是 {@code updatedAt} 永远停留在插入时刻，审计时间轴彻底失真且没有任何报错。
     * "最后修改时间"的语义要求它必须反映本次修改，不存在需要保留旧值的正当场景。</p>
     *
     * <p>{@code setFieldValByName} 内部以 {@code metaObject.hasSetter} 为守卫，
     * 因此对没有 {@code updatedAt} 字段的实体（如只追加不修改的 {@code AiMessage}）会自动跳过，
     * 不会抛异常。</p>
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        this.setFieldValByName(UPDATED_AT, LocalDateTime.now(), metaObject);
    }
}
