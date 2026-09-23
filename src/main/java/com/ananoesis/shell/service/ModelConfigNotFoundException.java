package com.ananoesis.shell.service;

/**
 * 请求的模型配置不存在（model-provider spec「模型配置管理」）。
 *
 * <p>WHY 单独建类型而不是复用 {@link NotFoundException}：
 * {@code ChatModelProvider} 需要能精确 catch "生效配置没了" 这一种情况——
 * 它与"api key 没配"在用户界面上是同一个引导（去设置页），
 * 但与"主机配置没了"（{@link HostNotFoundException}）的处置完全不同。</p>
 */
public class ModelConfigNotFoundException extends NotFoundException {

    public ModelConfigNotFoundException(String message) {
        super(message);
    }

    /** @param configId 未命中的主键，仅用于消息拼装（非敏感坐标） */
    public static ModelConfigNotFoundException forId(String configId) {
        return new ModelConfigNotFoundException("模型配置不存在: id=" + configId);
    }
}
