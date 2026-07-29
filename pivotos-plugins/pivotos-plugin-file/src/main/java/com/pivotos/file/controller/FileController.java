package com.pivotos.file.controller;

import com.pivotos.common.core.enums.error.GlobalErrorCode;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.result.R;
import com.pivotos.file.api.dto.PresignResult;
import com.pivotos.file.service.FileService;
import com.pivotos.starter.core.context.LoginContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文件预签名接口（sys / app / wx-mini 三账号体系通用：
 * 登录态由 LoginContextFilter 统一解析，这里只校验"已登录"）。
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
}
