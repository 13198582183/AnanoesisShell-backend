package com.ananoesis.shell.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;

import com.ananoesis.shell.entity.Host;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * {@code hosts} 表数据访问（ssh-connection spec「服务器配置管理」）。
 *
 * <p>Wave 2a 按 Wave 1 预留的方式补充业务查询：以 {@code default} 方法就地表达，
 * 不引入 XML——两条查询都不涉及动态 SQL，XML 只会把它们藏到一个需要额外跳转的文件里。</p>
 *
 * <p>WHY 用 {@code @Mapper} 而非 {@code @MapperScan}：MyBatis-Plus 的自动配置会从
 * {@code @SpringBootApplication} 所在包开始扫描 {@code @Mapper} 接口，
 * 显式注解让每个接口的"我是持久层"身份就地可读，也不依赖扫描路径配置。</p>
 */
@Mapper
public interface HostMapper extends BaseMapper<Host> {

    /**
     * 按创建时间升序返回全部服务器配置。
     *
     * <p>WHY 需要显式排序：{@code selectList(null)} 的结果顺序由 SQLite 的扫描计划决定，
     * 并不保证稳定。服务器列表是用户每次都看的界面，顺序抖动会让人以为"记录丢了又回来了"。
     * 追加 {@code id} 作为次级键，是为了在同一毫秒内创建的多条记录也有确定次序。</p>
     */
    default List<Host> selectAllOrderedByCreation() {
        return selectList(new QueryWrapper<Host>().orderByAsc("created_at", "id"));
    }

    /**
     * 全量写回一条配置（含**可空**列）。
     *
     * <p>WHY 不用 {@code updateById}：全局配置了 {@code update-strategy: not_null}
     * （见 {@code application.yml}，那是为了让 DDL 默认值生效），它会**静默跳过**
     * 值为 null 的字段。于是"用户清空了分组"这一操作会变成 no-op：
     * 界面提交成功、刷新后旧值又回来了，且没有任何报错可查。
     * 这里显式列出每个可空列，把"null 也是要写入的值"这件事变成代码事实。</p>
     *
     * <p>WHY 手写 {@code updated_at}：走 {@code UpdateWrapper} 时没有实体参与，
     * {@code AuditMetaObjectHandler#updateFill} 不会被触发；不补这一列，
     * 更新时间就会永远停在插入时刻。</p>
     *
     * <p>{@code created_at} 刻意不在 SET 列表中——创建时间一旦写定就不得被改写。</p>
     *
     * @return 受影响行数
     */
    default int updateConfiguration(Host host) {
        UpdateWrapper<Host> wrapper = new UpdateWrapper<Host>()
                .eq("id", host.getId())
                .set("name", host.getName())
                .set("host", host.getHost())
                .set("port", host.getPort())
                .set("username", host.getUsername())
                .set("auth_type", host.getAuthType())
                .set("group_name", host.getGroupName())
                .set("remark", host.getRemark())
                .set("updated_at", LocalDateTime.now());
        return update(null, wrapper);
    }
}
