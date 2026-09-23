/**
 * 服务层（tasks 2.6 分层目录）。
 *
 * <p>职责：承载业务规则与事务边界，是加解密、SSH 连接、审批闸门等安全关键逻辑的唯一收口点。
 * 控制器与 WebSocket 处理器都只做编排，规则一律下沉到本层。</p>
 */
package com.ananoesis.shell.service;