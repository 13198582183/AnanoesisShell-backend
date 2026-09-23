package com.ananoesis.shell.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.AlgorithmParameters;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;

import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;

/**
 * 内嵌 SSH 服务器（Apache MINA SSHD），tasks 6.x 全部测试的**唯一**远端替身。
 *
 * <p>WHY 自建替身而不用真实 Linux 主机或 Testcontainers：
 * 指挥官明确要求"SSH 集成测试不依赖真实 Linux 主机"。真实主机会让测试行为随那台机器的
 * sshd 配置漂移（是否允许密码登录、默认 shell 是什么、有没有 bash），
 * 测试就从"验证我们的连接逻辑"退化成"验证环境"；Docker 方案又把测试与 Docker Desktop
 * 的可用性绑死，在无 Docker 的 Windows 开发机上直接红。</p>
 *
 * <p>WHY 自己写 shell 而不用 MINA 自带的 {@code ProcessShellFactory}：
 * 后者会 exec 本机的 {@code cmd.exe}/{@code /bin/sh}，输出不可预测，且 Windows 上没有 PTY。
 * 本替身实现一个**行为确定**的迷你解释器，使断言可以精确到字节。</p>
 *
 * <p>WHY 迷你解释器而不是"原样回显输入"：tasks 6.5 要求验证 stdout/stderr/exit code
 * 三者**分离**，6.3 要求验证 PTY 分配与控制字节（Ctrl-C）转发，6.6 要求验证远端关闭。
 * 只有让远端行为可编程，这些断言才有意义。</p>
 *
 * <p>WHY 读入循环与前台命令分处两个线程：真实终端里，命令在前台运行时内核的线路规程
 * 仍在接收按键，Ctrl-C 才可能打断它。若把命令放在读循环里同步执行，
 * {@code wait} 这类命令一跑起来就再也读不到输入，中断信号测试会变成"必然超时"的假测试。</p>
 *
 * <p>服务端观察到的事实（收到的 exec 命令、shell 输入、是否分配 PTY）都会被记录，
 * 供测试断言"我们确实按契约发出了请求"，而不只是断言"我们收到了想要的输出"。</p>
 */
public final class FakeSshServer implements AutoCloseable {

    /** 唯一合法用户名；其它用户名一律认证失败。 */
    public static final String USERNAME = "ops";
    /** 唯一合法密码。 */
    public static final String PASSWORD = "F4ke-P@ssw0rd-MINA-sshd";
    /** 私钥口令（用于生成加密 PKCS#8 私钥夹具）。 */
    public static final String PASSPHRASE = "F4ke-P@ssphrase-MINA-sshd";

    /**
     * PBES2 + AES-256-CBC：与 {@code openssl pkcs8 -topk8 -v2aes256} 的产物同构。
     *
     * <p>WHY 更正原注释里"sshj 经 BouncyCastle 可解"的说法：字节码取证显示
     * {@code PKCS8KeyFile.getPkcs8DecryptedKeySpec} 走的是**纯 JDK** 路径
     * （{@code javax.crypto.EncryptedPrivateKeyInfo} + {@code SecretKeyFactory}），
     * 并未调用 BouncyCastle。BC 只被 {@code PEMDecryptor}（传统 PEM 的 DEK-Info 头）反射使用。</p>
     */
    private static final String PBE_ALGORITHM = "PBEWithHmacSHA256AndAES_256";

    /** 迭代次数。夹具只需"能解开"，取 openssl 常用量级即可，无需抗暴破强度。 */
    private static final int PBE_ITERATIONS = 10_000;

    /**
     * PBES2 的 OID（1.2.840.113549.1.5.13），已含 DER 的 tag 与长度字节。
     * WHY 硬编码而不用 {@code sun.security.x509.AlgorithmId}：那是 JDK 内部 API，
     * 且它正是本次要绕开的东西——它不认 JCE 别名（见 {@link #encryptedPrivateKeyPem}）。
     */
    private static final byte[] OID_PBES2 = {
            0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x05, 0x0d};

    private final SshServer server;
    private final KeyPair userKeyPair;
    private final List<String> execCommands = new CopyOnWriteArrayList<>();
    private final List<String> shellInputs = new CopyOnWriteArrayList<>();
    private final List<Boolean> ptyAllocations = new CopyOnWriteArrayList<>();
    private final List<String> observedSignals = new CopyOnWriteArrayList<>();

    /**
     * 交互式 shell 建立时立即输出的登录 banner（null/空 = 不输出，既有行为）。
     *
     * <p>WHY 可注入：真实 bash 登录后会**不等任何输入**立刻打印 banner + 首个 prompt，
     * 而迷你解释器原本只在收到输入后才出字节，测不出「闸门接入前登录 prompt 泄漏」
     * 的首连双提示符竞态（known-issues #19）。仅新用例显式设置，用完复原。</p>
     */
    private volatile String loginBanner;

    private FakeSshServer(SshServer server, KeyPair userKeyPair) {
        this.server = server;
        this.userKeyPair = userKeyPair;
    }

    /**
     * 启动一个监听 127.0.0.1 随机端口的替身服务器。
     *
     * @throws IOException 端口绑定或主机密钥生成失败
     */
    public static FakeSshServer start() throws IOException {
        KeyPair keyPair = generateRsaKeyPair();
        SshServer sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        // 端口 0 = 交给 OS 分配，避免与并行测试或本机真实 sshd 冲突
        sshd.setPort(0);
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKeyFile()));
        sshd.setPasswordAuthenticator((user, password, session) ->
                USERNAME.equals(user) && PASSWORD.equals(password));
        sshd.setPublickeyAuthenticator((user, key, session) ->
                USERNAME.equals(user) && sameRsaKey(keyPair.getPublic(), key));

        FakeSshServer fake = new FakeSshServer(sshd, keyPair);
        sshd.setCommandFactory((channel, command) -> {
            fake.execCommands.add(command);
            return new MiniShell(command, false, fake);
        });
        sshd.setShellFactory(channel -> {
            fake.ptyAllocations.add(hasPty(channel));
            return new MiniShell(null, true, fake);
        });
        sshd.start();
        return fake;
    }

    /** 实际监听端口（构造时未知，必须启动后读取）。 */
    public int port() {
        return server.getPort();
    }

    public String username() {
        return USERNAME;
    }

    public String password() {
        return PASSWORD;
    }

    public PublicKey publicKey() {
        return userKeyPair.getPublic();
    }

    /**
     * @return 未加密的 PKCS#8 PEM 私钥（{@code -----BEGIN PRIVATE KEY-----}）。
     *         WHY PKCS#8 而非 OpenSSH v1：生成只依赖 JDK，不必把 BouncyCastle
     *         拉进测试编译期；sshj 的 {@code PKCS8KeyFile} 直接可解析。
     */
    public String privateKeyPem() {
        return toPem("PRIVATE KEY", userKeyPair.getPrivate().getEncoded());
    }

    /**
     * @return 以 {@code passphrase} 加密的 PKCS#8 PEM（{@code -----BEGIN ENCRYPTED PRIVATE KEY-----}）。
     *
     * <p>WHY 需要它：tasks 6.1 明确要求覆盖"私钥（含 passphrase）认证"。
     * 这条路径的风险全在**客户端解密**（服务端只看公钥），
     * 没有加密夹具就永远测不到 passphrase 分支。</p>
     *
     * <p>WHY 手工拼 DER 而不用 {@code new EncryptedPrivateKeyInfo(AlgorithmParameters, byte[])}：
     * 该构造器内部走 {@code sun.security.x509.AlgorithmId.get(name)}，而它只认 OID 与
     * 已登记的算法名，**不认 JCE 别名**——实测对 {@code PBEWithHmacSHA256AndAES_256}
     * 抛 {@code NoSuchAlgorithmException: unrecognized algorithm name}。
     * 换句话说：JDK 能加密 PBES2，却无法用它自己的便捷构造器把结果包成 PKCS#8。
     * 手工拼一个 {@code SEQUENCE{AlgorithmIdentifier{PBES2, params}, OCTET STRING}}
     * 只需十几行，且与 {@code openssl pkcs8 -topk8 -v2aes256} 的产物同构，
     * 比为这一件事引入 BouncyCastle 测试依赖更划算（BC 在 sshj 里只是 runtime 作用域，
     * 测试编译期看不见）。</p>
     *
     * <p>WHY 结尾要自校验：这个夹具一旦与 sshj 的解密路径不兼容，表现出来就是
     * "私钥认证失败"，与真正的产品缺陷长得一模一样，排查成本极高。
     * 所以这里**当场重放 sshj 的算法**（{@code PKCS8KeyFile.getPkcs8DecryptedKeySpec} 的字节码显示：
     * 取 {@code getAlgParameters().toString()} 当 JCE 算法名 → {@code SecretKeyFactory}
     * → {@code Cipher.init(DECRYPT, key, algParams)} → {@code getKeySpec(cipher)}），
     * 解不开就让夹具自己立刻炸，而不是把误导留到断言阶段。</p>
     */
    public String encryptedPrivateKeyPem(String passphrase) {
        try {
            byte[] pkcs8 = userKeyPair.getPrivate().getEncoded();
            byte[] encryptedPkcs8 = buildEncryptedPkcs8(pkcs8, passphrase);
            verifyDecryptableLikeSshj(encryptedPkcs8, pkcs8, passphrase);
            return toPem("ENCRYPTED PRIVATE KEY", encryptedPkcs8);
        } catch (Exception e) {
            throw new IllegalStateException("无法生成加密私钥夹具", e);
        }
    }

    /** 加密 PKCS#8 并包成 {@code EncryptedPrivateKeyInfo} 的 DER。 */
    private static byte[] buildEncryptedPkcs8(byte[] pkcs8, String passphrase) throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[16];
        byte[] iv = new byte[16];
        random.nextBytes(salt);
        random.nextBytes(iv);

        PBEParameterSpec parameters = new PBEParameterSpec(salt, PBE_ITERATIONS, new IvParameterSpec(iv));
        SecretKey secret = SecretKeyFactory.getInstance(PBE_ALGORITHM)
                .generateSecret(new PBEKeySpec(passphrase.toCharArray(), salt, PBE_ITERATIONS));
        Cipher cipher = Cipher.getInstance(PBE_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, secret, parameters);
        byte[] encrypted = cipher.doFinal(pkcs8);

        AlgorithmParameters algorithmParameters = AlgorithmParameters.getInstance(PBE_ALGORITHM);
        algorithmParameters.init(parameters);
        // getEncoded("ASN.1") 给出的正是 PBES2-params SEQUENCE（KDF + 加密方案），可直接内嵌
        byte[] pbes2Parameters = algorithmParameters.getEncoded("ASN.1");

        byte[] algorithmIdentifier = derSequence(concat(OID_PBES2, pbes2Parameters));
        return derSequence(concat(algorithmIdentifier, derOctetString(encrypted)));
    }

    /**
     * 用 sshj 的解密算法当场重放一遍，确认夹具真的可被生产路径消费。
     * WHY 逐行对齐 {@code PKCS8KeyFile.getPkcs8DecryptedKeySpec}：见
     * {@link #encryptedPrivateKeyPem(String)} 的说明。
     */
    private static void verifyDecryptableLikeSshj(byte[] encryptedPkcs8, byte[] expectedPkcs8,
            String passphrase) throws Exception {
        EncryptedPrivateKeyInfo info = new EncryptedPrivateKeyInfo(encryptedPkcs8);
        AlgorithmParameters parameters = info.getAlgParameters();
        // sshj 用 toString() 而不是 getAlgorithm()：对 PBES2 前者返回具体 JCE 名
        // （PBEWithHmacSHA256AndAES_256），后者只返回 "PBES2"——后者是取不到 SecretKeyFactory 的
        String algorithm = parameters.toString();
        SecretKey secret = SecretKeyFactory.getInstance(algorithm)
                .generateSecret(new PBEKeySpec(passphrase.toCharArray()));
        Cipher cipher = Cipher.getInstance(algorithm);
        cipher.init(Cipher.DECRYPT_MODE, secret, parameters);
        byte[] decrypted = info.getKeySpec(cipher).getEncoded();
        if (!Arrays.equals(decrypted, expectedPkcs8)) {
            throw new IllegalStateException("加密私钥夹具自校验失败：解密结果与原始 PKCS#8 不一致");
        }
    }

    private static byte[] derSequence(byte[] content) {
        return concat(new byte[] {0x30}, derLength(content.length), content);
    }

    private static byte[] derOctetString(byte[] content) {
        return concat(new byte[] {0x04}, derLength(content.length), content);
    }

    /** DER 长度字段：小于 128 用短格式，否则 {@code 0x80|字节数} + 大端长度。 */
    private static byte[] derLength(int length) {
        if (length < 128) {
            return new byte[] {(byte) length};
        }
        byte[] littleEndian = new byte[4];
        int significant = 0;
        for (int shifted = length; shifted > 0; shifted >>>= 8) {
            littleEndian[significant++] = (byte) (shifted & 0xff);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x80 | significant);
        for (int i = significant - 1; i >= 0; i--) {
            out.write(littleEndian[i]);
        }
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }

    /** 服务端按到达顺序收到的 exec 命令。 */
    public List<String> execCommands() {
        return List.copyOf(execCommands);
    }

    /** 交互式 shell 收到的输入（每条为一次字节级读取的原文）。 */
    public List<String> shellInputs() {
        return List.copyOf(shellInputs);
    }

    /** 每次 shell 通道建立时服务端是否看到了 PTY 分配请求。 */
    public List<Boolean> ptyAllocations() {
        return List.copyOf(ptyAllocations);
    }

    /** 设置登录 banner（null 恢复不输出）；对设置后新建的交互式 shell 生效。 */
    public void setLoginBanner(String banner) {
        this.loginBanner = banner;
    }

    String loginBanner() {
        return loginBanner;
    }

    /**
     * 粗暴断开：直接停掉整个服务器。
     * WHY 需要它：spec「远端关闭连接」要求我们在远端主动断开时检测到 EOF、
     * 上报 remote_closed 并释放资源。{@code hangup} 命令是优雅关闭，
     * 本方法是"拔网线"级别的关闭，两者都必须正确处理。
     */
    public void stopAbruptly() throws IOException {
        server.stop(true);
    }

    @Override
    public void close() {
        try {
            server.stop(true);
        } catch (IOException e) {
            // 清理阶段的失败不应掩盖被测断言的失败
            System.err.println("FakeSshServer 停止失败: " + e.getMessage());
        }
    }

    // ==================================================================
    // 迷你解释器
    // ==================================================================

    /**
     * 行为确定的迷你 shell / 命令执行器。支持：
     * <ul>
     *   <li>{@code echo <文本>} → stdout；{@code echoerr <文本>} → stderr（验证双流分离）</li>
     *   <li>{@code exit [n]} → 以 n 结束（交互式下结束 shell）</li>
     *   <li>{@code fail <文本>} → exit 3 + stderr</li>
     *   <li>{@code sleep <毫秒>} / {@code wait}（阻塞到被中断）→ 验证超时与 Ctrl-C</li>
     *   <li>{@code big <字节数>} → 验证输出上限截断</li>
     *   <li>{@code whoami} / {@code tty}（pty|notty）/ {@code cols} → 验证 PTY 参数真的送达</li>
     *   <li>{@code hangup} → 远端主动关闭通道</li>
     *   <li>未知命令 → stderr {@code command not found} + 127，shell 继续存活（与真实 shell 一致）</li>
     * </ul>
     */
    private static final class MiniShell implements Command {

        /** 交互式下 {@link #execute} 返回此值表示 shell 应结束。 */
        private static final int EXIT_NOW = Integer.MIN_VALUE;

        private final String execCommand;
        private final boolean interactive;
        private final FakeSshServer owner;

        private InputStream in;
        private OutputStream out;
        private OutputStream err;
        private ExitCallback exitCallback;
        private ChannelSession channel;

        private volatile Thread worker;
        /** 当前前台命令线程；Ctrl-C 靠中断它来实现（对应真实 shell 的 SIGINT）。 */
        private volatile Thread foreground;
        private volatile boolean destroyed;
        private volatile boolean finished;

        MiniShell(String execCommand, boolean interactive, FakeSshServer owner) {
            this.execCommand = execCommand;
            this.interactive = interactive;
            this.owner = owner;
        }

        @Override
        public void setInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public void setOutputStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void setErrorStream(OutputStream err) {
            this.err = err;
        }

        @Override
        public void setExitCallback(ExitCallback callback) {
            this.exitCallback = callback;
        }

        @Override
        public void start(ChannelSession channel, Environment env) {
            this.channel = channel;
            worker = new Thread(() -> run(env), "fake-sshd-" + (interactive ? "shell" : "exec"));
            worker.setDaemon(true);
            worker.start();
        }

        @Override
        public void destroy(ChannelSession channel) {
            destroyed = true;
            interruptQuietly(foreground);
            interruptQuietly(worker);
        }

        private void run(Environment env) {
            try {
                if (!interactive) {
                    finish(env, execute(execCommand, env));
                    return;
                }
                readLoop(env);
            } catch (IOException e) {
                if (!destroyed) {
                    finishQuietly(1);
                }
            } catch (RuntimeException e) {
                finishQuietly(1);
            }
        }

        /**
         * 交互式主循环：只做"读字节 + 回显 + 分派"，命令交给前台线程执行，
         * 以便命令运行期间仍能收到 Ctrl-C。
         */
        private void readLoop(Environment env) throws IOException {
            // 模拟真实 bash：登录后不等输入立即打印 banner/首个 prompt，
            // 用于验证预安装静音必须从会话第一个字节起覆盖（known-issues #19）
            String banner = owner.loginBanner();
            if (banner != null && !banner.isEmpty()) {
                writeOut(banner);
            }
            StringBuilder line = new StringBuilder();
            int read;
            while (!destroyed && !finished && (read = in.read()) != -1) {
                char c = (char) read;
                owner.shellInputs.add(String.valueOf(c));
                if (c == '\r' || c == '\n') {
                    writeOut("\r\n");
                    String command = line.toString();
                    line.setLength(0);
                    if (command.isBlank() || foreground != null) {
                        // 前台命令运行期间的换行被忽略：等价于真实 shell 的同步前台语义
                        continue;
                    }
                    startForeground(command, env);
                    continue;
                }
                if (c == '\u0003') {
                    writeOut("^C\r\n");
                    line.setLength(0);
                    owner.observedSignals.add("INT");
                    interruptQuietly(foreground);
                    continue;
                }
                if (c == '\u0004') {
                    // Ctrl-D = EOF，退出 shell
                    break;
                }
                if (c == '\u007f' || c == '\b') {
                    if (!line.isEmpty()) {
                        line.deleteCharAt(line.length() - 1);
                    }
                    continue;
                }
                line.append(c);
                out.write(new byte[] {(byte) read});
                out.flush();
            }
            finish(env, 0);
        }

        private void startForeground(String command, Environment env) {
            CountDownLatch started = new CountDownLatch(1);
            Thread thread = new Thread(() -> {
                started.countDown();
                int code;
                try {
                    code = execute(command, env);
                } catch (IOException e) {
                    code = 1;
                } catch (RuntimeException e) {
                    code = 1;
                }
                foreground = null;
                if (code == EXIT_NOW) {
                    finishQuietly(0);
                }
            }, "fake-sshd-foreground");
            thread.setDaemon(true);
            foreground = thread;
            thread.start();
            try {
                started.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /** @return 退出码；交互式下 {@link #EXIT_NOW} 表示结束 shell */
        private int execute(String command, Environment env) throws IOException {
            String trimmed = command == null ? "" : command.trim();
            if (trimmed.isEmpty()) {
                return 0;
            }
            String[] parts = trimmed.split("\\s+", 2);
            String name = parts[0];
            String argument = parts.length > 1 ? parts[1].trim() : "";
            switch (name) {
                case "echo":
                    writeOut(argument + "\n");
                    return 0;
                case "echoerr":
                    writeErr(argument + "\n");
                    return 0;
                case "exit":
                    if (interactive) {
                        return EXIT_NOW;
                    }
                    return argument.isEmpty() ? 0 : Integer.parseInt(argument);
                case "fail":
                    writeErr("fake-failure: " + argument + "\n");
                    return 3;
                case "sleep":
                    return sleepFor(Long.parseLong(argument));
                case "wait":
                    // 阻塞直到被中断：验证 Ctrl-C 能真的终止前台命令（spec「中断运行中的命令」）
                    while (!destroyed) {
                        if (sleepFor(50) != 0) {
                            return 130;
                        }
                    }
                    return 0;
                case "big": {
                    int size = Integer.parseInt(argument);
                    byte[] chunk = new byte[Math.min(size, 4096)];
                    Arrays.fill(chunk, (byte) 'A');
                    int written = 0;
                    while (written < size && !destroyed) {
                        int now = Math.min(chunk.length, size - written);
                        out.write(chunk, 0, now);
                        out.flush();
                        written += now;
                    }
                    return 0;
                }
                case "whoami":
                    writeOut(env.getEnv().getOrDefault(Environment.ENV_USER, "?") + "\n");
                    return 0;
                case "tty":
                    writeOut((hasPty(channel) ? "pty" : "notty") + "\n");
                    return 0;
                case "cols":
                    writeOut(env.getEnv().getOrDefault(Environment.ENV_COLUMNS, "0") + "\n");
                    return 0;
                case "hangup":
                    // 模拟远端主动断开：关流 + 关通道，客户端应观察到 EOF
                    finish(env, 0);
                    channel.close(false);
                    return EXIT_NOW;
                default:
                    writeErr("command not found: " + name + "\n");
                    return 127;
            }
        }

        private int sleepFor(long millis) {
            try {
                Thread.sleep(millis);
                return 0;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                try {
                    writeErr("interrupted\n");
                } catch (IOException ignored) {
                    // 通道可能已被客户端关闭，忽略
                }
                return 130;
            }
        }

        private void finish(Environment env, int code) {
            if (finished) {
                return;
            }
            finished = true;
            try {
                out.flush();
                err.flush();
            } catch (IOException ignored) {
                // 客户端可能已断开
            }
            exitCallback.onExit(code == EXIT_NOW ? 0 : code);
        }

        private void finishQuietly(int code) {
            finish(null, code);
        }

        private void writeOut(String text) throws IOException {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        private void writeErr(String text) throws IOException {
            err.write(text.getBytes(StandardCharsets.UTF_8));
            err.flush();
        }
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private static void interruptQuietly(Thread thread) {
        if (thread != null) {
            thread.interrupt();
        }
    }

    /**
     * 判断服务端是否收到了 pty-req。
     *
     * <p>WHY 看 {@code TERM} 而不是 {@code getPtyModes().isEmpty()}：
     * pty-req 报文里的 terminal modes 字段**允许为空**（RFC 4254 §6.2），
     * sshj 的 {@code allocatePTY} 与 {@code allocateDefaultPTY} 默认都传空 map。
     * 用"modes 非空"当 PTY 信号，会把一个**正确**分配了 PTY 的连接误判成没分配，
     * 于是测试红在生产代码无辜的地方——这是替身自己的协议模型错误。</p>
     *
     * <p>依据：MINA {@code ChannelSession.handlePtyReqParsed} 的字节码显示它只做三件事——
     * {@code getPtyModes().putAll(clientModes)}、{@code addEnvVariable("TERM", term)}、
     * {@code addEnvVariable("COLUMNS"/"LINES", ...)}。因此 {@code TERM} 的存在
     * 等价于"收到过 pty-req"；客户端也无法用 env 请求伪造它（sshj 不发 env 请求）。</p>
     */
    private static boolean hasPty(ChannelSession channel) {
        if (channel == null) {
            return false;
        }
        Environment environment = channel.getEnvironment();
        return environment != null && environment.getEnv().containsKey(Environment.ENV_TERM);
    }

    /**
     * WHY 比 modulus/exponent 而不是比 {@code getEncoded()}：
     * MINA 从 ssh-rsa 线上格式解码出的 {@code PublicKey} 实现，其 X.509 编码
     * 与我们本地生成的实例不保证逐字节相同；比对 RSA 参数才是语义等价的判断。
     */
    private static boolean sameRsaKey(PublicKey expected, PublicKey actual) {
        if (!(expected instanceof RSAPublicKey left) || !(actual instanceof RSAPublicKey right)) {
            return actual != null && MessageDigest.isEqual(
                    expected.getEncoded(), actual.getEncoded());
        }
        return left.getModulus().equals(right.getModulus())
                && BigIntegerEquals.of(left.getPublicExponent(), right.getPublicExponent());
    }

    /** 小工具：把两个 BigInteger 的比较写成语义清晰的一行。 */
    private static final class BigIntegerEquals {
        static boolean of(java.math.BigInteger a, java.math.BigInteger b) {
            return a != null && a.equals(b);
        }

        private BigIntegerEquals() {
        }
    }

    private static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            // WHY 2048：过短的 RSA 密钥会被 MINA SSHD 与 sshj 双方拒绝
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 不支持 RSA，无法生成测试密钥", e);
        }
    }

    private static String toPem(String label, byte[] der) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + base64 + "\n-----END " + label + "-----\n";
    }

    /** 主机密钥落盘位置：放在 target 下由 mvn clean 回收，绝不污染用户目录。 */
    private static Path hostKeyFile() throws IOException {
        Path directory = Paths.get("target", "test-data", "fake-sshd").toAbsolutePath().normalize();
        Files.createDirectories(directory);
        return directory.resolve("hostkey-" + System.nanoTime() + ".ser");
    }

    /**
     * 当前活跃 SSH 会话数。
     * WHY 需要它：tasks 6.6 的"释放资源"不能只靠"我们调用了 close()"来断言，
     * 那只是自我证明。让**服务端**报告还剩几条会话，才能真正验证连接被对端确认关闭。
     */
    public int activeSessionCount() {
        return server.getActiveSessions().size();
    }


    /** 供测试断言"服务端确实收到了中断信号"。 */
    public List<String> observedSignals() {
        return List.copyOf(observedSignals);
    }
}
