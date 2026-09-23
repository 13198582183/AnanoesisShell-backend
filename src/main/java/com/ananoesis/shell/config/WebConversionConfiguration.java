package com.ananoesis.shell.config;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.GenericConverter;
import org.springframework.format.FormatterRegistry;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 让<b>查询参数/路径参数</b>里的契约枚举按契约的线上取值解析。
 *
 * <h2>WHY 必须有这个类（这是一个真实的、已复现的缺陷）</h2>
 * <p>Spring MVC 默认用 {@code StringToEnumConverterFactory}，其实现是
 * {@code Enum.valueOf(type, source)}——按 Java <b>常量名</b>匹配。而 openapi-generator
 * 为 {@code SessionStatus} 生成的常量名是 {@code OPEN}/{@code ENDED}，
 * 契约规定的线上取值却是小写的 {@code open}/{@code ended}（由 {@code @JsonValue getValue()}
 * 决定）。两者对不上，于是 {@code GET /api/sessions?status=open} 返回 400，
 * 而 {@code ?status=OPEN} 才通过——前端严格按契约发请求却收到"参数非法"。</p>
 *
 * <p>WHY 只在请求体上不出问题：报文体的反序列化走 Jackson，
 * {@code @JsonCreator fromValue(String)} 生效，所以 {@code auth_type: "password"} 一直是对的。
 * 查询参数走的是 Spring 的 ConversionService，<b>不经过 Jackson</b>。
 * 这正是"同一个枚举在两个位置表现不同"的原因，也是最容易被漏掉的一类缺陷。</p>
 *
 * <h2>WHY 做成通用转换器而不是只补 SessionStatus</h2>
 * <p>契约里目前有<b>两个</b>枚举型查询参数：{@code SessionsApi.listSessions(status)} 与
 * {@code ApprovalsApi.listApprovals(decision)}。后者属 Wave 3，若只修前者，
 * 同一个坑会在下个 Wave 原样复现，而且届时已经没人记得这里的取舍。
 * 通用做法是：凡是带有 {@code static fromValue(String)} 的枚举（openapi-generator 的产物一律有），
 * 就用它解析；没有的枚举退回默认的 {@code Enum.valueOf} 行为。</p>
 *
 * <p>{@code ContractEnumConversionTest} 会扫描全部生成的 {@code *Api} 接口，
 * 对每个枚举型参数、每个常量，断言"契约线上取值 → 常量"这条转换成立。
 * 新增端点时若引入了新的枚举参数，那个测试会自动覆盖到，无需人工记得。</p>
 *
 * <p>WHY 用 {@link GenericConverter} 而不是 {@code ConverterFactory<String, Enum<?>>}：
 * 后者的 {@code getConverter(Class<T extends Enum<?>>)} 无法满足 {@code Enum.valueOf}
 * 要求的 {@code <T extends Enum<T>>} 自限定泛型，写出来必须用原始类型压制警告；
 * {@code GenericConverter} 的入参是 {@code TypeDescriptor}，拿到的就是运行期 {@code Class<?>}，
 * 没有这层泛型矛盾。</p>
 */
@Configuration(proxyBeanMethods = false)
public class WebConversionConfiguration implements WebMvcConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        // WHY 注册顺序无所谓但确实"后来者优先"：GenericConversionService 内部对同一个
        // ConvertiblePair 用 Deque#addFirst 存放，本转换器因此覆盖掉默认的
        // StringToEnumConverterFactory（二者都声明 (String, Enum)）
        registry.addConverter(new ContractEnumConverter());
    }

    /**
     * {@code String -> Enum} 的转换器：优先用契约生成物的 {@code fromValue}。
     */
    static final class ContractEnumConverter implements GenericConverter {

        /**
         * WHY 缓存 {@code fromValue} 的查找结果：本转换器位于每一次带枚举参数的请求路径上，
         * 而 {@code Class#getMethod} 每次都要复制一份注解与参数数组。
         * 枚举类型是有限且不变的，缓存后每个类型只反射一次。
         */
        private final Map<Class<?>, Optional<Method>> fromValueCache = new ConcurrentHashMap<>();

        @Override
        public Set<ConvertiblePair> getConvertibleTypes() {
            // (String, Enum) 是模糊匹配：GenericConversionService 在找不到 (String, SessionStatus)
            // 这种精确对时，会沿目标类型的父类链向上找，Enum 即在其中
            return Set.of(new ConvertiblePair(String.class, Enum.class));
        }

        @Override
        @Nullable
        public Object convert(@Nullable Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
            String text = (String) source;
            // WHY 空串归 null：这是 Spring 默认枚举转换器的行为（?status= 视为"不过滤"）。
            // 若改成抛异常，前端"清空筛选条件"时会发出 ?status= 并收到 400
            if (text == null || text.isEmpty()) {
                return null;
            }
            Class<?> enumType = targetType.getType();
            Method fromValue = fromValueCache
                    .computeIfAbsent(enumType, ContractEnumConverter::findFromValue)
                    .orElse(null);
            if (fromValue == null) {
                return valueOfConstantName(enumType, text);
            }
            try {
                return fromValue.invoke(null, text);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                // WHY 原样抛出 cause：生成物的 fromValue 在取值非法时抛 IllegalArgumentException，
                // Spring 据此判定"类型不匹配"并转成 400 validation_error。
                // 若把反射包装后的 InvocationTargetException 抛出去，走的就不是同一条分支，
                // 客户端拼错一个查询参数会变成 500
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalArgumentException(cause);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("无法调用 " + enumType.getName() + "#fromValue", e);
            }
        }

        private static Optional<Method> findFromValue(Class<?> enumType) {
            try {
                Method method = enumType.getMethod("fromValue", String.class);
                return Modifier.isStatic(method.getModifiers()) ? Optional.of(method) : Optional.empty();
            } catch (NoSuchMethodException e) {
                // 非 openapi-generator 产物（例如手写的内部枚举），退回常量名匹配
                return Optional.empty();
            }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static Object valueOfConstantName(Class<?> enumType, String text) {
            return Enum.valueOf((Class) enumType, text);
        }
    }
}
