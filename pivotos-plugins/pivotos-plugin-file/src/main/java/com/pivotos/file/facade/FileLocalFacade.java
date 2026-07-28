package com.pivotos.file.facade;

import com.pivotos.file.api.dto.PresignResult;
import com.pivotos.file.api.facade.IFileFacade;
import com.pivotos.file.service.FileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** IFileFacade 本地实现（单体模式直接调用，微服务模式后续换 RPC 代理） */
@Component
@RequiredArgsConstructor
public class FileLocalFacade implements IFileFacade {

    private final FileService fileService;

    @Override
    public PresignResult presignUpload(String filename) {
        return fileService.presignUpload(filename);
    }

    @Override
    public String buildFileUrl(String objectKey) {
        return fileService.buildFileUrl(objectKey);
    }
}
