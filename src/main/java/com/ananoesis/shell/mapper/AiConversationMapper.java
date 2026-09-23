package com.ananoesis.shell.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.ananoesis.shell.entity.AiConversation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * {@code ai_conversations} 表数据访问（ai-agent spec「多轮上下文延续」）。
 */
@Mapper
public interface AiConversationMapper extends BaseMapper<AiConversation> {
}