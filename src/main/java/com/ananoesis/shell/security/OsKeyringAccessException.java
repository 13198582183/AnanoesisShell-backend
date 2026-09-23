package com.ananoesis.shell.security;

/**
 * 操作系统密钥库访问失败（后端未运行、本地库加载失败、权限不足等）。
 *
 * <p>WHY 用非受检异常：{@code java-keyring} 自身抛受检的
 * {@code PasswordAccessException}/{@code BackendNotSupportedException}，
 * 若照搬会让每个调用点都被迫 try/catch；而"密钥库不可用"在本应用里
 * 始终应转换为 {@link CredentialProtectionException} 这一条统一出口，
 * 中间层再暴露一套受检异常只会制造噪音。</p>
 */
public class OsKeyringAccessException extends RuntimeException {

    public OsKeyringAccessException(String message) {
        super(message);
    }

    public OsKeyringAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}