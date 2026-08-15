package com.pivotos.workflow.support;

import com.pivotos.common.core.enums.error.GlobalErrorCode;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.starter.core.context.LoginContext;

/**
 * 工作流登录校验支撑（S79 三端鉴权打通）。
 * <p>
 * sys / app / wx-mini 三账号体系通用：登录态由 LoginContextFilter 统一解析，
 * 移动端所需端点只校验「已登录」，业务归属（发起人/待办人）由 service 与
 * warm-flow 引擎层兜底；与消息中心 UserMessageController 同款口径。
 * 管理态操作（流程定义维护、终止）仍走 @SaCheckPermission(StpSysUtil.TYPE)。
 */
public final class WorkflowAuthSupport {

    private WorkflowAuthSupport() {
    }

    /** 三体系统一登录校验（未登录 → 1002，与 Sa-Token 未登录同码） */
    public static Long requireUserId() {
        Long userId = LoginContext.getUserId();
        if (userId == null) {
            throw new ServiceException(GlobalErrorCode.UNAUTHORIZED);
        }
        return userId;
    }
}
