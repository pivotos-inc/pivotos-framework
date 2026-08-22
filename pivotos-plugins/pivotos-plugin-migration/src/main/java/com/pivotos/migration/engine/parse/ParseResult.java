package com.pivotos.migration.engine.parse;

import com.pivotos.migration.domain.entity.MigrationArtifact;
import com.pivotos.migration.domain.entity.MigrationIrNode;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 单文件解析结果。
 */
@Data
public class ParseResult {

    /** 解析出的 IR 节点 */
    private List<MigrationIrNode> irNodes = new ArrayList<>();

    /** 解析出的产物 */
    private List<MigrationArtifact> artifacts = new ArrayList<>();

    /** 解析日志（用于后续记录到 migration_log） */
    private List<String> logs = new ArrayList<>();

    public void addIrNode(MigrationIrNode node) {
        irNodes.add(node);
    }

    public void addArtifact(MigrationArtifact artifact) {
        artifacts.add(artifact);
    }

    public void addLog(String log) {
        logs.add(log);
    }
}
