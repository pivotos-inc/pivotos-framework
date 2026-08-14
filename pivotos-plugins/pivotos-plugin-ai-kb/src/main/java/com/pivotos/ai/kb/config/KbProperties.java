package com.pivotos.ai.kb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 知识库配置（{@code pivotos.kb.*}）。
 */
@Data
@ConfigurationProperties(prefix = "pivotos.kb")
public class KbProperties {

    /** 向量存储全局配置 */
    private VectorStore vectorStore = new VectorStore();

    @Data
    public static class VectorStore {

        /** 默认向量存储类型：simple / milvus / pgvector / qdrant */
        private String type = "simple";

        /** SimpleVectorStore 快照持久化目录 */
        private Simple simple = new Simple();
    }

    @Data
    public static class Simple {

        /** JSON 快照文件存放目录（相对于工作目录或绝对路径） */
        private String dataPath = "./data/kb/simple";

        /** 快照文件名 */
        private String fileName = "kb-store.json";
    }
}
