package com.pivotos.ai.kb.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 知识库文档向量化状态。
 */
@Getter
@AllArgsConstructor
public enum KbDocStatusEnum {

    PENDING(0, "待处理"),
    INDEXING(1, "向量化中"),
    COMPLETED(2, "已完成"),
    FAILED(3, "失败");

    private final Integer value;
    private final String label;

    public static KbDocStatusEnum of(Integer value) {
        if (value == null) {
            return null;
        }
        for (KbDocStatusEnum e : values()) {
            if (e.value.equals(value)) {
                return e;
            }
        }
        return null;
    }
}
