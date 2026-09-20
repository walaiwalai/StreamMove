package com.sh.engine.processor.plugin.highlight;

import com.alibaba.fastjson.JSONObject;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.engine.constant.RecordConstant;
import com.sh.engine.model.highlight.FactPreservingCoverStyle;
import com.sh.engine.model.highlight.HighlightCoverAssessment;
import com.sh.engine.model.highlight.HighlightCoverVisualAssessment;
import com.sh.engine.model.highlight.core.InMemoryVideoFrame;
import com.sh.engine.model.llm.LlmCallResult;
import com.sh.engine.model.llm.LlmImageEditResult;
import com.sh.engine.model.llm.LlmImageInput;
import com.sh.engine.model.llm.LlmResponseException;
import com.sh.engine.processor.plugin.highlight.danmaku.HighlightAnalysisAuditRepository;
import com.sh.engine.service.LlmService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 从高光关键帧生成可直接上传的 16:9 封面。 */
@Component
@Slf4j
public class HighlightCoverGenerator {
    private static final int COVER_WIDTH = 1280;
    private static final int COVER_HEIGHT = 720;
    private static final int TITLE_LEFT = 72;
    private static final int TITLE_BOTTOM = 66;
    private static final int TITLE_MAX_WIDTH = 1120;
    private static final int TITLE_MAX_LINES = 2;
    private static final int TITLE_MAX_FONT_SIZE = 68;
    private static final int TITLE_MIN_FONT_SIZE = 48;
    private static final int SAFE_ASSESSMENT_MAX_ATTEMPTS = 2;
    private static final String FRAME_FILTER = "scale=1280:720:force_original_aspect_ratio=increase,"
            + "crop=1280:720";
    private static final String COVER_PROMPT =
            "Turn this exact screenshot into a compelling short-video cover background. "
                    + "Preserve every factual subject, object, interface element and visible state. "
                    + "Do not add, remove, replace or reinterpret content. "
                    + "Improve contrast, sharpness, lighting and visual focus without changing facts. "
                    + "Keep the lower third slightly darker for a later title overlay. "
                    + "Do not render text, logos, borders or watermarks.";
    private static final String COVER_VERIFICATION_PROMPT =
            "图片1是源视频关键帧，图片2是图像模型编辑后的候选封面背景。"
                    + "请只比较两图直接可见内容，不推断题材或故事。"
                    + "检查图片2是否保留图片1的主要主体、对象、界面状态和空间关系，"
                    + "是否新增、删除、替换或错误重绘事实元素，以及是否出现乱码、畸形、"
                    + "明显模糊或无法作为封面的构图。轻微的亮度、对比度和锐度变化允许。"
                    + "图片2不得新增任何文字、标志、边框或水印。相对图片1新增任何文字（包括乱码、"
                    + "伪字和装饰字）时 unexpectedText=true 且 acceptable=false；新增内容明显遮挡主要"
                    + "主体时 majorSubjectObscured=true 且 acceptable=false；存在乱码、畸形、严重模糊"
                    + "等明显视觉伪影时 severeVisualArtifacts=true 且 acceptable=false。"
                    + "factualConsistency 和 visualQuality 均为0~100；存在事实改变时 acceptable=false。"
                    + "只输出 JSON：acceptable、factualConsistency、visualQuality、unexpectedText、"
                    + "majorSubjectObscured、severeVisualArtifacts、issues、rationale。";
    private static final String SAFE_COVER_VISUAL_PROMPT =
            "这张图片是程序从真实视频帧按原坐标逐像素执行全局单调色调映射的结果；"
                    + "代码保证没有采用生成图的空间像素，因此请勿重新推断是否新增、移动或重绘对象。"
                    + "只检查它作为短视频封面背景的技术视觉可用性：主体是否仍清晰可辨、曝光和对比度"
                    + "是否可用、是否存在严重模糊、色彩断层、压缩破损或其他严重视觉伪影。"
                    + "画面原本存在的字幕、HUD、主播窗口或文字不能仅因存在而判为伪影。"
                    + "visualQuality 为0~100；存在影响观看的严重视觉伪影时"
                    + "severeVisualArtifacts=true 且 acceptable=false。只输出 JSON：acceptable、"
                    + "visualQuality、severeVisualArtifacts、issues、rationale。";
    private static final List<String> PREFERRED_FONTS = Arrays.asList(
            "Microsoft YaHei", "Noto Sans CJK SC", "WenQuanYi Micro Hei", "SansSerif");

    @Resource
    private FfmpegFrameExtractor frameExtractor;
    @Resource
    private LlmService llmService;
    @Resource
    private HighlightAnalysisAuditRepository auditRepository;
    @Resource
    private FactPreservingCoverStyler factPreservingStyler;

    /**
     * 生成 {@code highlight-cover.jpg}。外部图片增强失败时降级到真实源帧。
     */
    public File generate(String recordPath,
                         File sourceVideo,
                         int timestampSeconds,
                         String title) {
        if (StringUtils.isBlank(recordPath) || sourceVideo == null || !sourceVideo.isFile()
                || timestampSeconds < 0 || StringUtils.isBlank(title)) {
            throw new StreamerRecordException(
                    ErrorEnum.INVALID_PARAM, "invalid highlight cover arguments");
        }
        InMemoryVideoFrame frame = frameExtractor.extract(
                sourceVideo, timestampSeconds, FRAME_FILTER);
        byte[] sourceJpeg = frame.getJpegData();
        String auditKey = sourceVideo.getName() + "-" + frame.getTimestampSeconds()
                + "-" + sourceVideo.length() + "-" + sourceVideo.lastModified();
        LlmImageInput sourceInput = new LlmImageInput("source-frame.jpg", sourceJpeg);
        byte[] backgroundJpeg = createBackgroundWithFallback(
                new File(recordPath), auditKey, sourceInput);
        File target = new File(recordPath, RecordConstant.HIGHLIGHT_THUMBNAIL_FILE_NAME);
        render(backgroundJpeg, title, target);
        log.info("highlight cover generated, source: {}@{}s, output: {}",
                sourceVideo.getName(), frame.getTimestampSeconds(), target.getAbsolutePath());
        return target;
    }

    private byte[] createBackgroundWithFallback(
            File recordDirectory,
            String auditKey,
            LlmImageInput sourceInput) {
        try {
            return createVerifiedBackground(recordDirectory, auditKey, sourceInput);
        } catch (RuntimeException e) {
            log.warn("highlight cover enhancement failed; using the real source frame, key: {}",
                    auditKey, e);
            return sourceInput.getJpegData();
        }
    }

    private byte[] createVerifiedBackground(
            File recordDirectory,
            String auditKey,
            LlmImageInput sourceInput) {
        LlmImageEditResult editResult = llmService.editImageWithRaw(
                sourceInput.getJpegData(), COVER_PROMPT);
        auditImageEdit(recordDirectory, auditKey, sourceInput, editResult);
        HighlightCoverAssessment directAssessment = assessEditedBackground(
                recordDirectory, auditKey, sourceInput, editResult.getImageData(),
                "edited-background-qwen.jpg", "cover-verification-vision");
        if (directAssessment != null && directAssessment.isQualified()) {
            persistAcceptedBackground(recordDirectory, editResult.getImageData());
            return editResult.getImageData();
        }
        log.warn("qwen highlight cover background rejected; switching to "
                        + "fact-preserving style transfer: {}",
                directAssessment == null
                        ? "empty visual verification" : directAssessment.getRationale());

        FactPreservingCoverStyle safeStyle = factPreservingStyler.transfer(
                sourceInput.getJpegData(), editResult.getImageData());
        auditSafetyComposite(recordDirectory, auditKey, sourceInput,
                editResult.getImageData(), safeStyle);
        HighlightCoverVisualAssessment safeAssessment = assessSafeBackground(
                recordDirectory, auditKey, safeStyle.getImageData());
        if (safeAssessment != null && safeAssessment.isQualified()) {
            persistAcceptedBackground(recordDirectory, safeStyle.getImageData());
            return safeStyle.getImageData();
        }
        log.warn("fact-preserving highlight cover failed technical visual verification; "
                        + "using the real source frame: {}",
                safeAssessment == null
                        ? "empty result" : safeAssessment.getRationale());
        persistAcceptedBackground(recordDirectory, sourceInput.getJpegData());
        return sourceInput.getJpegData();
    }

    private void auditImageEdit(
            File recordDirectory,
            String auditKey,
            LlmImageInput sourceInput,
            LlmImageEditResult editResult) {
        JSONObject parsed = new JSONObject(true);
        parsed.put("outputBytes", editResult.getImageData().length);
        parsed.put("outputSha256", DigestUtils.sha256Hex(editResult.getImageData()));
        File evidenceDirectory = new File(
                recordDirectory, ".highlight-evidence/cover");
        File sourceFile = new File(evidenceDirectory, "source-frame.jpg");
        File editedFile = new File(
                evidenceDirectory, "edited-background-qwen.jpg");
        persistEvidence(sourceFile, sourceInput.getJpegData());
        persistEvidence(editedFile, editResult.getImageData());
        parsed.put("sourcePath", sourceFile.getAbsolutePath());
        parsed.put("outputPath", editedFile.getAbsolutePath());
        auditRepository.appendImages(
                recordDirectory, "cover-image-edit", auditKey,
                COVER_PROMPT, editResult.getRawResponse(), parsed,
                Collections.singletonList(sourceInput));
    }

    private void auditSafetyComposite(
            File recordDirectory,
            String auditKey,
            LlmImageInput sourceInput,
            byte[] qwenBackground,
            FactPreservingCoverStyle safeStyle) {
        byte[] safeBackground = safeStyle.getImageData();
        File safeFile = new File(
                recordDirectory, ".highlight-evidence/cover/edited-background-safe.jpg");
        persistEvidence(safeFile, safeBackground);
        JSONObject parsed = safeStyle.toAuditMetadata();
        parsed.put("qwenOutputSha256", DigestUtils.sha256Hex(qwenBackground));
        parsed.put("outputSha256", DigestUtils.sha256Hex(safeBackground));
        parsed.put("outputPath", safeFile.getAbsolutePath());
        auditRepository.appendImages(
                recordDirectory, "cover-image-safety-composite", auditKey,
                "Local deterministic global color transfer; source pixels retain all spatial facts.",
                "", parsed, Arrays.asList(
                        sourceInput,
                        new LlmImageInput("edited-background-qwen.jpg", qwenBackground),
                        new LlmImageInput("edited-background-safe.jpg", safeBackground)));
    }

    private void persistAcceptedBackground(File recordDirectory, byte[] backgroundJpeg) {
        persistEvidence(new File(
                recordDirectory, ".highlight-evidence/cover/edited-background.jpg"),
                backgroundJpeg);
    }

    private void persistEvidence(File target, byte[] imageData) {
        try {
            Files.createDirectories(target.getParentFile().toPath());
            Files.write(target.toPath(), imageData);
        } catch (IOException e) {
            throw coverError("cannot persist highlight cover evidence: " + target, e);
        }
    }

    private HighlightCoverAssessment assessEditedBackground(
            File recordDirectory,
            String auditKey,
            LlmImageInput sourceInput,
            byte[] backgroundJpeg,
            String candidateName,
            String stage) {
        List<LlmImageInput> images = Arrays.asList(
                sourceInput, new LlmImageInput(candidateName, backgroundJpeg));
        LlmCallResult<HighlightCoverAssessment> call = llmService.analyzeImagesWithRaw(
                COVER_VERIFICATION_PROMPT, images, HighlightCoverAssessment.class);
        HighlightCoverAssessment assessment = call.getResult();
        auditRepository.appendImages(
                recordDirectory, stage, auditKey,
                COVER_VERIFICATION_PROMPT, call.getRawResponse(), assessment, images);
        return assessment;
    }

    private HighlightCoverVisualAssessment assessSafeBackground(
            File recordDirectory,
            String auditKey,
            byte[] safeBackground) {
        LlmImageInput input = new LlmImageInput(
                "edited-background-safe.jpg", safeBackground);
        HighlightCoverVisualAssessment assessment = null;
        for (int attempt = 1; attempt <= SAFE_ASSESSMENT_MAX_ATTEMPTS; attempt++) {
            String stage = attempt == 1
                    ? "cover-verification-vision-safe"
                    : "cover-verification-vision-safe-retry";
            try {
                LlmCallResult<HighlightCoverVisualAssessment> call =
                        llmService.analyzeImagesWithRaw(
                                SAFE_COVER_VISUAL_PROMPT, Collections.singletonList(input),
                                HighlightCoverVisualAssessment.class);
                assessment = call.getResult();
                auditRepository.appendImages(
                        recordDirectory, stage, auditKey,
                        SAFE_COVER_VISUAL_PROMPT, call.getRawResponse(), assessment,
                        Collections.singletonList(input));
            } catch (RuntimeException e) {
                auditSafeAssessmentFailure(
                        recordDirectory, stage, auditKey, input, e);
                log.warn("safe cover visual assessment failed, attempt: {}/{}, reason: {}",
                        attempt, SAFE_ASSESSMENT_MAX_ATTEMPTS, e.getMessage());
                continue;
            }
            if (assessment != null && assessment.hasRequiredFields()) {
                return assessment;
            }
            log.warn("safe cover visual assessment missing required fields, attempt: {}/{}",
                    attempt, SAFE_ASSESSMENT_MAX_ATTEMPTS);
        }
        return assessment;
    }

    private void auditSafeAssessmentFailure(
            File recordDirectory,
            String stage,
            String auditKey,
            LlmImageInput input,
            RuntimeException failure) {
        JSONObject parsed = new JSONObject(true);
        parsed.put("exception", failure.getClass().getName());
        parsed.put("message", failure.getMessage());
        String rawResponse = failure instanceof LlmResponseException
                ? ((LlmResponseException) failure).getRawEnvelope() : null;
        auditRepository.appendImages(
                recordDirectory, stage + "-error", auditKey,
                SAFE_COVER_VISUAL_PROMPT, rawResponse, parsed,
                Collections.singletonList(input));
    }

    private void render(byte[] backgroundJpeg, String title, File target) {
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(backgroundJpeg));
        } catch (IOException e) {
            throw coverError("cannot decode highlight cover background", e);
        }
        if (source == null) {
            throw coverError("unsupported highlight cover background", null);
        }

        BufferedImage cover = new BufferedImage(
                COVER_WIDTH, COVER_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = cover.createGraphics();
        configureGraphics(graphics);
        drawCroppedBackground(graphics, source);
        drawVeil(graphics);
        String fontName = resolveFontName();
        drawTitle(graphics,
                StringUtils.normalizeSpace(title.replace('\n', ' ').replace('\r', ' ')),
                fontName);
        graphics.dispose();

        try {
            if (!ImageIO.write(cover, "jpg", target)) {
                throw coverError("JPEG writer is unavailable", null);
            }
        } catch (IOException e) {
            throw coverError("cannot save highlight cover: " + target, e);
        }
    }

    private void configureGraphics(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
    }

    private void drawCroppedBackground(Graphics2D graphics, BufferedImage source) {
        double scale = Math.max(
                COVER_WIDTH / (double) source.getWidth(),
                COVER_HEIGHT / (double) source.getHeight());
        int scaledWidth = (int) Math.ceil(source.getWidth() * scale);
        int scaledHeight = (int) Math.ceil(source.getHeight() * scale);
        int x = (COVER_WIDTH - scaledWidth) / 2;
        int y = (COVER_HEIGHT - scaledHeight) / 2;
        graphics.drawImage(source, x, y, scaledWidth, scaledHeight, null);
    }

    private void drawVeil(Graphics2D graphics) {
        graphics.setComposite(AlphaComposite.SrcOver);
        graphics.setPaint(new GradientPaint(
                0, COVER_HEIGHT * 0.28f, new Color(0, 0, 0, 0),
                0, COVER_HEIGHT, new Color(0, 0, 0, 225)));
        graphics.fillRect(0, 0, COVER_WIDTH, COVER_HEIGHT);
    }

    private void drawTitle(Graphics2D graphics, String title, String fontName) {
        Font titleFont = resolveTitleFont(graphics, title, fontName);
        graphics.setFont(titleFont);
        List<String> lines = fitTitleLines(graphics.getFontMetrics(), title);
        FontMetrics metrics = graphics.getFontMetrics();
        int lineHeight = metrics.getHeight() + 5;
        int firstBaseline = COVER_HEIGHT - TITLE_BOTTOM
                - (lines.size() - 1) * lineHeight;

        float strokeWidth = Math.max(6f, titleFont.getSize2D() / 8f);
        graphics.setStroke(new BasicStroke(
                strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            int baseline = firstBaseline + index * lineHeight;
            java.awt.Shape glyph = titleFont.createGlyphVector(
                    graphics.getFontRenderContext(), line).getOutline(TITLE_LEFT, baseline);
            graphics.setColor(new Color(0, 0, 0, 210));
            graphics.draw(glyph);
            graphics.setColor(index == 0 ? new Color(255, 225, 70) : Color.WHITE);
            graphics.fill(glyph);
        }
    }

    /** 长标题先缩小到可单行展示，避免少量尾字单独换行并遮挡主要画面。 */
    private Font resolveTitleFont(Graphics2D graphics, String title, String fontName) {
        int codePointCount = Math.max(1, title.codePointCount(0, title.length()));
        int estimatedSize = TITLE_MAX_WIDTH / codePointCount - 4;
        int fontSize = Math.max(
                TITLE_MIN_FONT_SIZE, Math.min(TITLE_MAX_FONT_SIZE, estimatedSize));
        Font font = new Font(fontName, Font.BOLD, fontSize);
        while (fontSize > TITLE_MIN_FONT_SIZE
                && graphics.getFontMetrics(font).stringWidth(title) > TITLE_MAX_WIDTH) {
            fontSize -= 2;
            font = new Font(fontName, Font.BOLD, fontSize);
        }
        return font;
    }

    private List<String> fitTitleLines(FontMetrics metrics, String title) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (int offset = 0; offset < title.length(); ) {
            int codePoint = title.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            if (line.length() > 0
                    && metrics.stringWidth(line.toString() + character) > TITLE_MAX_WIDTH) {
                lines.add(line.toString());
                line.setLength(0);
                if (lines.size() == TITLE_MAX_LINES) {
                    break;
                }
            }
            line.append(character);
            offset += Character.charCount(codePoint);
        }
        if (line.length() > 0 && lines.size() < TITLE_MAX_LINES) {
            lines.add(line.toString());
        }
        if (lines.isEmpty()) {
            lines.add("直播高光");
        }
        if (metrics.stringWidth(String.join("", lines)) < metrics.stringWidth(title)) {
            int last = lines.size() - 1;
            lines.set(last, abbreviateToWidth(metrics, lines.get(last), TITLE_MAX_WIDTH - 45) + "…");
        }
        return lines;
    }

    private String abbreviateToWidth(FontMetrics metrics, String text, int maxWidth) {
        StringBuilder result = new StringBuilder();
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            if (metrics.stringWidth(result.toString() + character) > maxWidth) {
                break;
            }
            result.append(character);
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private String resolveFontName() {
        List<String> available = Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String preferred : PREFERRED_FONTS) {
            if (available.stream().anyMatch(preferred::equalsIgnoreCase)) {
                return preferred;
            }
        }
        return Font.SANS_SERIF;
    }

    private StreamerRecordException coverError(String message, Throwable cause) {
        if (cause == null) {
            return new StreamerRecordException(ErrorEnum.COVER_GENERATION_ERROR, message);
        }
        return new StreamerRecordException(ErrorEnum.COVER_GENERATION_ERROR, message, cause);
    }
}
