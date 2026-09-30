package com.pivotos.system.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/** PivotOS·智域登录响应 */
@Data
@AllArgsConstructor
public class MindLoginVO {

    /** 访问令牌 */
    private String token;

    /** 是否新用户 */
    private Boolean fresh;
}
