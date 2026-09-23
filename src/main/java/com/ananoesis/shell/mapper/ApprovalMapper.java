package com.ananoesis.shell.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.ananoesis.shell.entity.Approval;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * {@code approvals} 表数据访问（command-approval spec「审批审计日志」）。
 *
 * <p>写入本表的数据构成审计证据链，MUST NOT 含明文凭据；命令文本需由服务层脱敏。</p>
 */
@Mapper
public interface ApprovalMapper extends BaseMapper<Approval> {
}