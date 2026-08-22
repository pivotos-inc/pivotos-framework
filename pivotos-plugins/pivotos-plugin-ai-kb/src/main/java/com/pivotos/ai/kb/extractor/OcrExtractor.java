package com.pivotos.ai.kb.extractor;

import com.pivotos.ai.kb.config.KbProperties;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.util.StringUtils;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * OCR 提取器：使用 tess4j 对图片和扫描版 PDF 进行文字识别。
 *
 * <p>支持 PNG/JPG/TIFF/BMP 图片及扫描版 PDF（按页渲染为图片后 OCR）。
 * 中文支持需系统安装 Tesseract 并配置 chi_sim 语言包。
 * Tesseract 未安装时自动降级（跳过 OCR，仅用 Tika 文本提取）。
 */
@Slf4j
public class OcrExtractor {

    private static final Set<String> IMAGE_TYPES = Set.of("png", "jpg", "jpeg", "tif", "tiff", "bmp");
    private static final Set<String> PDF_TYPES = Set.of("pdf");
    private static final int PDF_RENDER_DPI = 300;

    private final String tessdataPath;
    private final String languages;
    private volatile boolean available = false;
    /** 串行化锁：tess4j 原生层与进程内其他 JNA 调用方（如 oshi）并发时存在首次初始化竞态，OCR 调用统一串行执行 */
    private final Object ocrLock = new Object();

    public OcrExtractor(KbProperties properties) {
        KbProperties.Ocr ocr = properties.getOcr();
        boolean configEnabled = ocr != null && ocr.isEnabled();
        this.tessdataPath = ocr != null ? ocr.getTessdataPath() : null;
        this.languages = ocr != null && StringUtils.hasText(ocr.getLanguages())
                ? ocr.getLanguages() : "chi_sim+eng";

        if (configEnabled) {
            try {
                ITesseract instance = createTesseract();
                // 用 1x1 空白图片做最小化验证，确认原生库可加载
                instance.doOCR(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB));
                available = true;
                // 真图级预热：实测 fat jar 形态下「进程启动后首次真实图像 OCR」存在原生层间歇性崩溃，
                // 启动期先跑一遍完整识别管线完成原生初始化，把竞态窗口消化在启动阶段
                warmupRealPipeline();
                log.info("[PivotOS-KB] Tesseract OCR 已就绪: languages={}", languages);
            } catch (Throwable e) {
                available = false;
                log.warn("[PivotOS-KB] Tesseract OCR 不可用，将跳过 OCR: {}", e.getMessage());
            }
        }
    }

    /**
     * 启动预热：用合成灰度图完整跑一遍 doOCR 管线（语言包加载 + 原生识别），
     * 失败仅告警不影响就绪判定（请求期仍有重试兜底）。
     */
    private void warmupRealPipeline() {
        try {
            synchronized (ocrLock) {
                BufferedImage warmup = new BufferedImage(200, 60, BufferedImage.TYPE_BYTE_GRAY);
                Graphics2D g = warmup.createGraphics();
                try {
                    g.drawString("PivotOS OCR warmup", 10, 30);
                } finally {
                    g.dispose();
                }
                createTesseract().doOCR(warmup);
            }
        } catch (Throwable e) {
            log.warn("[PivotOS-KB] OCR 预热失败（不阻断就绪，请求期有重试兜底）: {}", e.getMessage());
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /**
     * 对图片或扫描版 PDF 执行 OCR 文本识别。
     *
     * @param downloadUrl 文件的可访问 URL（HTTP 预签名或本地路径）
     * @param fileType    文件类型扩展名（png/jpg/pdf 等）
     * @return OCR 识别文本，不可用或不支持的类型时返回空字符串
     */
    public String extractText(String downloadUrl, String fileType) {
        if (!available || !StringUtils.hasText(fileType)) {
            return "";
        }
        String type = fileType.toLowerCase();
        if (!IMAGE_TYPES.contains(type) && !PDF_TYPES.contains(type)) {
            return "";
        }
        try (InputStream is = openStream(downloadUrl)) {
            byte[] bytes = is.readAllBytes();
            return extractTextFromBytes(bytes, type);
        } catch (Throwable e) {
            // 捕获 Throwable：tess4j 原生层可能抛出 Error（如 Invalid memory access），不允许穿透为 500
            log.warn("[PivotOS-KB] OCR 提取失败: fileType={}, error={}", type, e.getMessage());
            return "";
        }
    }

    private String extractTextFromBytes(byte[] bytes, String fileType) {
        try {
            if (PDF_TYPES.contains(fileType)) {
                return ocrPdf(bytes);
            } else {
                return ocrImage(bytes);
            }
        } catch (Throwable e) {
            log.warn("[PivotOS-KB] OCR 处理失败: {}", e.getMessage());
            return "";
        }
    }

    private String ocrImage(byte[] imageBytes) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (image == null) {
            return "";
        }
        // tess4j 对带 alpha 通道的图像（如 RGBA PNG）会原生崩溃（Invalid memory access），
        // OCR 仅依赖亮度信息，统一归一化为灰度图后再识别
        return doOcrWithRetry(toGrayscale(image));
    }

    /**
     * 串行 + 单次重试的 doOCR 封装。
     *
     * <p>实测 fat jar 形态下进程启动后首次真实图像 OCR 会间歇性抛
     * {@code java.lang.Error: Invalid memory access}（独立 JVM 探针无法复现，
     * 疑似与进程内其他 JNA 调用方的原生初始化竞态相关），同进程内重试即恢复，
     * 故失败后用全新实例重试一次；串行执行进一步收敛原生层并发窗口。
     */
    private String doOcrWithRetry(BufferedImage image) throws Exception {
        synchronized (ocrLock) {
            try {
                return createTesseract().doOCR(image);
            } catch (Throwable first) {
                log.warn("[PivotOS-KB] OCR 首次识别失败，换新实例重试一次: {}", first.getMessage());
                return createTesseract().doOCR(image);
            }
        }
    }

    private BufferedImage toGrayscale(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_BYTE_GRAY) {
            return source;
        }
        BufferedImage gray = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        try {
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return gray;
    }

    private String ocrPdf(byte[] pdfBytes) throws Exception {
        StringBuilder result = new StringBuilder();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                try {
                    BufferedImage image = renderer.renderImageWithDPI(i, PDF_RENDER_DPI);
                    String pageText = doOcrWithRetry(toGrayscale(image));
                    result.append(pageText).append('\n');
                } catch (Throwable e) {
                    log.debug("[PivotOS-KB] PDF 第 {} 页 OCR 失败: {}", i + 1, e.getMessage());
                }
            }
        }
        return result.toString();
    }

    private ITesseract createTesseract() {
        ITesseract instance = new Tesseract();
        if (StringUtils.hasText(tessdataPath)) {
            instance.setDatapath(tessdataPath);
        }
        instance.setLanguage(languages);
        return instance;
    }

    private InputStream openStream(String url) throws IOException {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return new URL(url).openStream();
        }
        return Files.newInputStream(Path.of(url));
    }
}
