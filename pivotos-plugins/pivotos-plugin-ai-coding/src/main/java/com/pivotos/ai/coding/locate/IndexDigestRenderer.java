package com.pivotos.ai.coding.locate;

import com.pivotos.migration.api.codeindex.CodeFileEntry;
import com.pivotos.migration.api.codeindex.CodeIndexSnapshot;

/**
 * 索引摘要渲染器：快照 → 粗定位喂入的一行式索引文本。
 *
 * <p>相比 S107 spike 的「路径|类名|首行注释」三字段，本版多喂两个字段：
 * <ul>
 *   <li>{@code LAYER}：分层标签，把「业务逻辑默认落在实现层」这条工程约定显式喂给模型，
 *       直接对冲 spike K2 的「上层文件偏好」；</li>
 *   <li>{@code symbols}：该文件的关键符号名，给模型一个确定性召回线索。</li>
 * </ul>
 *
 * @author PivotOS
 * @since 2.14.0（S110 A4-1）
 */
public final class IndexDigestRenderer {

    private IndexDigestRenderer() {
    }

    /**
     * 渲染索引摘要（每文件一行：路径|分层|模块|类型名|摘要|符号）。
     *
     * @param snapshot 索引快照
     * @return 索引文本
     */
    public static String render(CodeIndexSnapshot snapshot) {
        if (snapshot == null || snapshot.entries().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(snapshot.entries().size() * 128);
        for (CodeFileEntry entry : snapshot.entries()) {
            sb.append(entry.relativePath())
                    .append('|').append(entry.layer().name())
                    .append('|').append(entry.moduleName())
                    .append('|').append(entry.typeName())
                    .append('|').append(entry.summary())
                    .append('|').append(String.join(",", entry.symbols()))
                    .append('\n');
        }
        return sb.toString();
    }
}
