package com.ananoesis.shell.security;

/**
 * 主密钥来源。
 *
 * <p>credential-store spec 要求主密钥托管于操作系统密钥库；本接口把这层依赖抽象出来，
 * 使得（1）加解密逻辑可以在无桌面会话的 CI 上被确定性测试，
 * （2）"密钥库不可用"成为一个可被显式表达、显式拒绝的状态，
 * 而不是一段藏在实现里的 try/catch。</p>
 *
 * <p>实现约定：{@link #copyMasterKeyBytes()} 每次返回**新副本**，
 * 调用方用毕必须清零；实现内部可以缓存，但不得把同一个数组实例交出去，
 * 否则一次擦除就会破坏后续所有加解密。</p>
 */
public interface MasterKeyProvider {

    /**
     * 提供者标识，会被写进密文信封的 {@code kp} 字段。
     * WHY 必须落盘：解密时要据此判断该用哪把主密钥，
     * 否则用户一旦切换密钥来源，历史密文将无法定位解密方式。
     */
    String id();

    /** 当前是否可用；返回 false 时 {@link #copyMasterKeyBytes()} 必须抛异常。 */
    boolean isAvailable();

    /**
     * @return 32 字节（AES-256）主密钥的新副本
     * @throws CredentialProtectionException 密钥库不可用时；消息含「凭据保护不可用」
     */
    byte[] copyMasterKeyBytes();
}