package com.ananoesis.shell.ssh;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.List;

import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

/**
 * 测试用嵌入式 SFTP 服务器（task 12.1）。
 *
 * <p>基于 Apache MINA SSHD，在临时目录上启动一个真实的 SSH+SFTP 服务器，
 * 供 {@link SftpServiceTest} 进行端到端验证。接受任意用户名/密码。</p>
 *
 * <p>WHY 使用真实服务器而非 mock：SftpService 需要与真实的 SFTP 协议交互，
 * mock 无法验证路径解析、属性读取等协议级行为。</p>
 */
public class SftpTestServer {

    private final Path tempDir;
    private SshServer server;
    private int port;

    public SftpTestServer(Path tempDir) {
        this.tempDir = tempDir;
    }

    /** 启动 SFTP 服务器。 */
    public void start() throws IOException {
        server = SshServer.setUpDefaultServer();
        server.setPort(0); // 随机端口

        // 生成临时主机密钥
        KeyPair hostKey = generateHostKey();
        server.setKeyPairProvider(session -> List.of(hostKey));

        // 接受任意密码（测试用）
        server.setPasswordAuthenticator((username, password, session) -> true);

        // 设置 SFTP 子系统
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));

        // 设置虚拟文件系统根目录
        server.setFileSystemFactory(new VirtualFileSystemFactory(tempDir));

        server.start();
        port = server.getPort();
    }

    /** 停止服务器。 */
    public void stop() throws IOException {
        if (server != null) {
            server.stop();
        }
    }

    public int port() {
        return port;
    }

    public Path rootDir() {
        return tempDir;
    }

    public Path createDir(String name) throws IOException {
        Path dir = tempDir.resolve(name);
        Files.createDirectories(dir);
        return dir;
    }

    public Path createFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    public Path createFile(Path directory, String name, String content) throws IOException {
        Path file = directory.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    public Path createSymlink(String linkName, Path target) throws IOException {
        Path link = tempDir.resolve(linkName);
        Files.createSymbolicLink(link, target);
        return link;
    }

    public Path createSymlink(Path directory, String linkName, Path target) throws IOException {
        Path link = directory.resolve(linkName);
        Files.createSymbolicLink(link, target);
        return link;
    }

    public Path createFileWithSize(String name, int sizeBytes) throws IOException {
        Path file = tempDir.resolve(name);
        try (OutputStream os = Files.newOutputStream(file)) {
            byte[] buf = new byte[Math.min(sizeBytes, 8192)];
            java.util.Arrays.fill(buf, (byte) 'A');
            int written = 0;
            while (written < sizeBytes) {
                int toWrite = Math.min(buf.length, sizeBytes - written);
                os.write(buf, 0, toWrite);
                written += toWrite;
            }
        }
        return file;
    }

    public List<String> listDir(Path dir) throws IOException {
        List<String> names = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.forEach(p -> names.add(p.getFileName().toString()));
        }
        return names;
    }

    private static KeyPair generateHostKey() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(256);
            return kpg.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("无法生成测试主机密钥", e);
        }
    }
}
