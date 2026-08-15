package com.pivotos.workflow.domain.vo;

import lombok.Data;

/**
 * 用户选项视图（S81 加签选人）。
 */
@Data
public class UserOptionVO {

    /** 用户 ID */
    private Long id;

    /** 用户名 */
    private String username;

    /** 昵称 */
    private String nickname;
}
