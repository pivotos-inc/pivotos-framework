package com.pivotos.generator.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.pivotos.generator.domain.entity.GenTable;
import com.pivotos.generator.domain.entity.GenTableColumn;

import java.util.List;
import java.util.Map;

/**
 * 代码生成器服务接口
 */
public interface GeneratorService {

    /**
     * 查询数据库表列表（information_schema）
     */
    IPage<Map<String, Object>> selectDbTableList(IPage<Map<String, Object>> page,
                                                  String tableName, String tableComment);

    /**
     * 导入表结构
     */
    void importTable(List<String> tableNames, String packageName, String moduleName,
                     String businessName, String functionName, String functionAuthor);

    /**
     * 分页查询已导入的生成表
     */
    IPage<GenTable> selectGenTableList(IPage<GenTable> page, String tableName, String tableComment);

    /**
     * 查询生成表详情
     */
    GenTable selectGenTableById(Long id);

    /**
     * 删除生成表
     */
    void deleteGenTable(List<Long> ids);

    /**
     * 同步数据库表字段
     */
    void synchDb(Long id);

    /**
     * 查询表的字段列表
     */
    List<GenTableColumn> selectGenTableColumnListByTableId(Long tableId);

    /**
     * 更新字段配置
     */
    void updateGenTableColumn(GenTableColumn column);

    /**
     * 更新表配置（模板类型 / 树 / 主子 / fk 之外的表级属性，S50 / 2.4-F1）
     */
    void updateGenTable(GenTable table);

    /**
     * 预览代码
     * @return Map<模板文件名, 生成代码内容>
     */
    Map<String, String> previewCode(Long tableId);

    /**
     * 生成代码（zip 下载）
     * @return zip 文件字节数组
     */
    byte[] downloadCode(Long tableId);

    /**
     * 生成代码并写入工程
     */
    void generateToProject(Long tableId);
}
