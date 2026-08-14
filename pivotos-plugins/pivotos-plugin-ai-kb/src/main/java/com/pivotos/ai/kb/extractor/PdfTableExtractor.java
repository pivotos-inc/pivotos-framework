package com.pivotos.ai.kb.extractor;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 表格提取器：使用 Apache PDFBox 3.x 提取 PDF 中的表格内容，转为 Markdown 格式。
 *
 * <p>仅返回检测到的表格内容（Markdown 格式），非表格文本由 Tika 负责提取，
 * 避免与 Tika 文本重复。检测启发式：按页提取文本后，连续行中列数一致（≥2 列）、
 * 且行数≥2 的行块视为表格。
 */
@Slf4j
@Component
public class PdfTableExtractor {

    /**
     * 从 PDF 提取表格内容并转为 Markdown 格式。
     *
     * @param pdfUrl PDF 的可访问 URL（HTTP 预签名或本地路径）
     * @return Markdown 格式的表格文本，无表格或提取失败时返回空字符串
     */
    public String extractTables(String pdfUrl) {
        if (!StringUtils.hasText(pdfUrl)) {
            return "";
        }
        try (InputStream is = openStream(pdfUrl)) {
            byte[] bytes = is.readAllBytes();
            return extractTablesFromBytes(bytes);
        } catch (Exception e) {
            log.warn("[PivotOS-KB] PDF 表格提取失败: {}", e.getMessage());
            return "";
        }
    }

    private String extractTablesFromBytes(byte[] pdfBytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String fullText = stripper.getText(doc);
            return detectAndConvertTables(fullText);
        }
    }

    /**
     * 检测文本中类似表格的内容（连续行、列数一致、≥2 列）并转为 Markdown 表格。
     * 仅返回检测到的 Markdown 表格，非表格行丢弃。
     */
    private String detectAndConvertTables(String text) {
        String[] lines = text.split("\n");
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < lines.length) {
            String[] cols = splitColumns(lines[i]);
            if (cols.length >= 2) {
                int expectedCols = cols.length;
                List<String[]> rows = new ArrayList<>();
                while (i < lines.length) {
                    String[] row = splitColumns(lines[i]);
                    if (row.length == expectedCols) {
                        rows.add(row);
                        i++;
                    } else {
                        break;
                    }
                }
                if (rows.size() >= 2) {
                    result.append(toMarkdownTable(rows)).append('\n');
                }
            } else {
                i++;
            }
        }
        return result.toString();
    }

    /** 按连续空格（≥2）拆分为列 */
    private String[] splitColumns(String line) {
        String trimmed = line.strip();
        if (trimmed.isEmpty()) {
            return new String[0];
        }
        return trimmed.split("\\s{2,}");
    }

    /** 将行列表转为 Markdown 表格 */
    private String toMarkdownTable(List<String[]> rows) {
        StringBuilder sb = new StringBuilder();
        int cols = rows.get(0).length;
        // header
        sb.append("| ").append(String.join(" | ", rows.get(0))).append(" |\n");
        // separator
        sb.append("|");
        for (int c = 0; c < cols; c++) {
            sb.append(" --- |");
        }
        sb.append("\n");
        // data rows
        for (int r = 1; r < rows.size(); r++) {
            sb.append("| ").append(String.join(" | ", rows.get(r))).append(" |\n");
        }
        return sb.toString();
    }

    private InputStream openStream(String url) throws IOException {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return new URL(url).openStream();
        }
        return Files.newInputStream(Path.of(url));
    }
}
