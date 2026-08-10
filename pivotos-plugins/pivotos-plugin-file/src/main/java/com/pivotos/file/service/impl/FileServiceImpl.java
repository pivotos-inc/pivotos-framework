package com.pivotos.file.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.file.api.dto.PresignResult;
import com.pivotos.file.api.enums.FileErrorCode;
import com.pivotos.file.config.FileProperties;
import com.pivotos.file.domain.dto.FilePageQuery;
import com.pivotos.file.domain.dto.FileRegisterRequest;
import com.pivotos.file.domain.entity.SysFile;
import com.pivotos.file.domain.vo.SysFileVO;
import com.pivotos.file.mapper.SysFileMapper;
import com.pivotos.file.service.FileService;
import com.pivotos.file.storage.StorageStrategy;
import com.pivotos.starter.core.context.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 文件服务实现：S25 起面向 StorageStrategy 编程（minio / s3 条件装配二选一）。
 * StorageStrategy 用 ObjectProvider 延迟解析：未配置存储时报 4020，
 * 不影响插件其余部分装配（条件装配纪律）。
 * <p>业务语义（对象键规则 / 扩展名白名单 / 历史 fileUrl 归一化 / sys_file 元数据）
 * 留在本层，不下沉存储实现。
 */
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    private static final DateTimeFormatter DATE_DIR = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 平台/默认租户 ID（与 ColumnTenantStrategy 无上下文兜底值一致） */
    private static final long PLATFORM_TENANT_ID = 0L;

    private final ObjectProvider<StorageStrategy> storageProvider;
    private final FileProperties properties;
    private final SysFileMapper sysFileMapper;

    @Override
    public PresignResult presignUpload(String filename) {
        StorageStrategy storage = requireStorage();
        String ext = extractAndCheckExtension(filename);
        String objectKey = "upload/" + LocalDate.now().format(DATE_DIR)
                + "/" + UUID.randomUUID().toString().replace("-", "") + "." + ext;
        String uploadUrl = storage.presignUpload(objectKey);
        return new PresignResult(objectKey, uploadUrl,
                storage.buildUrl(objectKey), properties.getPresignExpireSeconds());
    }

    @Override
    public String buildFileUrl(String objectKey) {
        return requireStorage().buildUrl(objectKey);
    }

    @Override
    public String presignDownload(String objectKeyOrUrl) {
        if (!StringUtils.hasText(objectKeyOrUrl)) {
            throw new ServiceException(FileErrorCode.FILE_KEY_EMPTY);
        }
        StorageStrategy storage = requireStorage();
        return storage.presignDownload(normalizeObjectKey(objectKeyOrUrl));
    }

    @Override
    public Long register(FileRegisterRequest request) {
        SysFile file = new SysFile();
        file.setObjectKey(request.getObjectKey().trim());
        file.setOriginalName(StringUtils.hasText(request.getOriginalName())
                ? request.getOriginalName() : request.getObjectKey());
        file.setFileSize(request.getFileSize());
        file.setMd5(request.getMd5());
        file.setContentType(request.getContentType());
        file.setStorageType(properties.effectiveStorageLabel());
        file.setBucket(properties.getBucket());
        // 审计填充仅在有租户上下文时写 tenantId，无上下文（单租户/平台视角）显式回落 0，
        // 否则撞 sys_file.tenant_id NOT NULL 约束（ai 插件同惯例）
        file.setTenantId(currentTenantId());
        sysFileMapper.insert(file);
        return file.getId();
    }

    @Override
    public PageResult<SysFileVO> page(FilePageQuery query) {
        LambdaQueryWrapper<SysFile> wrapper = new LambdaQueryWrapper<SysFile>()
                .like(StringUtils.hasText(query.getOriginalName()),
                        SysFile::getOriginalName, query.getOriginalName())
                .eq(StringUtils.hasText(query.getStorageType()),
                        SysFile::getStorageType, query.getStorageType())
                .orderByDesc(SysFile::getCreateTime);
        Page<SysFile> page = sysFileMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        List<SysFileVO> records = page.getRecords().stream().map(item -> {
            SysFileVO vo = new SysFileVO();
            BeanUtils.copyProperties(item, vo);
            return vo;
        }).toList();
        return new PageResult<>(records, page.getTotal(), (int) page.getCurrent(), (int) page.getSize());
    }

    @Override
    public void delete(Long id) {
        SysFile file = sysFileMapper.selectById(id);
        if (file == null) {
            throw new ServiceException(FileErrorCode.FILE_NOT_FOUND);
        }
        // 先删存储对象（失败抛 4006 保留元数据可重试），再逻辑删元数据
        requireStorage().delete(file.getObjectKey());
        sysFileMapper.deleteById(id);
    }

    /**
     * 归一化对象键：兼容历史落库的完整 fileUrl（{publicUrl}/{bucket}/{objectKey}）与裸对象键。
     */
    private String normalizeObjectKey(String objectKeyOrUrl) {
        String value = objectKeyOrUrl.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) {
            String bucketPrefix = "/" + properties.getBucket() + "/";
            int idx = value.indexOf(bucketPrefix);
            if (idx >= 0) {
                value = value.substring(idx + bucketPrefix.length());
            } else {
                // 兑底：去掉协议与主机，取 path 部分
                int schemeEnd = value.indexOf("://");
                int pathStart = value.indexOf('/', schemeEnd + 3);
                value = pathStart >= 0 ? value.substring(pathStart + 1) : value;
            }
        }
        // 去掉可能携带的查询串（历史 URL 若带签名参数）
        int q = value.indexOf('?');
        if (q >= 0) {
            value = value.substring(0, q);
        }
        // 去掉前导斜杠
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value;
    }

    /** 当前租户 ID（无租户上下文 = 平台视角） */
    private long currentTenantId() {
        Long tenantId = TenantContext.get();
        return tenantId == null ? PLATFORM_TENANT_ID : tenantId;
    }

    private StorageStrategy requireStorage() {
        StorageStrategy storage = storageProvider.getIfAvailable();
        if (storage == null) {
            throw new ServiceException(FileErrorCode.FILE_STORAGE_NOT_CONFIGURED);
        }
        return storage;
    }

    /** 提取并校验扩展名（白名单，小写比较） */
    private String extractAndCheckExtension(String filename) {
        if (!StringUtils.hasText(filename)) {
            throw new ServiceException(FileErrorCode.FILENAME_EMPTY);
        }
        int dot = filename.lastIndexOf('.');
        String ext = dot >= 0 ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (!properties.getAllowedExtensions().contains(ext)) {
            throw new ServiceException(FileErrorCode.FILE_TYPE_NOT_ALLOWED);
        }
        return ext;
    }
}
