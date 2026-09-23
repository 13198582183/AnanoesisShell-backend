package com.ananoesis.shell.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.ananoesis.shell.entity.AiMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * {@code ai_messages} 表数据访问。
 *
 * <p>消息按 {@code (conversation_id, seq)} 唯一且**只追加不修改**：
 * 重建上下文时必须 {@code orderByAsc("seq")}，不能依赖 rowid 或插入顺序。</p>
 */
@Mapper
public interface AiMessageMapper extends BaseMapper<AiMessage> {
}