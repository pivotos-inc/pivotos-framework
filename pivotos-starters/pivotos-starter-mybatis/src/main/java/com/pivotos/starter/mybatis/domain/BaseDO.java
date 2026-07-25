package com.pivotos.starter.mybatis.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 实体基类：雪花主键 + 审计字段 + 逻辑删除。
 * 审计字段由 AuditMetaObjectHandler 自动填充，业务代码禁止手工赋值。
 * 乐观锁：需要的实体自行加 @Version 字段（不强制所有表带 version 列）。
 */
@Data
public class BaseDO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（雪花 ID，禁自增） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 创建人 */
    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新人 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 逻辑删除标记：0 正常 1 删除 */
    @TableLogic
    @TableField(fill = FieldFill.INSERT)
    private Integer deleted;
}
