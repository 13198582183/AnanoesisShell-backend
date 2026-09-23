package com.ananoesis.shell.config;

import javax.sql.DataSource;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.zaxxer.hikari.HikariDataSource;

/**
 * 嵌入式 SQLite 数据源装配（design.md D6）。
 *
 * <p>WHY 自行声明 {@link DataSource} Bean 而不用 Spring Boot 的自动配置：
 * sqlite-jdbc 只会创建库文件、**不会**创建父目录，必须在建立第一条连接之前把用户数据目录建出来；
 * 而自动配置的 DataSource 在容器刷新早期创建，常规 Bean 插不到它前面。
 * 在本方法内先 {@code ensureDataDirExists()} 再构造连接池，是确定性的顺序保证，
 * 也让 Flyway 与 MyBatis-Plus 一定拿到一个已就绪的库。</p>
 *
 * <p>WHY 用 Bean 期绑定而不是 {@code EnvironmentPostProcessor}：
 * EPP 在 {@code prepareEnvironment} 阶段执行，早于 Spring Test 的 ContextCustomizer，
 * 看不到 {@code @DynamicPropertySource} / {@code @SpringBootTest(properties=...)} 注入的值，
 * 会导致测试把库文件写进开发者真实的用户数据目录。Bean 绑定发生在容器刷新期，属性已全部就绪。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
public class SqliteDataSourceConfiguration {

    /** SQLite JDBC 驱动类名；显式指定可在缺依赖时给出可读错误，而非晦涩的 "No suitable driver"。 */
    private static final String SQLITE_DRIVER_CLASS = "org.sqlite.JDBC";

    /**
     * 构造指向用户数据目录下 SQLite 文件的 Hikari 连接池。
     *
     * <p>{@code @ConfigurationProperties} 在工厂方法返回**之后**绑定
     * {@code spring.datasource.hikari.*}，因此方法内设定的 jdbcUrl / driverClassName 是默认值，
     * 连接池尺寸等运维参数仍完全由配置文件驱动，不写死在代码里。</p>
     *
     * @param storage 存储配置（数据目录 / 库文件名 / PRAGMA 参数）
     * @return 已就绪的数据源
     */
    @Bean(destroyMethod = "close")
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    public DataSource dataSource(StorageProperties storage) {
        storage.ensureDataDirExists();

        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(storage.jdbcUrl());
        dataSource.setDriverClassName(SQLITE_DRIVER_CLASS);
        return dataSource;
    }
}
