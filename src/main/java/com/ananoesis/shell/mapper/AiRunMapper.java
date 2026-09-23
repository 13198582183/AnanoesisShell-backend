package com.ananoesis.shell.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.ananoesis.shell.entity.AiRun;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * {@code ai_runs} 表数据访问（design.md D10「运行账本」）。
 */
@Mapper
public interface AiRunMapper extends BaseMapper<AiRun> {
}
