package com.pivotos.starter.web.checker;

/**
 * 幂等判定器接口：本模块只定义契约，
 * Redis 实现由 pivotos-starter-redis 注册（S6），条件装配。
 */
public interface RepeatSubmitChecker {

    /**
     * 判定并占位
     *
     * @param key            请求指纹（用户 + 方法 + URI + 参数）
     * @param intervalMillis 幂等窗口
     * @return true = 窗口内已存在相同请求，判定重复提交
     */
    boolean isRepeatSubmit(String key, long intervalMillis);
}
