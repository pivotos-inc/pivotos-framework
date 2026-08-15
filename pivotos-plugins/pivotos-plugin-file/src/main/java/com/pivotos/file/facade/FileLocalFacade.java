package com.pivotos.file.facade;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.pivotos.file.api.dto.FileStatsDTO;
import com.pivotos.file.api.dto.PresignResult;
import com.pivotos.file.api.facade.IFileFacade;
import com.pivotos.file.domain.entity.SysFile;
import com.pivotos.file.mapper.SysFileMapper;
import com.pivotos.file.service.FileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/** IFileFacade 本地实现（单体模式直接调用，微服务模式后续换 RPC 代理） */
@Component
@RequiredArgsConstructor
public class FileLocalFacade implements IFileFacade {

    private final FileService fileService;
    private final SysFileMapper fileMapper;

    @Override
    public PresignResult presignUpload(String filename) {
        return fileService.presignUpload(filename);
    }

    @Override
    public String buildFileUrl(String objectKey) {
        return fileService.buildFileUrl(objectKey);
    }

    @Override
    public String presignDownload(String objectKeyOrUrl) {
        return fileService.presignDownload(objectKeyOrUrl);
    }

    @Override
    public FileStatsDTO storageStats() {
        FileStatsDTO dto = new FileStatsDTO();
        QueryWrapper<SysFile> wrapper = new QueryWrapper<>();
        wrapper.select("COUNT(*) AS cnt", "COALESCE(SUM(file_size), 0) AS total_bytes");
        Map<String, Object> row = fileMapper.selectMaps(wrapper).stream().findFirst().orElse(Map.of());
        dto.setFileCount(((Number) row.getOrDefault("cnt", 0L)).longValue());
        dto.setTotalBytes(((Number) row.getOrDefault("total_bytes", 0L)).longValue());
        return dto;
    }
}
