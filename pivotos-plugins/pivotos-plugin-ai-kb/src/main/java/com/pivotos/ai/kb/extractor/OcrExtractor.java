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
                log.info("[PivotOS-KB] Tesseract OCR 已就绪: languages={}", languages);
            } catch (Throwable e) {
                available = false;
                log.warn("[PivotOS-KB] Tesseract OCR 不可用，将跳过 OCR: {}", e.getMessage());
            }
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
        ITesseract instance = createTesseract();
        // tess4j 对带 alpha 通道的图像（如 RGBA PNG）会原生崩溃（Invalid memory access），
        // OCR 仅依赖亮度信息，统一归一化为灰度图后再识别
        return instance.doOCR(toGrayscale(image));
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
        ITesseract instance = createTesseract();
        StringBuilder result = new StringBuilder();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                try {
                    BufferedImage image = renderer.renderImageWithDPI(i, PDF_RENDER_DPI);
                    String pageText = instance.doOCR(toGrayscale(image));
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
