package com.pivotos.ai.kb.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 知识库支持的向量存储类型。
 * S58 实现 simple（内存 + JSON 快照）与 milvus；pgvector / qdrant 预留扩展点。
 */
@Getter
@AllArgsConstructor
public enum KbVectorStoreTypeEnum {

    SIMPLE("simple", "SimpleVectorStore（内存 + JSON 快照）"),
    MILVUS("milvus", "Milvus"),
    PGVECTOR("pgvector", "pgvector（预留）"),
    QDRANT("qdrant", "Qdrant（预留）");

    private final String value;
    private final String label;

    public static KbVectorStoreTypeEnum of(String value) {
        if (value == null) {
            return null;
        }
        for (KbVectorStoreTypeEnum e : values()) {
            if (e.value.equalsIgnoreCase(value)) {
                return e;
            }
        }
        return null;
    }
}
