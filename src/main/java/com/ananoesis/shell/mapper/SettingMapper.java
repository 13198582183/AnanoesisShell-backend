package com.ananoesis.shell.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;

import com.ananoesis.shell.entity.Setting;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * {@code settings} 表数据访问。
 *
 * <p>主键为业务键 {@code setting_key}，故 {@code selectById("approval.timeout.seconds")}
 * 即为按设置名点查。V1 迁移已预置 spec 要求的各项阈值默认值。</p>
 *
 * <p>Wave 3 起本表还承载<b>模型配置</b>：每条配置是一行
 * {@code setting_key='model.config.<uuid>'}、{@code value_type='json'} 的记录
 * （见 V1 迁移的表注释与 {@code ModelConfigService}）。因此这里补一个按前缀列举的查询。</p>
 */
@Mapper
public interface SettingMapper extends BaseMapper<Setting> {

    /**
     * 按业务键点查。
     *
     * <p>WHY 在 {@code selectById} 之外再包一层：调用点写 {@code findByKey("read_file.max_lines")}
     * 比 {@code selectById(...)} 更能说明"这是一次按键查询"，也避免读者误以为传的是代理主键。</p>
     */
    default Setting findByKey(String settingKey) {
        return selectById(settingKey);
    }

    /**
     * 列出以给定前缀开头的全部设置行。
     *
     * <p>WHY 用 {@code likeRight} 是安全的：前缀字面量（如 {@code model.config.}）里
     * <b>不含</b> SQL LIKE 的通配符 {@code _} 与 {@code %}，因此不会被误解释成模式。
     * 若日后引入带下划线的前缀，必须改用 {@code escape} 显式转义，否则
     * {@code model_config.} 会连带匹配到 {@code modelXconfig.}。</p>
     *
     * <p>WHY 不在 SQL 里排序：JSON 载荷里的 {@code created_at} 才是真正的创建时间，
     * 而 {@code settings} 表只有 {@code updated_at} 一列时间。配置数量是个位数，
     * 排序交给服务层在内存里做，比在 SQL 里解析 JSON 更直白也更可测。</p>
     */
    default List<Setting> findByKeyPrefix(String prefix) {
        return selectList(new QueryWrapper<Setting>().likeRight("setting_key", prefix));
    }

    /**
     * 只更新值与更新时间。
     *
     * <p>WHY 手写 {@code updated_at}：走 {@code UpdateWrapper} 时没有实体参与，
     * {@code AuditMetaObjectHandler#updateFill} 不会被触发；不补这一列，
     * 更新时间就会永远停在插入时刻（与 {@code HostMapper#updateConfiguration} 同一理由）。</p>
     */
    default int updateValueByKey(String settingKey, String settingValue) {
        return update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Setting>()
                .eq("setting_key", settingKey)
                .set("setting_value", settingValue)
                .set("updated_at", LocalDateTime.now()));
    }
}
