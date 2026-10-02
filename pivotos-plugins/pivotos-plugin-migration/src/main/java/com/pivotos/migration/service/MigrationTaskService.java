package com.pivotos.migration.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.pivotos.migration.domain.entity.MigrationTask;
import com.pivotos.migration.domain.enums.MigrationFileType;
import org.springframework.web.multipart.MultipartFile;

/**
 * 迁移任务 Service。
 */
public interface MigrationTaskService extends IService<MigrationTask> {

    /**
     * 上传源码压缩包并建立文件索引。
     *
     * @param taskId   任务 ID
     * @param fileType 文件类型（后端 / 前端）
     * @param file     ZIP 压缩包
     * @return 索引到的文件数量
     */
    Long uploadSourceArchive(Long taskId, MigrationFileType fileType, MultipartFile file);

    /**
     * 解析任务下已上传的源码文件，生成 IR 节点与产物。
     *
     * @param taskId 任务 ID
     * @return 解析出的 IR 节点数量
     */
    Long parseSourceCode(Long taskId);

    /**
     * 调用 AI 对 IR 节点进行架构分析，生成分析报告写回任务。
     *
     * @param taskId 任务 ID
     * @return 分析报告摘要（JSON 字符串）
     */
    String analyzeSourceCode(Long taskId);

    /**
     * 调用 AI 生成迁移步骤计划，创建 migration_step 记录，写回 migration_plan。
     * 前置状态：ANALYZED(4)，后置状态：PLANNED(6)。
     *
     * @param taskId 任务 ID
     * @return 计划生成摘要（JSON 字符串）
     */
    String generateMigrationPlan(Long taskId);

    /**
     * 计划复位（L10 清偿：替代「手工 DELETE migration_step / migration_artifact 再回置状态」）。
     * 清除该任务全部步骤与「未落盘」产物，把任务状态回退到 ANALYZED(4) 并清空计划字段。
     *
     * <p>前置状态：ANALYZED(4) / PLANNED(6) / ROLLED_BACK(12)；执行中与已完成不允许复位。
     * 已落盘（{@code applied=true}）的产物行刻意保留，否则 rollback 会再也找不到对应文件。
     *
     * @param taskId 任务 ID
     * @return 清除的步骤 + 未落盘产物条数
     */
    int resetPlan(Long taskId);

    /**
     * 完成任务：校验任务状态为 EXECUTED(8) 且全部步骤已 COMPLETED，推进 → COMPLETED(9)。
     *
     * @param taskId 任务 ID
     */
    void completeTask(Long taskId);
}
