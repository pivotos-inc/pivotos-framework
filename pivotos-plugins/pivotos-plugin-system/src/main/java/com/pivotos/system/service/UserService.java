package com.pivotos.system.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.starter.excel.util.ExcelImportResult;
import com.pivotos.system.domain.dto.ResetPasswordBody;
import com.pivotos.system.domain.dto.ChangePasswordBody;
import com.pivotos.system.domain.dto.ProfileUpdateRequest;
import com.pivotos.system.domain.dto.UserQuery;
import com.pivotos.system.domain.dto.UserSaveRequest;
import com.pivotos.system.domain.entity.SysUser;
import com.pivotos.system.domain.vo.UserExcelVO;
import com.pivotos.system.domain.vo.UserVO;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/** 用户服务 */
public interface UserService extends IService<SysUser> {

    /** 分页查询用户 */
    PageResult<UserVO> pageUsers(UserQuery query);

    /** 查询用户详情 */
    UserVO getUser(Long userId);

    /** 新增用户（含角色分配），返回用户ID */
    Long createUser(UserSaveRequest request);

    /** 修改用户（含角色重建；密码留空表示不变） */
    void updateUser(UserSaveRequest request);

    /** 删除用户（逻辑删除，清理角色关联） */
    void deleteUser(Long userId);

    /** 重置密码 */
    void resetPassword(ResetPasswordBody body);

    /** 按用户名查询实体（登录专用，返回含密码的完整实体） */
    SysUser getByUsername(String username);

    /** 修改本人资料（昵称/头像/邮箱/手机号，null 字段不动） */
    void updateProfile(Long userId, ProfileUpdateRequest request);

    /** 修改本人密码（旧密码校验通过才允许重置） */
    void changePassword(Long userId, ChangePasswordBody body);

    /** 导出用户列表为 Excel（分页流式写入 response） */
    void exportUsers(HttpServletResponse response, UserQuery query) throws IOException;

    /** 导入用户 Excel（单行校验 + 分批入库 + 错误行回执） */
    ExcelImportResult<UserExcelVO> importUsers(MultipartFile file) throws IOException;

    /** 下载用户导入模板 */
    void downloadUserTemplate(HttpServletResponse response) throws IOException;
}
