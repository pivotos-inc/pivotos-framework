package com.pivotos.file.service;

import com.pivotos.common.core.page.PageResult;
import com.pivotos.file.api.dto.PresignResult;
import com.pivotos.file.domain.dto.FilePageQuery;
import com.pivotos.file.domain.dto.FileRegisterRequest;
import com.pivotos.file.domain.vo.SysFileVO;

/** 文件存储服务 */
public interface FileService {

    /**
     * 生成预签名直传地址
     *
     * @param filename 原始文件名（用于扩展名校验与对象键前缀）
     */
    PresignResult presignUpload(String filename);

    /**
     * 由对象键还原访问地址
     */
    String buildFileUrl(String objectKey);

    /**
     * 为已存储对象生成预签名下载地址（GET，限时有效，用于私有桶回显）
     *
     * @param objectKeyOrUrl 对象键，或历史落库的完整 fileUrl
     */
    String presignDownload(String objectKeyOrUrl);

    /**
     * 直传完成回调登记（sys_file 元数据落库，S25 2.1-F7）
     *
     * @return 元数据记录 ID
     */
    Long register(FileRegisterRequest request);

    /**
     * 文件元数据分页（多租户行级隔离由租户插件自动过滤）
     */
    PageResult<SysFileVO> page(FilePageQuery query);

    /**
     * 删除文件（存储对象 + 元数据同删）
     *
     * @param id 元数据记录 ID
     */
    void delete(Long id);
}
