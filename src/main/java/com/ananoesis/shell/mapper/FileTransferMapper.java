package com.ananoesis.shell.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.ananoesis.shell.entity.FileTransfer;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * {@code file_transfers} 表数据访问（design.md D10「SFTP 传输」）。
 */
@Mapper
public interface FileTransferMapper extends BaseMapper<FileTransfer> {
}
