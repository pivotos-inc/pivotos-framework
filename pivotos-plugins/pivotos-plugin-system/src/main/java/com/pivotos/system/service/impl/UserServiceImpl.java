package com.pivotos.system.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.pivotos.common.core.constant.CommonConstants;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.starter.excel.function.RowValidator;
import com.pivotos.starter.excel.util.ExcelHelper;
import com.pivotos.starter.excel.util.ExcelImportResult;
import com.pivotos.system.api.enums.SystemErrorCode;
import com.pivotos.system.constant.SystemConstants;
import com.pivotos.system.convert.UserConvert;
import com.pivotos.system.domain.dto.ResetPasswordBody;
import com.pivotos.system.domain.dto.ChangePasswordBody;
import com.pivotos.system.domain.dto.ProfileUpdateRequest;
import com.pivotos.system.domain.dto.UserQuery;
import com.pivotos.system.domain.dto.UserSaveRequest;
import com.pivotos.system.domain.entity.SysDept;
import com.pivotos.system.domain.entity.SysUser;
import com.pivotos.system.domain.entity.SysUserRole;
import com.pivotos.system.domain.vo.UserExcelVO;
import com.pivotos.system.domain.vo.UserVO;
import com.pivotos.system.mapper.SysDeptMapper;
import com.pivotos.system.mapper.SysUserMapper;
import com.pivotos.system.mapper.SysUserRoleMapper;
import com.pivotos.system.service.ConfigService;
import com.pivotos.system.service.UserService;
import com.pivotos.system.support.PageUtils;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 用户服务实现 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements UserService {

    private final UserConvert userConvert;
    private final SysUserRoleMapper userRoleMapper;
    private final ConfigService configService;
    private final ExcelHelper excelHelper;
    private final SysDeptMapper deptMapper;

    @Override
    public PageResult<UserVO> pageUsers(UserQuery query) {
        LambdaQueryWrapper<SysUser> wrapper = buildQueryWrapper(query);
        wrapper.orderByDesc(SysUser::getCreateTime);
        Page<SysUser> page = page(PageUtils.toMpPage(query), wrapper);
        return PageUtils.toPageResult(page, userConvert.toVoList(page.getRecords()));
    }

    @Override
    public UserVO getUser(Long userId) {
        return userConvert.toVo(requireUser(userId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createUser(UserSaveRequest request) {
        checkUsernameUnique(request.getUsername(), null);
        SysUser entity = userConvert.toEntity(request);
        entity.setId(null);
        String rawPassword = StringUtils.hasText(request.getPassword())
                ? request.getPassword()
                : configService.getConfigValue(SystemConstants.CONFIG_INIT_PASSWORD,
                        SystemConstants.DEFAULT_INIT_PASSWORD);
        entity.setPassword(BCrypt.hashpw(rawPassword, BCrypt.gensalt()));
        save(entity);
        rebuildUserRoles(entity.getId(), request.getRoleIds());
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateUser(UserSaveRequest request) {
        SysUser exist = requireUser(request.getId());
        checkSuperAdminStatusChange(exist, request);
        checkUsernameUnique(request.getUsername(), request.getId());
        SysUser entity = userConvert.toEntity(request);
        // 密码留空表示不变（updateById 忽略 null 字段）
        entity.setPassword(StringUtils.hasText(request.getPassword())
                ? BCrypt.hashpw(request.getPassword(), BCrypt.gensalt())
                : null);
        updateById(entity);
        rebuildUserRoles(request.getId(), request.getRoleIds());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteUser(Long userId) {
        requireUser(userId);
        if (CommonConstants.SUPER_ADMIN_ID.equals(userId)) {
            throw new ServiceException(SystemErrorCode.SUPER_ADMIN_FORBIDDEN);
        }
        removeById(userId);
        userRoleMapper.delete(Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, userId));
    }

    @Override
    public void resetPassword(ResetPasswordBody body) {
        requireUser(body.getUserId());
        SysUser entity = new SysUser();
        entity.setId(body.getUserId());
        entity.setPassword(BCrypt.hashpw(body.getPassword(), BCrypt.gensalt()));
        updateById(entity);
    }

    @Override
    public SysUser getByUsername(String username) {
        return getOne(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, username));
    }

    @Override
    public void updateProfile(Long userId, ProfileUpdateRequest request) {
        requireUser(userId);
        SysUser entity = new SysUser();
        entity.setId(userId);
        entity.setNickname(request.getNickname());
        entity.setAvatar(request.getAvatar());
        entity.setEmail(request.getEmail());
        entity.setMobile(request.getMobile());
        // MP updateById 只更新非 null 字段，null 即"不动该列"
        updateById(entity);
    }

    @Override
    public void changePassword(Long userId, ChangePasswordBody body) {
        SysUser user = requireUser(userId);
        if (!BCrypt.checkpw(body.getOldPassword(), user.getPassword())) {
            throw new ServiceException(SystemErrorCode.OLD_PASSWORD_ERROR);
        }
        SysUser entity = new SysUser();
        entity.setId(userId);
        entity.setPassword(BCrypt.hashpw(body.getNewPassword(), BCrypt.gensalt()));
        updateById(entity);
    }

    // ==================== Excel 导入导出（S27 2.1-F8/F9） ====================

    @Override
    public void exportUsers(HttpServletResponse response, UserQuery query) throws IOException {
        // 预加载部门名称映射
        List<SysDept> allDepts = deptMapper.selectList(null);
        Map<Long, String> deptNameMap = allDepts.stream()
                .collect(Collectors.toMap(SysDept::getId, SysDept::getDeptName, (a, b) -> a));

        excelHelper.export(response, "用户列表", UserExcelVO.class,
                (pageNum, pageSize) -> {
                    LambdaQueryWrapper<SysUser> wrapper = buildQueryWrapper(query);
                    Page<SysUser> page = baseMapper.selectPage(
                            new Page<>(pageNum, pageSize), wrapper);
                    if (page.getRecords().isEmpty()) return List.of();
                    return page.getRecords().stream()
                            .map(user -> toExcelVO(user, deptNameMap))
                            .collect(Collectors.toList());
                });
    }

    // 手机号与邮箱正则（与前端保持一致）
    private static final Pattern MOBILE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");

    @Override
    public ExcelImportResult<UserExcelVO> importUsers(MultipartFile file) throws IOException {
        // 预加载部门名称→ID 映射（导入时需要反查）
        List<SysDept> allDepts = deptMapper.selectList(null);
        Map<String, Long> deptNameToId = new HashMap<>();
        for (SysDept dept : allDepts) {
            deptNameToId.put(dept.getDeptName(), dept.getId());
        }
        // 预加载全量用户名集合（用于导入去重检查）
        Set<String> existingUsernames = this.lambdaQuery()
                .select(SysUser::getUsername).list().stream()
                .map(SysUser::getUsername)
                .collect(Collectors.toSet());
        // 本批次内已出现的用户名（防止文件内重复）
        Set<String> batchUsernames = new HashSet<>();

        String defaultPassword = BCrypt.hashpw(
                configService.getConfigValue(SystemConstants.CONFIG_INIT_PASSWORD, SystemConstants.DEFAULT_INIT_PASSWORD),
                BCrypt.gensalt());

        RowValidator<UserExcelVO> validator = vo -> {
            String username = vo.getUsername();
            // 1. 用户名必填
            if (username == null || username.isBlank()) {
                throw new ServiceException("用户名为必填项");
            }
            // 2. 手机号格式（如果有值）
            if (vo.getMobile() != null && !vo.getMobile().isBlank()
                    && !MOBILE_PATTERN.matcher(vo.getMobile()).matches()) {
                throw new ServiceException("手机号格式不正确");
            }
            // 3. 邮箱格式（如果有值）
            if (vo.getEmail() != null && !vo.getEmail().isBlank()
                    && !EMAIL_PATTERN.matcher(vo.getEmail()).matches()) {
                throw new ServiceException("邮箱格式不正确");
            }
            // 4. 用户名去重：已在数据库中存在
            if (existingUsernames.contains(username)) {
                throw new ServiceException("用户『" + username + "』已存在，导入失败");
            }
            // 5. 用户名去重：本批导入文件中重复
            if (!batchUsernames.add(username)) {
                throw new ServiceException("用户『" + username + "』在当前导入文件中重复");
            }
        };

        return excelHelper.importExcel(file, UserExcelVO.class,
                batch -> {
                    List<SysUser> entities = new ArrayList<>();
                    for (UserExcelVO vo : batch) {
                        SysUser user = new SysUser();
                        user.setUsername(vo.getUsername());
                        user.setNickname(vo.getNickname());
                        user.setGender(Integer.parseInt(vo.getGender() != null && !vo.getGender().isEmpty() ? vo.getGender() : "0"));
                        user.setMobile(vo.getMobile());
                        user.setEmail(vo.getEmail());
                        user.setStatus(Integer.parseInt(vo.getStatus() != null && !vo.getStatus().isEmpty() ? vo.getStatus() : "0"));
                        user.setDeptId(deptNameToId.getOrDefault(vo.getDeptName(), 0L));
                        user.setRemark(vo.getRemark());
                        user.setPassword(defaultPassword);
                        entities.add(user);
                    }
                    saveBatch(entities);
                    return entities.size();
                }, validator);
    }

    @Override
    public SseEmitter importUsersStream(MultipartFile file) {
        SseEmitter emitter = new SseEmitter(0L);

        // 预加载：部门映射、已存在用户名、默认密码
        List<SysDept> allDepts = deptMapper.selectList(null);
        Map<String, Long> deptNameToId = new HashMap<>();
        for (SysDept dept : allDepts) {
            deptNameToId.put(dept.getDeptName(), dept.getId());
        }
        Set<String> existingUsernames = this.lambdaQuery()
                .select(SysUser::getUsername).list().stream()
                .map(SysUser::getUsername)
                .collect(Collectors.toSet());
        String defaultPassword = BCrypt.hashpw(
                configService.getConfigValue(SystemConstants.CONFIG_INIT_PASSWORD, SystemConstants.DEFAULT_INIT_PASSWORD),
                BCrypt.gensalt());

        new Thread(() -> {
            try (InputStream in = file.getInputStream()) {
                Set<String> processedUsernames = new HashSet<>();
                AtomicInteger successCount = new AtomicInteger(0);
                AtomicInteger errorCount = new AtomicInteger(0);
                AtomicInteger totalCount = new AtomicInteger(0);

                EasyExcel.read(in, UserExcelVO.class, new ReadListener<UserExcelVO>() {
                    @Override
                    public void invoke(UserExcelVO vo, AnalysisContext ctx) {
                        int rowNum = ctx.readRowHolder().getRowIndex() + 1;
                        totalCount.incrementAndGet();
                        String username = vo.getUsername();

                        try {
                            // 1. 用户名必填
                            if (username == null || username.isBlank()) {
                                throw new ServiceException("用户名为必填项");
                            }
                            // 2. 手机号格式（如果有值）
                            if (vo.getMobile() != null && !vo.getMobile().isBlank()
                                    && !MOBILE_PATTERN.matcher(vo.getMobile()).matches()) {
                                throw new ServiceException("手机号格式不正确");
                            }
                            // 3. 邮箱格式（如果有值）
                            if (vo.getEmail() != null && !vo.getEmail().isBlank()
                                    && !EMAIL_PATTERN.matcher(vo.getEmail()).matches()) {
                                throw new ServiceException("邮箱格式不正确");
                            }
                            // 4. 用户名去重：已在数据库中存在
                            if (existingUsernames.contains(username)) {
                                throw new ServiceException("用户『" + username + "』已存在，导入失败");
                            }
                            // 5. 用户名去重：前面已处理的行中重复
                            if (!processedUsernames.add(username)) {
                                throw new ServiceException("用户『" + username + "』在当前导入文件中重复");
                            }

                            // 字典翻译（男/女→1/2，正常/停用→0/1）
                            excelHelper.translateForImport(vo, UserExcelVO.class);

                            // 创建实体并单行入库
                            SysUser user = new SysUser();
                            user.setUsername(username);
                            user.setNickname(vo.getNickname());
                            user.setGender(Integer.parseInt(vo.getGender() != null && !vo.getGender().isEmpty() ? vo.getGender() : "0"));
                            user.setMobile(vo.getMobile());
                            user.setEmail(vo.getEmail());
                            user.setStatus(Integer.parseInt(vo.getStatus() != null && !vo.getStatus().isEmpty() ? vo.getStatus() : "0"));
                            user.setDeptId(deptNameToId.getOrDefault(vo.getDeptName(), 0L));
                            user.setRemark(vo.getRemark());
                            user.setPassword(defaultPassword);
                            save(user);

                            successCount.incrementAndGet();
                            sendSse(emitter, "row",
                                    buildRowJson(rowNum, "success", username, ""));
                        } catch (Exception e) {
                            errorCount.incrementAndGet();
                            try {
                                sendSse(emitter, "row",
                                        buildRowJson(rowNum, "error",
                                                username != null ? username : "",
                                                e.getMessage()));
                            } catch (Exception ignored) {
                                // emitter 已关闭
                            }
                        }
                    }

                    @Override
                    public void doAfterAllAnalysed(AnalysisContext ctx) {
                        try {
                            sendSse(emitter, "done",
                                    "{\"totalRows\":" + totalCount.get()
                                    + ",\"successCount\":" + successCount.get()
                                    + ",\"errorCount\":" + errorCount.get() + "}");
                        } catch (Exception ignored) {
                        }
                        emitter.complete();
                    }

                    @Override
                    public void onException(Exception e, AnalysisContext ctx) throws Exception {
                        errorCount.incrementAndGet();
                        try {
                            int rowNum = ctx.readRowHolder() != null
                                    ? ctx.readRowHolder().getRowIndex() + 1
                                    : totalCount.get() + 1;
                            sendSse(emitter, "row",
                                    buildRowJson(rowNum, "error", "",
                                            "行解析异常：" + e.getMessage()));
                        } catch (Exception ignored) {
                        }
                    }
                }).sheet().doRead();
            } catch (Exception e) {
                log.error("流式导入异常", e);
                try {
                    sendSse(emitter, "error", "{\"message\":\"" + escapeJson(e.getMessage()) + "\"}");
                } catch (Exception ignored) {
                }
                emitter.completeWithError(e);
            }
        }, "user-import-stream").start();

        return emitter;
    }

    @Override
    public void downloadUserTemplate(HttpServletResponse response) throws IOException {
        excelHelper.downloadTemplate(response, "用户导入模板", UserExcelVO.class,
                excelHelper.buildDictDropDownMap(UserExcelVO.class));
    }

    // ==================== 私有方法 ====================

    /** SSE 发送一行 JSON 事件 */
    private static void sendSse(SseEmitter emitter, String eventName, String data) throws IOException {
        emitter.send(SseEmitter.event().name(eventName).data(data));
    }

    /** 构建单行结果的 JSON */
    private static String buildRowJson(int rowNum, String status, String username, String message) {
        return "{\"rowNum\":" + rowNum
                + ",\"status\":\"" + status + "\""
                + ",\"username\":\"" + escapeJson(username) + "\""
                + ",\"message\":\"" + escapeJson(message) + "\"}";
    }

    /** JSON 字符串转义 */
    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 构建与 pageUsers 一致的查询条件 */
    private LambdaQueryWrapper<SysUser> buildQueryWrapper(UserQuery query) {
        return Wrappers.<SysUser>lambdaQuery()
                .like(StringUtils.hasText(query.getUsername()), SysUser::getUsername, query.getUsername())
                .like(StringUtils.hasText(query.getNickname()), SysUser::getNickname, query.getNickname())
                .like(StringUtils.hasText(query.getMobile()), SysUser::getMobile, query.getMobile())
                .eq(query.getDeptId() != null, SysUser::getDeptId, query.getDeptId())
                .eq(query.getPostId() != null, SysUser::getPostId, query.getPostId())
                .eq(query.getStatus() != null, SysUser::getStatus, query.getStatus());
    }

    /** SysUser → UserExcelVO（含部门名称翻译） */
    private UserExcelVO toExcelVO(SysUser user, Map<Long, String> deptNameMap) {
        UserExcelVO vo = new UserExcelVO();
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setGender(String.valueOf(user.getGender() != null ? user.getGender() : 0));
        vo.setMobile(user.getMobile());
        vo.setEmail(user.getEmail());
        vo.setStatus(String.valueOf(user.getStatus() != null ? user.getStatus() : 0));
        vo.setDeptName(deptNameMap.getOrDefault(user.getDeptId(), ""));
        vo.setRemark(user.getRemark());
        return vo;
    }

    /** 查询用户，不存在抛 2004 */
    private SysUser requireUser(Long userId) {
        SysUser user = getById(userId);
        if (user == null) {
            throw new ServiceException(SystemErrorCode.USER_NOT_FOUND);
        }
        return user;
    }

    /** 用户名唯一性校验（排除自身） */
    private void checkUsernameUnique(String username, Long excludeId) {
        long count = count(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, username)
                .ne(excludeId != null, SysUser::getId, excludeId));
        if (count > 0) {
            throw new ServiceException(SystemErrorCode.USERNAME_EXISTS);
        }
    }

    /** 超级管理员不允许被停用 */
    private void checkSuperAdminStatusChange(SysUser exist, UserSaveRequest request) {
        if (CommonConstants.SUPER_ADMIN_ID.equals(exist.getId())
                && request.getStatus() != null
                && !Objects.equals(request.getStatus(), exist.getStatus())) {
            throw new ServiceException(SystemErrorCode.SUPER_ADMIN_FORBIDDEN);
        }
    }

    /** 重建用户-角色关联（先删后插） */
    private void rebuildUserRoles(Long userId, List<Long> roleIds) {
        userRoleMapper.delete(Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, userId));
        if (roleIds != null && !roleIds.isEmpty()) {
            roleIds.forEach(roleId -> userRoleMapper.insert(new SysUserRole(userId, roleId)));
        }
    }
}
