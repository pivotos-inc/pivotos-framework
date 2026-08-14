package com.pivotos.ai.kb.splitter;

import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 语义感知分块器：按段落 → 句子边界分割，支持重叠窗口。
 *
 * <p>替代 Spring AI {@code TokenTextSplitter}（不支持 chunkOverlap）：
 * <ol>
 *   <li>段落级分割：按双换行符拆分原文为段落</li>
 *   <li>句子级分割：段落内按中英文句子边界（。！？；.!?; + 换行）拆分</li>
 *   <li>块合并：句子按 chunkSize 累加合并为块（累加长度 ≤ chunkSize）</li>
 *   <li>重叠窗口：相邻块之间保留 chunkOverlap 个字符的重叠（从上一块末尾取）</li>
 *   <li>硬切兜底：超长单句（&gt; chunkSize）在 chunkSize 处硬切</li>
 * </ol>
 *
 * <p>每个块继承原文档全部元数据 + {@code chunk_index}（从 0 开始的序号）。
 */
public class SemanticChunkSplitter implements Function<List<Document>, List<Document>> {

    private final int chunkSize;
    private final int chunkOverlap;

    public SemanticChunkSplitter(int chunkSize, int chunkOverlap) {
        this.chunkSize = Math.max(1, chunkSize);
        // overlap 上限为 chunkSize 的一半，避免过度重叠
        this.chunkOverlap = Math.min(Math.max(0, chunkOverlap), this.chunkSize / 2);
    }

    @Override
    public List<Document> apply(List<Document> documents) {
        List<Document> chunks = new ArrayList<>();
        for (Document doc : documents) {
            String text = doc.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            Map<String, Object> baseMetadata = new HashMap<>(doc.getMetadata());
            List<String> chunkTexts = split(text);
            for (int i = 0; i < chunkTexts.size(); i++) {
                Map<String, Object> chunkMeta = new HashMap<>(baseMetadata);
                chunkMeta.put("chunk_index", i);
                chunks.add(new Document(chunkTexts.get(i), chunkMeta));
            }
        }
        return chunks;
    }

    // ---------- 分块算法 ----------

    private List<String> split(String text) {
        // 1. 段落级分割
        List<String> paragraphs = splitParagraphs(text);
        // 2. 句子级分割
        List<String> sentences = new ArrayList<>();
        for (String para : paragraphs) {
            sentences.addAll(splitSentences(para));
        }
        // 3. 块合并 + 重叠
        return mergeWithOverlap(sentences);
    }

    /** 按双换行符（允许中间有空白行）拆分段落 */
    private List<String> splitParagraphs(String text) {
        List<String> paragraphs = new ArrayList<>();
        String[] parts = text.split("\\n\\s*\\n");
        for (String part : parts) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                paragraphs.add(trimmed);
            }
        }
        return paragraphs;
    }

    /** 按句子边界字符拆分，保留标点符号在句尾 */
    private List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);
            if (isSentenceBoundary(c)) {
                String trimmed = current.toString().strip();
                if (!trimmed.isEmpty()) {
                    sentences.add(trimmed);
                }
                current.setLength(0);
            }
        }
        String remaining = current.toString().strip();
        if (!remaining.isEmpty()) {
            sentences.add(remaining);
        }
        return sentences;
    }

    /** 中英文句子边界：。！？；.!?; + 换行 */
    private boolean isSentenceBoundary(char c) {
        return c == '\u3002' || c == '\uff01' || c == '\uff1f' || c == '\uff1b'
                || c == '.' || c == '!' || c == '?' || c == ';'
                || c == '\n' || c == '\r';
    }

    /**
     * 句子合并为块：逐句累加，超限时收尾并取 overlap 作为下一块前缀。
     * 超长单句在 chunkSize 处硬切（带 overlap）。
     */
    private List<String> mergeWithOverlap(List<String> sentences) {
        List<String> chunks = new ArrayList<>();
        if (sentences.isEmpty()) {
            return chunks;
        }

        StringBuilder current = new StringBuilder();

        for (String sentence : sentences) {
            // Case 1: 超长句子（单句 > chunkSize），需要硬切
            if (sentence.length() > chunkSize) {
                // 先收尾当前块
                if (current.length() > 0) {
                    chunks.add(current.toString().strip());
                    current = new StringBuilder(extractOverlap(current.toString()));
                }
                // 硬切长句子
                String remaining = sentence;
                while (remaining.length() + current.length() > chunkSize) {
                    int take = Math.max(1, chunkSize - current.length());
                    if (take > remaining.length()) {
                        take = remaining.length();
                    }
                    current.append(remaining, 0, take);
                    chunks.add(current.toString().strip());
                    String overlap = extractOverlap(current.toString());
                    current = new StringBuilder(overlap);
                    remaining = remaining.substring(take);
                }
                if (!remaining.isEmpty()) {
                    if (current.length() > 0) {
                        current.append(' ');
                    }
                    current.append(remaining);
                }
                continue;
            }

            // Case 2: 正常句子，累加会超限 → 收尾当前块
            if (current.length() > 0 && current.length() + sentence.length() + 1 > chunkSize) {
                chunks.add(current.toString().strip());
                String overlap = extractOverlap(current.toString());
                current = new StringBuilder(overlap);
            }

            // 累加句子
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(sentence);
        }

        // 收尾
        if (current.length() > 0) {
            chunks.add(current.toString().strip());
        }

        return chunks;
    }

    /** 从文本末尾取 chunkOverlap 个字符作为下一块的重叠前缀 */
    private String extractOverlap(String text) {
        if (chunkOverlap <= 0 || text.length() <= chunkOverlap) {
            return "";
        }
        return text.substring(text.length() - chunkOverlap);
    }
}
