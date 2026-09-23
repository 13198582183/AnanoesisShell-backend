package com.ananoesis.shell.support;

import java.util.UUID;

/**
 * 实体主键生成。
 *
 * <p>WHY 需要它，而不是直接用 MyBatis-Plus 的 {@code IdType.ASSIGN_UUID}：
 * 后者的 {@code IdentifierGenerator} 产出的是**去掉连字符的 32 位十六进制串**
 * （如 {@code 6f1c...a2}），而 {@code openapi.yaml} 把所有 id 声明为
 * {@code type: string, format: uuid}，openapi-generator 因此把 DTO 字段生成为
 * {@code java.util.UUID}。{@code UUID.fromString("6f1c...a2")} 会直接抛
 * {@code IllegalArgumentException}——症状是"记录能存进去、但一读取就 500"，
 * 排查时极难想到根因在主键生成策略上。</p>
 *
 * <p>因此由服务层在插入前显式赋 canonical UUID（带连字符）。
 * 实体上的 {@code @TableId(type = ASSIGN_UUID)} 保留作为兜底：
 * 它保证"忘了调用本类"时仍能落库（只是形式不同），而不是撞 NOT NULL 约束。</p>
 *
 * <p>WHY 不改 V1 迁移或实体注解：Wave 1 的 7 张表与实体已冻结且测试全绿，
 * 改注解会波及所有实体并让 Wave 1 的 {@code MapperCrudTest} 语义发生变化；
 * 在唯一的 id 生成点收口，改动面最小且意图最清晰。</p>
 */
public final class EntityIds {

    private EntityIds() {
        // 工具类，禁止实例化
    }

    /**
     * @return 新的 canonical 形式 UUID 字符串（8-4-4-4-12，带连字符），
     *         与契约 {@code format: uuid} 及 {@code UUID.fromString} 双向兼容
     */
    public static String newUuid() {
        return UUID.randomUUID().toString();
    }
}
