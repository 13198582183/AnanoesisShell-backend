package com.ananoesis.shell.support;

import java.util.stream.Stream;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.lang.Nullable;

/**
 * 固定返回<b>同一个</b>实例（或什么都没有）的 {@link ObjectProvider} 替身。
 *
 * <p>WHY 需要它：{@code ApprovalGate} 与 {@code AiAgentService} 都用
 * {@code ObjectProvider<X>} 注入协作者，目的只是把<b>循环依赖</b>在类型层面断开
 * （见 {@code ApprovalGate} 类注释）。单元测试里没有容器，
 * 于是"惰性解析"退化成一个纯粹的语法障碍——不给它一个实现就 new 不出被测对象。</p>
 *
 * <p>WHY 手写而不用 Mockito：本项目沿用 Wave 1 的测试风格（见 {@link SshTestDoubles} 的说明）。
 * 这里的行为只有一条：{@code getIfAvailable()} 返回给定实例。用 mock 框架表达它，
 * 代码量只会更多，而且"到底返回了什么"变成一处需要读 {@code when(...)} 才知道的间接。</p>
 *
 * <p>WHY {@link #empty()} 也要支持：两个被测类都显式处理了 {@code getIfAvailable() == null}
 * 的分支（没有广播出口 / 没有下行出口时只记日志、不中断）。那条分支是真实运行时会走到的
 * ——前端没连上就是 null——因此必须可测。</p>
 *
 * @param <T> 被提供的类型
 */
public final class StaticObjectProvider<T> implements ObjectProvider<T> {

    @Nullable
    private final T value;

    private StaticObjectProvider(@Nullable T value) {
        this.value = value;
    }

    /** @return 恒定返回 {@code value} 的提供者 */
    public static <T> StaticObjectProvider<T> of(T value) {
        if (value == null) {
            // 传 null 的调用点几乎一定是写错了；用 empty() 表达"没有"，意图才清楚
            throw new IllegalArgumentException("请使用 empty() 表达'没有可用实例'");
        }
        return new StaticObjectProvider<>(value);
    }

    /** @return 恒定返回 {@code null} 的提供者（模拟容器里没有该类型的 bean） */
    public static <T> StaticObjectProvider<T> empty() {
        return new StaticObjectProvider<>(null);
    }

    @Override
    public T getObject() throws BeansException {
        if (value == null) {
            throw new NoSuchBeanDefinitionException("StaticObjectProvider 为空");
        }
        return value;
    }

    @Override
    public T getObject(Object... args) throws BeansException {
        return getObject();
    }

    @Override
    @Nullable
    public T getIfAvailable() throws BeansException {
        return value;
    }

    @Override
    @Nullable
    public T getIfUnique() throws BeansException {
        return value;
    }

    @Override
    public Stream<T> stream() {
        return value == null ? Stream.empty() : Stream.of(value);
    }

    @Override
    public Stream<T> orderedStream() {
        return stream();
    }
}
