/**
 * 跨层复用的无状态工具（tasks 2.6 分层目录）。
 *
 * <p>本包只放"不属于任何业务概念、但被多层共同依赖"的极小工具。判定标准：
 * 若一个类需要注入 Spring bean 或承载业务规则，它就属于 {@code service} 而非本包。</p>
 */
package com.ananoesis.shell.support;
