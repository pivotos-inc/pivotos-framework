package com.pivotos.system.domain.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** 个人资料修改请求（移动端"我的"，仅本人字段，全部可选） */
@Data
public class ProfileUpdateRequest {

    /** 昵称 */
    @Size(max = 64, message = "昵称最长 64 字符")
    private String nickname;

    /** 头像地址（file/presign 直传后的 fileUrl 或 objectKey） */
    @Size(max = 512, message = "头像地址最长 512 字符")
    private String avatar;

    /** 邮箱 */
    @Size(max = 64, message = "邮箱最长 64 字符")
    private String email;

    /** 手机号 */
    @Size(max = 20, message = "手机号最长 20 字符")
    private String mobile;
}
