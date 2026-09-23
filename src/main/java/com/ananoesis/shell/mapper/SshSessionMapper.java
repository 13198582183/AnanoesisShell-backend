package com.ananoesis.shell.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.ananoesis.shell.entity.SshSession;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * {@code sessions} 表数据访问（ssh-connection spec「连接会话生命周期」）。
 *
 * <p>WHY 命名为 {@code SshSessionMapper}：与实体 {@link SshSession} 保持一致，
 * 避免与 WebSocket 的 session 概念混淆——design.md D4 下二者是完全不同的东西。</p>
 */
@Mapper
public interface SshSessionMapper extends BaseMapper<SshSession> {
}