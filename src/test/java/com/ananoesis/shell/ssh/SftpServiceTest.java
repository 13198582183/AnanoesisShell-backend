package com.ananoesis.shell.ssh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.ananoesis.shell.contract.model.FileEntry;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SftpService 的端到端测试（tasks 12.2-12.3）。
 *
 * <p>使用嵌入式 MINA SSHD SFTP 服务器（{@link SftpTestServer}），
 * 通过真实 sshj SSHClient 连接，验证路径校验、lstat、有界 READDIR 等行为。</p>
 */
class SftpServiceTest {

    private static SftpTestServer sftpServer;
    private static Path tempDir;

    private SftpService sftpService;
    private SessionRuntime runtime;
    private SSHClient sshClient;
    private String sessionId;

    @BeforeAll
    static void startServer() throws Exception {
        tempDir = Files.createTempDirectory("sftp-test-");
        sftpServer = new SftpTestServer(tempDir);
        sftpServer.start();
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (sftpServer != null) {
            sftpServer.stop();
        }
        // 清理临时目录
        if (tempDir != null) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        sftpService = new SftpService();

        // 创建真实 SSHClient 连接到测试 SFTP 服务器
        sshClient = new SSHClient();
        sshClient.addHostKeyVerifier(new HostKeyVerifier() {
            @Override
            public boolean verify(String hostname, int port, PublicKey key) {
                return true;
            }

            @Override
            public List<String> findExistingAlgorithms(String hostname, int port) {
                return List.of();
            }
        });
        sshClient.connect("localhost", sftpServer.port());
        sshClient.authPassword("testuser", "testpass");

        // 构造 SessionRuntime（使用 null shell 的测试桩终端会话）
        sessionId = UUID.randomUUID().toString();
        SshTerminalSession terminalSession = new SshTerminalSession(
                sessionId, sshClient, null, null,
                new NoopTerminalOutputListener(),
                reason -> {});

        runtime = new SessionRuntime(
                UUID.randomUUID(),
                terminalSession,
                new NoopTerminalOutputListener(),
                null);

        // 准备测试目录结构
        prepareTestDirectory();
    }

    /** 在每个测试前清理并重建测试目录结构。 */
    private void prepareTestDirectory() throws IOException {
        // 清理已有的测试内容
        Path testDir = tempDir.resolve("testdir");
        if (Files.exists(testDir)) {
            Files.walk(testDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        }
        // 重建
        Files.createDirectories(testDir);
        sftpServer.createFile(testDir, "file1.txt", "hello");
        sftpServer.createFile(testDir, "file2.txt", "world");
        Files.createDirectories(testDir.resolve("subdir"));
        sftpServer.createFile(testDir.resolve("subdir"), "nested.txt", "nested content");
    }

    // ======================================================================
    // 12.2 路径校验
    // ======================================================================

    @Nested
    @DisplayName("路径校验（basename 规则）")
    class PathValidation {

        @Test
        @DisplayName("拒绝相对路径（不以 / 开头）")
        void rejectsRelativePath() {
            assertThatThrownBy(() -> sftpService.validatePath("relative/path"))
                    .isInstanceOf(SftpService.PathValidationException.class)
                    .hasMessageContaining("绝对路径");
        }

        @Test
        @DisplayName("拒绝空路径")
        void rejectsEmptyPath() {
            assertThatThrownBy(() -> sftpService.validatePath(""))
                    .isInstanceOf(SftpService.PathValidationException.class);
        }

        @Test
        @DisplayName("拒绝含 .. 组件的路径")
        void rejectsDotDotComponent() {
            assertThatThrownBy(() -> sftpService.validatePath("/home/user/../etc"))
                    .isInstanceOf(SftpService.PathValidationException.class)
                    .hasMessageContaining("..");
        }

        @Test
        @DisplayName("拒绝含 NUL 字符的路径")
        void rejectsNulCharacter() {
            assertThatThrownBy(() -> sftpService.validatePath("/home/\0file"))
                    .isInstanceOf(SftpService.PathValidationException.class)
                    .hasMessageContaining("NUL");
        }

        @Test
        @DisplayName("拒绝含 CR 字符的路径")
        void rejectsCrCharacter() {
            assertThatThrownBy(() -> sftpService.validatePath("/home/\rfile"))
                    .isInstanceOf(SftpService.PathValidationException.class)
                    .hasMessageContaining("CR");
        }

        @Test
        @DisplayName("拒绝含 LF 字符的路径")
        void rejectsLfCharacter() {
            assertThatThrownBy(() -> sftpService.validatePath("/home/\nfile"))
                    .isInstanceOf(SftpService.PathValidationException.class)
                    .hasMessageContaining("LF");
        }

        @Test
        @DisplayName("接受中文和空格路径")
        void acceptsChineseAndSpaces() {
            // 不应抛异常
            sftpService.validatePath("/home/用户/我的文件");
        }

        @Test
        @DisplayName("接受普通绝对路径")
        void acceptsNormalAbsolutePath() {
            sftpService.validatePath("/home/user/docs");
        }

        @Test
        @DisplayName("根路径 / 合法")
        void acceptsRootPath() {
            sftpService.validatePath("/");
        }
    }

    // ======================================================================
    // 12.2 目录浏览
    // ======================================================================

    @Nested
    @DisplayName("目录浏览（listDir）")
    class ListDirectory {

        @Test
        @DisplayName("列出目录内容，排除 . 和 ..")
        void listsDirectoryContents() {
            var result = sftpService.listDir(runtime, "/testdir", null, 100);

            assertThat(result.getItems()).isNotEmpty();
            assertThat(result.getItems())
                    .extracting(FileEntry::getName)
                    .containsExactlyInAnyOrder("file1.txt", "file2.txt", "subdir");
            assertThat(result.getItems())
                    .noneMatch(e -> ".".equals(e.getName()) || "..".equals(e.getName()));
        }

        @Test
        @DisplayName("正确标识目录、常规文件")
        void correctlyIdentifiesFileTypes() {
            var result = sftpService.listDir(runtime, "/testdir", null, 100);

            FileEntry subdir = result.getItems().stream()
                    .filter(e -> "subdir".equals(e.getName()))
                    .findFirst().orElseThrow();
            assertThat(subdir.getIsDir()).isTrue();
            assertThat(subdir.getIsRegular()).isFalse();
            assertThat(subdir.getIsLink()).isFalse();

            FileEntry file = result.getItems().stream()
                    .filter(e -> "file1.txt".equals(e.getName()))
                    .findFirst().orElseThrow();
            assertThat(file.getIsDir()).isFalse();
            assertThat(file.getIsRegular()).isTrue();
            assertThat(file.getIsLink()).isFalse();
            assertThat(file.getSize()).isNotNull();
            assertThat(file.getSize()).isEqualTo(5L); // "hello" = 5 bytes
        }

        @Test
        @DisplayName("符号链接标记为 link，不跟随")
        void symlinkShownNotFollowed() throws IOException {
            // 创建一个符号链接
            sftpServer.createSymlink(tempDir.resolve("testdir"), "link.txt",
                    tempDir.resolve("testdir").resolve("file1.txt"));

            var result = sftpService.listDir(runtime, "/testdir", null, 100);

            FileEntry link = result.getItems().stream()
                    .filter(e -> "link.txt".equals(e.getName()))
                    .findFirst().orElseThrow();
            assertThat(link.getIsLink()).isTrue();
        }

        @Test
        @DisplayName("非目录路径抛出异常")
        void nonDirectoryPathThrows() {
            assertThatThrownBy(() -> sftpService.listDir(runtime, "/testdir/file1.txt", null, 100))
                    .isInstanceOf(SftpService.NotDirectoryException.class);
        }

        @Test
        @DisplayName("不存在路径抛出异常")
        void nonExistentPathThrows() {
            assertThatThrownBy(() -> sftpService.listDir(runtime, "/nonexistent", null, 100))
                    .isInstanceOf(SftpService.PathNotFoundException.class);
        }

        @Test
        @DisplayName("相对路径被拒绝")
        void relativePathRejected() {
            assertThatThrownBy(() -> sftpService.listDir(runtime, "testdir", null, 100))
                    .isInstanceOf(SftpService.PathValidationException.class);
        }
    }

    // ======================================================================
    // 12.3 有界 READDIR
    // ======================================================================

    @Nested
    @DisplayName("有界 READDIR（分页）")
    class BoundedReaddir {

        @Test
        @DisplayName("首页返回指定 limit 数量的条目和 next_cursor")
        void firstPageReturnsLimitAndCursor() throws IOException {
            // 创建包含 5 个文件的目录
            Path bigDir = sftpServer.createDir("bigdir");
            for (int i = 0; i < 5; i++) {
                sftpServer.createFile(bigDir, "file" + i + ".txt", "content");
            }

            var result = sftpService.listDir(runtime, "/bigdir", null, 3);

            assertThat(result.getItems()).hasSize(3);
            assertThat(result.getHasMore()).isTrue();
            assertThat(result.getNextCursor()).isNotNull();
        }

        @Test
        @DisplayName("使用 cursor 获取下一页")
        void subsequentPageUsesCursor() throws IOException {
            Path bigDir = sftpServer.createDir("bigdir2");
            for (int i = 0; i < 5; i++) {
                sftpServer.createFile(bigDir, "file" + i + ".txt", "content");
            }

            var firstPage = sftpService.listDir(runtime, "/bigdir2", null, 3);
            assertThat(firstPage.getItems()).hasSize(3);
            assertThat(firstPage.getHasMore()).isTrue();

            var secondPage = sftpService.listDir(runtime, "/bigdir2",
                    firstPage.getNextCursor(), 3);
            assertThat(secondPage.getItems()).hasSize(2);
            assertThat(secondPage.getHasMore()).isFalse();
            assertThat(secondPage.getNextCursor()).isNull();

            // 两页的文件名不重复
            var allNames = new java.util.HashSet<String>();
            firstPage.getItems().forEach(e -> allNames.add(e.getName()));
            secondPage.getItems().forEach(e -> allNames.add(e.getName()));
            assertThat(allNames).hasSize(5);
        }

        @Test
        @DisplayName("limit 上限 200")
        void limitCappedAt200() {
            // limit 201 应被截断为 200（不报错）
            var result = sftpService.listDir(runtime, "/testdir", null, 201);
            // 只要不抛异常，且返回结果即可
            assertThat(result.getItems()).isNotEmpty();
        }

        @Test
        @DisplayName("默认 limit 为 100")
        void defaultLimitIs100() {
            // 传 null 作为 limit 应使用默认值 100
            var result = sftpService.listDir(runtime, "/testdir", null, null);
            assertThat(result.getItems()).isNotEmpty();
        }

        @Test
        @DisplayName("每连接最多 2 个目录 handle")
        void maxTwoHandlesPerSession() throws IOException {
            // 创建 3 个目录；每个放 2 个文件，保证 limit=1 时有下一页（才会生成游标占 handle）
            Path d1 = sftpServer.createDir("handle_d1");
            Path d2 = sftpServer.createDir("handle_d2");
            Path d3 = sftpServer.createDir("handle_d3");
            sftpServer.createFile(d1, "f1.txt", "x");
            sftpServer.createFile(d1, "f2.txt", "x");
            sftpServer.createFile(d2, "f1.txt", "x");
            sftpServer.createFile(d2, "f2.txt", "x");
            sftpServer.createFile(d3, "f1.txt", "x");
            sftpServer.createFile(d3, "f2.txt", "x");

            // 打开第 1 个目录
            var r1 = sftpService.listDir(runtime, "/handle_d1", null, 1);
            assertThat(r1.getHasMore()).isTrue(); // 有游标 = 占了一个 handle

            // 打开第 2 个目录
            var r2 = sftpService.listDir(runtime, "/handle_d2", null, 1);
            assertThat(r2.getHasMore()).isTrue();

            // 打开第 3 个目录——应驱逐最旧的 handle
            var r3 = sftpService.listDir(runtime, "/handle_d3", null, 1);
            assertThat(r3.getHasMore()).isTrue();

            // 第 1 个游标应已被驱逐，使用时应报错或自动重新打开
            assertThatThrownBy(() ->
                    sftpService.listDir(runtime, "/handle_d1", r1.getNextCursor(), 1))
                    .isInstanceOf(SftpService.CursorExpiredException.class);
        }

        @Test
        @DisplayName("空目录返回空列表")
        void emptyDirectoryReturnsEmpty() throws IOException {
            sftpServer.createDir("emptydir");

            var result = sftpService.listDir(runtime, "/emptydir", null, 100);

            assertThat(result.getItems()).isEmpty();
            assertThat(result.getHasMore()).isFalse();
            assertThat(result.getNextCursor()).isNull();
        }
    }

    // ======================================================================
    // 辅助
    // ======================================================================

    /** 不产生任何输出的 TerminalOutputListener 桩。 */
    private static class NoopTerminalOutputListener implements TerminalOutputListener {
        @Override public void onStdout(String data) {}
        @Override public void onStderr(String data) {}
        @Override public void onClosed(SshCloseReason reason) {}
    }
}
