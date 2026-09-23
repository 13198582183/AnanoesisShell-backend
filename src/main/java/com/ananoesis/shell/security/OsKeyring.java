package com.ananoesis.shell.security;

/**
 * 操作系统密钥库的最小 SPI。
 *
 * <p>WHY 要在 {@code java-keyring} 之上再包一层：
 * 第三方类型是 final 语义不明的具体类、抛受检异常，且各后端对"条目不存在"的
 * 表达方式互不相同。把这层差异收口在一个自有接口后面，主密钥托管逻辑就能
 * 用内存替身做确定性单测（tasks 4.1 的验收方式），
 * 真实后端只在 {@code JavaKeyringOsKeyringTest} 里以假设门控方式验证。</p>
 */
public interface OsKeyring extends AutoCloseable {

    /**
     * @return 条目内容；**条目不存在时返回 null**
     * @throws OsKeyringAccessException 后端故障时
     */
    String read(String service, String account);

    /** 写入或覆盖条目。 */
    void write(String service, String account, String secret);

    /** 删除条目；条目不存在时静默返回。 */
    void delete(String service, String account);

    @Override
    void close();

    /**
     * @return 一个永远不可用的实现。
     *         WHY 需要它：当前平台没有可用后端时，应用仍必须能**启动**
     *         （否则用户连"改用主密码回退"的机会都没有），
     *         但任何加解密尝试都要立刻得到明确报错。
     */
    static OsKeyring unavailable(String reason) {
        return new OsKeyring() {
            @Override
            public String read(String service, String account) {
                throw new OsKeyringAccessException(reason);
            }

            @Override
            public void write(String service, String account, String secret) {
                throw new OsKeyringAccessException(reason);
            }

            @Override
            public void delete(String service, String account) {
                throw new OsKeyringAccessException(reason);
            }

            @Override
            public void close() {
                // 无资源可释放
            }

            @Override
            public String toString() {
                return "OsKeyring.unavailable(" + reason + ")";
            }
        };
    }
}