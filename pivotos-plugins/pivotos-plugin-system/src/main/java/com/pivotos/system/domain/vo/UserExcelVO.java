package com.pivotos.system.domain.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.pivotos.starter.excel.annotation.DictExcelProperty;
import lombok.Data;

/**
 * 用户 Excel 导入导出 DTO
 * <p>
 * 性别/状态字段用 @DictExcelProperty 实现字典双向翻译：
 * 导出时 value→label（1→男、0→正常），导入时 label→value（男→1、正常→0）。
 * </p>
 *
 * @author PivotOS Team
 * @since 2.1.0
 */
@Data
public class UserExcelVO {

    @ExcelProperty("用户名")
    private String username;

    @ExcelProperty("昵称")
    private String nickname;

    @DictExcelProperty(dictType = "sys_user_sex")
    @ExcelProperty("性别")
    private String gender;

    @ExcelProperty("手机号")
    private String mobile;

    @ExcelProperty("邮箱")
    private String email;

    @DictExcelProperty(dictType = "common_status")
    @ExcelProperty("状态")
    private String status;

    @ExcelProperty("部门")
    private String deptName;

    @ExcelProperty("备注")
    private String remark;
}
