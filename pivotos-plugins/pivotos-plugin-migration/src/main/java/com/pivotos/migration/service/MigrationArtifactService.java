package com.pivotos.migration.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.pivotos.migration.domain.entity.MigrationArtifact;

/**
 * 迁移产物 Service。
 */
public interface MigrationArtifactService extends IService<MigrationArtifact> {

    /**
     * 将产物内容落盘到目标目录（幂等，重复应用覆盖写）。
     *
     * @param artifactId 产物 ID
     */
    void applyArtifact(Long artifactId);

    /**
     * 撤销产物落盘：删除已写入的目标文件。
     *
     * @param artifactId 产物 ID
     */
    void unapplyArtifact(Long artifactId);

    /**
     * 批量落盘指定步骤的全部产物。
     *
     * @param stepId 步骤 ID
     * @return 成功落盘的产物数
     */
    Long applyStep(Long stepId);

    /**
     * 任务级回滚：删除任务 target 目录下全部已落盘文件，任务置为已回滚。
     *
     * @param taskId 任务 ID
     * @return 删除的文件数
     */
    Long rollbackTask(Long taskId);
}
