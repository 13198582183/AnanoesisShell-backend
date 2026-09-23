package com.ananoesis.shell.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.ananoesis.shell.entity.CommandExecution;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * {@code command_executions} 表数据访问（design.md D10「命令执行账本」）。
 */
@Mapper
public interface CommandExecutionMapper extends BaseMapper<CommandExecution> {
}
