package com.ananoesis.shell.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本地存储配置：用户数据目录、SQLite 文件名与连接级 PRAGMA 参数（design.md D6）。
 *
 * <p>WHY 把 PRAGMA 做成 JDBC URL 参数而不是启动后执行一次 {@code PRAGMA}：
 * {@code journal_mode=WAL} 是库级且持久化的，但 {@code foreign_keys} 与 {@code busy_timeout}
 * 是**连接级**属性；连接池场景下必须对每一条连接都生效，只有 URL 参数能保证这点。</p>
 *
 * <p>WHY 默认目录是 {@code ${user.home}/.ananoesis}：桌面单机免安装，且放在用户主目录
 * 可避开 Windows Program Files 的写权限限制，也便于用户整体备份/迁移数据。</p>
 */
@ConfigurationProperties(prefix = "ananoesis.storage")
public class StorageProperties {

    /** 用户数据目录；可被 {@code -Dananoesis.storage.data-dir=...}、环境变量或测试覆盖。 */
    private Path dataDir = Paths.get(System.getProperty("user.home"), ".ananoesis");

    /** SQLite 数据库文件名。 */
    private String databaseFileName = "data.db";

    /**
     * 追加到 JDBC URL 的 PRAGMA 参数串（不含前导 {@code ?}）。
     * 默认值即 design.md D6 要求的 WAL 模式，外加外键约束与写冲突自旋等待。
     */
    private String jdbcParameters = "journal_mode=WAL&foreign_keys=on&busy_timeout=10000&synchronous=NORMAL";

    /** @return 规范化后的数据目录绝对路径 */
    public Path resolvedDataDir() {
        return dataDir.toAbsolutePath().normalize();
    }

    /** @return 规范化后的 SQLite 数据库文件绝对路径 */
    public Path resolvedDatabaseFile() {
        return resolvedDataDir().resolve(databaseFileName);
    }

    /**
     * 构造 sqlite-jdbc 连接串。
     *
     * @return 形如 {@code jdbc:sqlite:<绝对路径>?journal_mode=WAL&...}
     */
    public String jdbcUrl() {
        String suffix = (jdbcParameters == null || jdbcParameters.isBlank()) ? "" : "?" + jdbcParameters;
        return "jdbc:sqlite:" + resolvedDatabaseFile() + suffix;
    }

    /**
     * 确保数据目录存在。
     *
     * <p>WHY 必须显式创建：sqlite-jdbc 只会创建数据库文件本身，**不会**创建父目录；
     * 目录缺失时连接会以晦涩的 {@code SQLITE_CANTOPEN} 失败。</p>
     *
     * @throws UncheckedIOException 目录不可创建（磁盘只读 / 权限不足 / 路径被文件占用）
     */
    public void ensureDataDirExists() {
        Path dir = resolvedDataDir();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            // 数据目录不可写是致命前置条件缺失：必须显式失败，
            // 禁止静默改用其他路径——否则用户数据会分散到多处且无从排查
            throw new UncheckedIOException("无法创建用户数据目录: " + dir, e);
        }
    }

    public Path getDataDir() {
        return dataDir;
    }

    public void setDataDir(Path dataDir) {
        this.dataDir = dataDir;
    }

    public String getDatabaseFileName() {
        return databaseFileName;
    }

    public void setDatabaseFileName(String databaseFileName) {
        this.databaseFileName = databaseFileName;
    }

    public String getJdbcParameters() {
        return jdbcParameters;
    }

    public void setJdbcParameters(String jdbcParameters) {
        this.jdbcParameters = jdbcParameters;
    }
}
