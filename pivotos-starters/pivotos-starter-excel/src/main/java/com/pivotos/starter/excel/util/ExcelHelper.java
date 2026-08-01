package com.pivotos.starter.excel.util;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.pivotos.starter.excel.annotation.DictExcelProperty;
import com.pivotos.starter.excel.config.ExcelProperties;
import com.pivotos.starter.excel.function.BatchSaveFunction;
import com.pivotos.starter.excel.function.PageFetchFunction;
import com.pivotos.starter.excel.translator.DictTranslator;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Excel 导入导出工具：分页流式导出 + 单行校验分批导入 + 字典双向翻译 + 模板下载。
 * <p>
 * 字典翻译通过 {@link DictExcelProperty} 注解驱动，无需 EasyExcel Converter。
 * 仅在 DTO 字段上用 {@code @DictExcelProperty(dictType = "xxx")} 标注即可。
 * </p>
 *
 * @author PivotOS Team
 * @since 2.1.0
 */
@Slf4j
public final class ExcelHelper {

    private final DictTranslator translator;
    private final ExcelProperties properties;

    // 缓存：Class → 字典字段信息列表（避免重复反射）
    private static final Map<Class<?>, List<DictFieldInfo>> DICT_FIELD_CACHE = new ConcurrentHashMap<>();

    public ExcelHelper(DictTranslator translator, ExcelProperties properties) {
        this.translator = translator;
        this.properties = properties;
    }

    // ==================== 导出 ====================

    /**
     * 分页流式导出 Excel：从业务方按页拉取数据 → 字典翻译 → EasyExcel 流式写入 response。
     *
     * @param response HttpServletResponse（不关闭流，由框架管理）
     * @param filename 下载文件名（不含扩展名）
     * @param clazz    导出 DTO 的 Class（用于解析列头 + 字典注解）
     * @param fetcher  分页数据源
     */
    public <T> void export(HttpServletResponse response, String filename,
                           Class<T> clazz, PageFetchFunction<T> fetcher) throws IOException {
        setExportResponseHeaders(response, filename);
        int pageNum = 1;
        int pageSize = properties.getExportPageSize();

        try (ServletOutputStream out = response.getOutputStream();
             ExcelWriter writer = EasyExcel.write(out, clazz).build()) {
            WriteSheet sheet = EasyExcel.writerSheet(filename).build();
            while (true) {
                List<T> page = fetcher.fetch(pageNum++, pageSize);
                if (page == null || page.isEmpty()) {
                    break;
                }
                // 导出翻译：dict value → label
                for (T row : page) {
                    translateForExport(row, clazz);
                }
                writer.write(page, sheet);
            }
            writer.finish();
        }
        log.info("Excel 导出完成：{}，共 {} 页", filename, pageNum - 1);
    }

    // ==================== 导入 ====================

    /**
     * 导入 Excel：EasyExcel 流式读取 → 逐行字典翻译 → 单行校验 → 分批次调用 saver 入库。
     *
     * @param file  上传的 Excel 文件
     * @param clazz 导入 DTO 的 Class
     * @param saver 批量入库回调
     * @return 导入结果（成功行 + 错误行回执）
     */
    public <T> ExcelImportResult<T> importExcel(MultipartFile file, Class<T> clazz,
                                                 BatchSaveFunction<T> saver) throws IOException {
        try (InputStream in = file.getInputStream()) {
            ImportListener<T> listener = new ImportListener<>(clazz, saver, translator, properties);
            EasyExcel.read(in, clazz, listener).sheet().doRead();
            return new ExcelImportResult<>(listener.successRows, listener.errors);
        }
    }

    // ==================== 模板下载 ====================

    /**
     * 下载 Excel 导入模板（仅列头，无数据行）。
     *
     * @param response HttpServletResponse
     * @param filename 文件名
     * @param clazz    模板 DTO 的 Class
     */
    public void downloadTemplate(HttpServletResponse response, String filename,
                                  Class<?> clazz) throws IOException {
        setExportResponseHeaders(response, filename);
        try (ServletOutputStream out = response.getOutputStream();
             ExcelWriter writer = EasyExcel.write(out, clazz).build()) {
            WriteSheet sheet = EasyExcel.writerSheet(filename).build();
            // 写空列表 → 仅输出表头
            writer.write(List.of(), sheet);
            writer.finish();
        }
    }

    // ==================== 内部工具 ====================

    /** 设置导出响应头 */
    private void setExportResponseHeaders(HttpServletResponse response, String filename) {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded + ".xlsx");
    }

    /**
     * 导出时翻译：遍历 DTO 上有 @DictExcelProperty 注解的字段，调用 translator.toLabel 替换值
     */
    private <T> void translateForExport(T row, Class<T> clazz) {
        for (DictFieldInfo info : resolveDictFields(clazz)) {
            if (!info.translate) continue;
            try {
                Object value = info.field.get(row);
                if (value != null) {
                    String label = translator.toLabel(info.dictType, String.valueOf(value));
                    info.field.set(row, label);
                }
            } catch (IllegalAccessException e) {
                log.warn("字典字段翻译失败 field={} dictType={}", info.field.getName(), info.dictType, e);
            }
        }
    }

    /**
     * 导入时翻译：遍历 DTO 上有 @DictExcelProperty 注解的字段，调用 translator.toValue 反查值
     */
    private <T> void translateForImport(T row, Class<T> clazz) {
        for (DictFieldInfo info : resolveDictFields(clazz)) {
            try {
                Object value = info.field.get(row);
                if (value != null) {
                    String original = translator.toValue(info.dictType, String.valueOf(value));
                    info.field.set(row, original);
                }
            } catch (IllegalAccessException e) {
                log.warn("字典字段反查失败 field={} dictType={}", info.field.getName(), info.dictType, e);
            }
        }
    }

    /** 解析 Class 中所有标注 @DictExcelProperty 的字段（带缓存） */
    private List<DictFieldInfo> resolveDictFields(Class<?> clazz) {
        return DICT_FIELD_CACHE.computeIfAbsent(clazz, c -> {
            List<DictFieldInfo> list = new ArrayList<>();
            for (Field field : c.getDeclaredFields()) {
                DictExcelProperty ann = field.getAnnotation(DictExcelProperty.class);
                if (ann != null) {
                    field.setAccessible(true);
                    list.add(new DictFieldInfo(field, ann.dictType(), ann.translate()));
                }
            }
            return list;
        });
    }

    // ==================== 内部类 ====================

    /** 字典字段元信息（内部记录） */
    private record DictFieldInfo(Field field, String dictType, boolean translate) {}

    /**
     * 导入监听器：EasyExcel 逐行读取 → 翻译 + 校验 → 分批入库。
     */
    private class ImportListener<T> extends AnalysisEventListener<T> {
        private final Class<T> clazz;
        private final BatchSaveFunction<T> saver;
        private final List<T> batch = new ArrayList<>();
        final List<T> successRows = new ArrayList<>();
        final List<ExcelImportResult.ImportError> errors = new ArrayList<>();
        private int totalRows = 0;

        ImportListener(Class<T> clazz, BatchSaveFunction<T> saver,
                       DictTranslator translator, ExcelProperties properties) {
            this.clazz = clazz;
            this.saver = saver;
        }

        @Override
        public void invoke(T data, AnalysisContext context) {
            totalRows++;
            if (totalRows > properties.getImportMaxRows()) {
                errors.add(new ExcelImportResult.ImportError(
                        totalRows, "超过最大行数限制（" + properties.getImportMaxRows() + "行）"));
                throw new RuntimeException("导入终止：超过最大行数");
            }
            batch.add(data);
            if (batch.size() >= properties.getImportBatchSize()) {
                flushBatch();
            }
        }

        @Override
        public void doAfterAllAnalysed(AnalysisContext context) {
            if (!batch.isEmpty()) {
                flushBatch();
            }
        }

        @Override
        public void onException(Exception exception, AnalysisContext context) {
            errors.add(new ExcelImportResult.ImportError(totalRows + 1,
                    "行解析异常：" + exception.getMessage()));
        }

        private void flushBatch() {
            List<T> validRows = new ArrayList<>();
            for (T row : batch) {
                try {
                    translateForImport(row, clazz);
                    // 本轮总数 - 批内余量 = 当前行在 Excel 中的大致行号
                    int excelRow = totalRows - batch.size() + validRows.size() + 1;
                    validateRow(row, excelRow);
                    validRows.add(row);
                } catch (Exception e) {
                    int excelRow = totalRows - batch.size() + validRows.size() + 1;
                    errors.add(new ExcelImportResult.ImportError(excelRow, e.getMessage()));
                }
            }
            if (!validRows.isEmpty()) {
                try {
                    saver.save(validRows);
                    successRows.addAll(validRows);
                } catch (Exception e) {
                    errors.add(new ExcelImportResult.ImportError(0,
                            "批量入库失败：" + e.getMessage()));
                }
            }
            batch.clear();
        }

        /** 单行校验钩子：默认不做额外校验，子类可扩展 */
        private void validateRow(T row, int excelRow) {
            // 预留：业务方可在此扩展校验逻辑
        }
    }
}
