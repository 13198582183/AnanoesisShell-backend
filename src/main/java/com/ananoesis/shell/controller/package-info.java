/**
 * REST 接口层（tasks 2.6 分层目录）。
 *
 * <p>职责：承载 {@code openapi.yaml} 冻结后由 openapi-generator 生成的接口实现，
 * 只做参数校验、DTO 与领域对象转换、以及把领域异常翻译成 HTTP 状态码；
 * MUST NOT 直接访问 Mapper，也 MUST NOT 承载业务规则。</p>
 *
 * <p>Wave 1 仅建立目录与职责约定，业务实现待接口契约冻结后在 Wave 2 接入。</p>
 */
package com.ananoesis.shell.controller;