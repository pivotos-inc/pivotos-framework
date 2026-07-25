package com.pivotos.common.api.dto;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * DTO 基类：主键 + 审计字段，与 BaseDO 对应
 * （Long 序列化为 String 防前端精度丢失，由 starter-core 的 fastjson2 全局配置处理）
 */
public class BaseDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（雪花 ID） */
    private Long id;

    /** 创建人 */
    private Long createBy;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新人 */
    private Long updateBy;

    /** 更新时间 */
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCreateBy() {
        return createBy;
    }

    public void setCreateBy(Long createBy) {
        this.createBy = createBy;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public Long getUpdateBy() {
        return updateBy;
    }

    public void setUpdateBy(Long updateBy) {
        this.updateBy = updateBy;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
