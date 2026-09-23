package com.ananoesis.shell.security;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.javakeyring.BackendNotSupportedException;
import com.github.javakeyring.Keyring;
import com.github.javakeyring.PasswordAccessException;

/**
 * 基于 {@code java-keyring} 的 {@link OsKeyring} 实现，对接
 * Windows Credential Store / macOS Keychain / Linux SecretService+KWallet。
 *
 * <p>本类唯一的实质职责是把各后端**互不一致**的错误表达，归一成 SPI 约定的两种结果：
 * "条目不存在 → 返回 null" 与 "后端故障 → 抛 {@link OsKeyringAccessException}"。</p>
 *
 * <p>WHY 归一化必须保守（宁可误判为故障，不可误判为不存在）：
 * 若把一次真实的后端故障误判成"条目不存在"，上层会生成一把**新**主密钥并覆盖旧条目，
 * 用户此前保存的全部 SSH 密码与 api key 将永久无法解密，且过程没有任何报错。
 * 反之，把"不存在"误判为故障只会让首次启动明确报出「凭据保护不可用」，
 * 用户可改用主密码回退，数据零损失。两种错误的代价完全不对称，
 * 因此本类的匹配规则只收录**已确证**的措辞与错误码，宁缺勿滥。</p>
 *
 * @see JavaKeyringAbsentEntryClassificationTest 各措辞的确定性回归
 */
public final class JavaKeyringOsKeyring implements OsKeyring {

    /**
     * 已确证的"条目不存在"**文本**措辞。
     * <ul>
     *   <li>{@code not found} —— {@code WinCredentialStoreBackend} 在 CredRead 成功但凭据块长度为 0 时
     *       抛 {@code PasswordAccessException("Password not Found")}（已核对其字节码常量池 #89）</li>
     *   <li>{@code could not be found} —— macOS 后端把 OSStatus 交给
     *       {@code SecCopyErrorMessageString}，{@code errSecItemNotFound}(-25300) 的官方文案为
     *       "The specified item could not be found in the keychain."</li>
     *   <li>{@code not in wallet} —— KWallet 后端抛 "Password is not in wallet"（同上映射自字节码）</li>
     * </ul>
     * 刻意**不**收录 Linux SecretService/dbus 的措辞：该后端的错误表达未经确证，
     * 猜测式匹配可能把"密钥环未解锁"误判为"条目不存在"从而覆盖主密钥。
     * 在 Linux 上验证后应把确证的措辞补进本列表。
     */
    private static final List<String> ABSENT_ENTRY_HINTS =
            List.of("not found", "could not be found", "not in wallet");

    /**
     * java-keyring 的 Windows 后端把原生失败码拼成 {@code "Error code <n>"}。
     *
     * <p>证据链（双向确证，非猜测）：
     * <ol>
     *   <li>字节码：{@code WinCredentialStoreBackend} 的 {@code getPassword}/{@code setPassword}/
     *       {@code deletePassword} 在原生调用返回 false 时，都以拼接模板
     *       {@code "Error code \u0001"}（常量池 #216）包装 {@code Kernel32.GetLastError()}；</li>
     *   <li>运行时：本机读取一个不存在的条目，实际抛出的正是
     *       {@code PasswordAccessException: Error code 1168}（{@code WinCredentialStoreBackend.java:61}）。</li>
     * </ol>
     * {@code PasswordAccessException} 只携带 message、**没有**错误码字段，
     * 因此解析消息是唯一的取码途径。</p>
     */
    private static final Pattern WINDOWS_ERROR_CODE = Pattern.compile("error code (\\d+)");

    /**
     * 允许判定为"条目不存在"的 Win32 错误码白名单。
     *
     * <p>WHY 只有一个码：{@code 1168 = ERROR_NOT_FOUND}（"Element not found."），
     * 这是 {@code CredRead}/{@code CredDelete} 在目标条目不存在时的返回码，已由本机实测确证。
     * 其余码（如 87 参数非法、1004 标志非法、1312 无此登录会话）都**不**代表条目不存在，
     * 一旦误收进来就会在真实故障时触发主密钥重生成，代价是用户全部凭据永久失联。</p>
     */
    private static final Set<String> ABSENT_WINDOWS_ERROR_CODES = Set.of("1168");

    /**
     * cause 链遍历深度上限。
     *
     * <p>WHY 需要上限：{@code Throwable.initCause} 允许构造出环形 cause 链，
     * 无上限的 {@code while (current.getCause())} 会让调用线程永久自旋。
     * 正常后端的嵌套深度不超过 3 层，32 层留足余量。</p>
     */
    private static final int MAX_CAUSE_DEPTH = 32;

    private final Keyring delegate;

    public JavaKeyringOsKeyring(Keyring delegate) {
        this.delegate = Objects.requireNonNull(delegate, "Keyring 委托不得为 null");
    }

    /**
     * 打开当前平台的密钥库。
     *
     * @return 实例；当前平台无可用后端时返回 {@code null}（调用方据此判断可用性）
     */
    public static OsKeyring open() {
        try {
            return new JavaKeyringOsKeyring(Keyring.create());
        } catch (BackendNotSupportedException e) {
            return null;
        } catch (RuntimeException | LinkageError e) {
            // WHY 捕获 LinkageError：后端通过 JNA 加载本地库，缺库时抛的是
            // UnsatisfiedLinkError 等 Error 而非 Exception；漏掉它会让应用启动直接崩溃，
            // 而不是优雅降级到"凭据保护不可用 + 主密码回退"。
            return null;
        }
    }

    @Override
    public String read(String service, String account) {
        try {
            return delegate.getPassword(service, account);
        } catch (PasswordAccessException e) {
            if (isAbsentEntry(e)) {
                return null;
            }
            throw new OsKeyringAccessException(
                    "读取操作系统密钥库失败: service=" + service + ", account=" + account, e);
        } catch (RuntimeException | LinkageError e) {
            throw new OsKeyringAccessException(
                    "读取操作系统密钥库失败: service=" + service + ", account=" + account, e);
        }
    }

    @Override
    public void write(String service, String account, String secret) {
        try {
            delegate.setPassword(service, account, secret);
        } catch (PasswordAccessException | RuntimeException | LinkageError e) {
            // 写入不存在"条目不存在"这一语义分支：CredWrite 是 upsert，失败即真实故障
            throw new OsKeyringAccessException(
                    "写入操作系统密钥库失败: service=" + service + ", account=" + account, e);
        }
    }

    @Override
    public void delete(String service, String account) {
        try {
            delegate.deletePassword(service, account);
        } catch (PasswordAccessException e) {
            // SPI 约定：删除不存在的条目是幂等成功，不应让清理流程因"本来就没有"而失败。
            // Windows 上 CredDelete 对不存在的目标同样返回 ERROR_NOT_FOUND(1168)。
            if (isAbsentEntry(e)) {
                return;
            }
            throw new OsKeyringAccessException(
                    "删除操作系统密钥库条目失败: service=" + service + ", account=" + account, e);
        } catch (RuntimeException | LinkageError e) {
            throw new OsKeyringAccessException(
                    "删除操作系统密钥库条目失败: service=" + service + ", account=" + account, e);
        }
    }

    @Override
    public void close() {
        try {
            delegate.close();
        } catch (Exception e) {
            // 关闭失败无需上抛：此时已无法补救，且不该让 Spring 的销毁回调链中断
            throw new OsKeyringAccessException("关闭操作系统密钥库失败", e);
        }
    }

    /**
     * 判断异常是否表示"条目本来就不存在"。遍历整条 cause 链——各后端常把真实原因包在里层。
     *
     * <p>包级可见而非 private：分类规则的正确性直接决定用户凭据是否会永久失联，
     * 必须能被确定性单测逐条钉死（含本机跑不到的 macOS/KWallet 措辞）。
     * {@code Keyring} 的构造器是私有的、又刻意不引入 Mockito，无法用替身从 {@code read()} 外侧驱动。</p>
     *
     * @param thrown 底层抛出的异常，可为 {@code null}
     * @return 仅当命中已确证的"不存在"信号时返回 {@code true}
     */
    static boolean isAbsentEntry(Throwable thrown) {
        int depth = 0;
        for (Throwable current = thrown; current != null && depth < MAX_CAUSE_DEPTH;
             current = current.getCause(), depth++) {
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            String normalized = message.toLowerCase(Locale.ROOT);
            for (String hint : ABSENT_ENTRY_HINTS) {
                if (normalized.contains(hint)) {
                    return true;
                }
            }
            if (isAbsentWindowsErrorCode(normalized)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从消息中取出 Win32 错误码并比对白名单。
     *
     * <p>WHY 用"锚定在 {@code error code} 之后的数字"而非裸 {@code contains("1168")}：
     * 后者会让任何恰好带这串数字的消息（字节数、耗时、端口）被误判为"条目不存在"，
     * 进而触发主密钥重生成。</p>
     */
    private static boolean isAbsentWindowsErrorCode(String normalizedMessage) {
        Matcher matcher = WINDOWS_ERROR_CODE.matcher(normalizedMessage);
        return matcher.find() && ABSENT_WINDOWS_ERROR_CODES.contains(matcher.group(1));
    }
}
