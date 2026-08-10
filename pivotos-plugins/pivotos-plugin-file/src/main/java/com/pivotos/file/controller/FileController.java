package com.pivotos.file.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.pivotos.common.core.enums.error.GlobalErrorCode;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.common.core.result.R;
import com.pivotos.file.api.dto.PresignResult;
import com.pivotos.file.domain.dto.FilePageQuery;
import com.pivotos.file.domain.dto.FileRegisterRequest;
import com.pivotos.file.domain.vo.SysFileVO;
import com.pivotos.file.service.FileService;
import com.pivotos.starter.auth.account.StpSysUtil;
import com.pivotos.starter.core.context.LoginContext;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文件预签名与元数据接口。
 * presign / presign-download / register 三端通用（sys / app / wx-mini，
 * 登录态由 LoginContextFilter 统一解析，只校验"已登录"）；
 * page / delete 是后台管理能力，走 file:file:* 权限（sys 账号体系）。
 */
@RestController
@RequestMapping("/file")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

    /** 预签名直传地址（PUT） */
    @GetMapping("/presign")
    public R<PresignResult> presign(@RequestParam String filename) {
        if (!LoginContext.isLogin()) {
            throw new ServiceException(GlobalErrorCode.UNAUTHORIZED);
        }
        return R.ok(fileService.presignUpload(filename));
    }

    /** 预签名下载地址（GET，私有桶回显：key 可传对象键或历史完整 fileUrl） */
    @GetMapping("/presign-download")
    public R<String> presignDownload(@RequestParam String key) {
        if (!LoginContext.isLogin()) {
            throw new ServiceException(GlobalErrorCode.UNAUTHORIZED);
        }
        return R.ok(fileService.presignDownload(key));
    }

    /** 直传完成回调登记（sys_file 元数据落库，三端通用） */
    @PostMapping("/register")
    public R<Long> register(@Validated @RequestBody FileRegisterRequest request) {
        if (!LoginContext.isLogin()) {
            throw new ServiceException(GlobalErrorCode.UNAUTHORIZED);
        }
        return R.ok(fileService.register(request));
    }

    /** 文件元数据分页（后台管理，多租户行级隔离自动生效） */
    @GetMapping("/page")
    @SaCheckPermission(value = "file:file:list", type = StpSysUtil.TYPE)
    public R<PageResult<SysFileVO>> page(FilePageQuery query) {
        return R.ok(fileService.page(query));
    }

    /** 删除文件（存储对象 + 元数据同删） */
    @DeleteMapping("/{id}")
    @SaCheckPermission(value = "file:file:remove", type = StpSysUtil.TYPE)
    public R<Void> delete(@PathVariable Long id) {
        fileService.delete(id);
        return R.ok();
    }
}
