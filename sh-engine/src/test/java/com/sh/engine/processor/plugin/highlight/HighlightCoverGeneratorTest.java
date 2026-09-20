package com.sh.engine.processor.plugin.highlight;

import com.sh.engine.constant.RecordConstant;
import com.sh.engine.model.highlight.HighlightCoverAssessment;
import com.sh.engine.model.highlight.HighlightCoverVisualAssessment;
import com.sh.engine.model.highlight.core.InMemoryVideoFrame;
import com.sh.engine.model.llm.LlmCallResult;
import com.sh.engine.model.llm.LlmImageEditResult;
import com.sh.engine.model.llm.LlmResponseException;
import com.sh.engine.processor.plugin.highlight.danmaku.HighlightAnalysisAuditRepository;
import com.sh.engine.service.LlmService;
import org.junit.Assert;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class HighlightCoverGeneratorTest {
    @Test
    public void shouldReduceFontSizeForEighteenCharacterTitle() throws Exception {
        HighlightCoverGenerator generator = new HighlightCoverGenerator();
        BufferedImage image = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            Method method = HighlightCoverGenerator.class.getDeclaredMethod(
                    "resolveTitleFont", Graphics2D.class, String.class, String.class);
            method.setAccessible(true);

            Font font = (Font) method.invoke(
                    generator, graphics, "连续尝试终于成功现场突然发生意外", Font.SANS_SERIF);

            Assert.assertTrue(font.getSize() < 68);
            Assert.assertTrue(font.getSize() >= 48);
        } finally {
            graphics.dispose();
        }
    }

    @Test
    public void shouldCreateUploadableCoverFromRealFrame() throws Exception {
        Path recordDirectory = Files.createTempDirectory("highlight-cover-");
        try {
            File video = Files.createFile(recordDirectory.resolve("P01.mp4")).toFile();
            byte[] frame = createJpegFrame();
            HighlightCoverGenerator generator = new HighlightCoverGenerator();
            FfmpegFrameExtractor frameExtractor = mock(FfmpegFrameExtractor.class);
            when(frameExtractor.extract(any(File.class), anyInt(), anyString()))
                    .thenReturn(new InMemoryVideoFrame(42, frame));
            LlmService llmService = mock(LlmService.class);
            when(llmService.editImageWithRaw(any(byte[].class), anyString()))
                    .thenReturn(new LlmImageEditResult("image-edit-raw", frame));
            HighlightCoverAssessment assessment = new HighlightCoverAssessment();
            assessment.setAcceptable(true);
            assessment.setFactualConsistency(100);
            assessment.setVisualQuality(90);
            assessment.setUnexpectedText(false);
            assessment.setMajorSubjectObscured(false);
            assessment.setSevereVisualArtifacts(false);
            assessment.setIssues(Collections.emptyList());
            assessment.setRationale("事实一致且画面可用");
            when(llmService.analyzeImagesWithRaw(anyString(), any(), any()))
                    .thenReturn(new LlmCallResult<>("vision-raw", assessment));
            setField(generator, "frameExtractor", frameExtractor);
            setField(generator, "llmService", llmService);
            setField(generator, "auditRepository",
                    mock(HighlightAnalysisAuditRepository.class));

            File cover = generator.generate(
                    recordDirectory.toString(), video, 42, "舞台灯光突然亮起");

            Assert.assertEquals(RecordConstant.HIGHLIGHT_THUMBNAIL_FILE_NAME, cover.getName());
            BufferedImage image = ImageIO.read(cover);
            Assert.assertNotNull(image);
            Assert.assertEquals(1280, image.getWidth());
            Assert.assertEquals(720, image.getHeight());
            Assert.assertTrue(recordDirectory.resolve(
                    ".highlight-evidence/cover/source-frame.jpg").toFile().isFile());
            Assert.assertTrue(recordDirectory.resolve(
                    ".highlight-evidence/cover/edited-background.jpg").toFile().isFile());
            verify(llmService).analyzeImagesWithRaw(anyString(), any(), any());
        } finally {
            deleteRecursively(recordDirectory);
        }
    }

    @Test
    public void shouldUseSafeStyleTransferWhenQwenAddsUnexpectedText() throws Exception {
        Path recordDirectory = Files.createTempDirectory("highlight-cover-retry-");
        try {
            File video = Files.createFile(recordDirectory.resolve("P01.mp4")).toFile();
            byte[] frame = createJpegFrame();
            HighlightCoverGenerator generator = new HighlightCoverGenerator();
            FfmpegFrameExtractor frameExtractor = mock(FfmpegFrameExtractor.class);
            when(frameExtractor.extract(any(File.class), anyInt(), anyString()))
                    .thenReturn(new InMemoryVideoFrame(42, frame));
            LlmService llmService = mock(LlmService.class);
            when(llmService.editImageWithRaw(any(byte[].class), anyString()))
                    .thenReturn(new LlmImageEditResult("qwen-edit", frame));
            HighlightCoverAssessment rejected = qualifiedAssessment();
            rejected.setAcceptable(true);
            rejected.setUnexpectedText(true);
            rejected.setIssues(Collections.singletonList("新增了装饰文字"));
            HighlightCoverVisualAssessment accepted = qualifiedVisualAssessment();
            HighlightCoverVisualAssessment incomplete = new HighlightCoverVisualAssessment();
            when(llmService.analyzeImagesWithRaw(
                    anyString(), any(), eq(HighlightCoverAssessment.class)))
                    .thenReturn(new LlmCallResult<>("first-vision", rejected));
            when(llmService.analyzeImagesWithRaw(
                    anyString(), any(), eq(HighlightCoverVisualAssessment.class)))
                    .thenReturn(new LlmCallResult<>("incomplete-vision", incomplete))
                    .thenReturn(new LlmCallResult<>("second-vision", accepted));
            setField(generator, "frameExtractor", frameExtractor);
            setField(generator, "llmService", llmService);
            setField(generator, "auditRepository",
                    mock(HighlightAnalysisAuditRepository.class));
            setField(generator, "factPreservingStyler",
                    new FactPreservingCoverStyler());

            File cover = generator.generate(
                    recordDirectory.toString(), video, 42, "舞台灯光突然亮起");

            Assert.assertTrue(cover.isFile());
            verify(llmService).editImageWithRaw(any(byte[].class), anyString());
            verify(llmService, times(3)).analyzeImagesWithRaw(anyString(), any(), any());
        } finally {
            deleteRecursively(recordDirectory);
        }
    }

    @Test
    public void shouldUseRealFrameWhenSafeVisualAssessmentHasProtocolFailures()
            throws Exception {
        Path recordDirectory = Files.createTempDirectory("highlight-cover-fallback-");
        try {
            File video = Files.createFile(recordDirectory.resolve("P01.mp4")).toFile();
            byte[] frame = createJpegFrame();
            HighlightCoverGenerator generator = new HighlightCoverGenerator();
            FfmpegFrameExtractor frameExtractor = mock(FfmpegFrameExtractor.class);
            when(frameExtractor.extract(any(File.class), anyInt(), anyString()))
                    .thenReturn(new InMemoryVideoFrame(42, frame));
            LlmService llmService = mock(LlmService.class);
            when(llmService.editImageWithRaw(any(byte[].class), anyString()))
                    .thenReturn(new LlmImageEditResult("qwen-edit", frame));
            HighlightCoverAssessment rejected = qualifiedAssessment();
            rejected.setUnexpectedText(true);
            when(llmService.analyzeImagesWithRaw(
                    anyString(), any(), eq(HighlightCoverAssessment.class)))
                    .thenReturn(new LlmCallResult<>("edited-rejected", rejected));
            when(llmService.analyzeImagesWithRaw(
                    anyString(), any(), eq(HighlightCoverVisualAssessment.class)))
                    .thenThrow(new LlmResponseException("invalid JSON", "{bad"));
            setField(generator, "frameExtractor", frameExtractor);
            setField(generator, "llmService", llmService);
            setField(generator, "auditRepository",
                    mock(HighlightAnalysisAuditRepository.class));
            setField(generator, "factPreservingStyler",
                    new FactPreservingCoverStyler());

            File cover = generator.generate(
                    recordDirectory.toString(), video, 42, "舞台灯光突然亮起");

            Assert.assertTrue(cover.isFile());
            Assert.assertArrayEquals(frame, Files.readAllBytes(recordDirectory.resolve(
                    ".highlight-evidence/cover/edited-background.jpg")));
            verify(llmService, times(3)).analyzeImagesWithRaw(anyString(), any(), any());
        } finally {
            deleteRecursively(recordDirectory);
        }
    }

    private HighlightCoverAssessment qualifiedAssessment() {
        HighlightCoverAssessment assessment = new HighlightCoverAssessment();
        assessment.setAcceptable(true);
        assessment.setFactualConsistency(100);
        assessment.setVisualQuality(90);
        assessment.setUnexpectedText(false);
        assessment.setMajorSubjectObscured(false);
        assessment.setSevereVisualArtifacts(false);
        assessment.setIssues(Collections.emptyList());
        assessment.setRationale("事实一致且画面可用");
        return assessment;
    }

    private HighlightCoverVisualAssessment qualifiedVisualAssessment() {
        HighlightCoverVisualAssessment assessment = new HighlightCoverVisualAssessment();
        assessment.setAcceptable(true);
        assessment.setVisualQuality(90);
        assessment.setSevereVisualArtifacts(false);
        assessment.setIssues(Collections.emptyList());
        assessment.setRationale("清晰度和曝光可用");
        return assessment;
    }

    private byte[] createJpegFrame() throws Exception {
        BufferedImage image = new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(42, 96, 130));
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", output);
        return output.toByteArray();
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = HighlightCoverGenerator.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private void deleteRecursively(Path directory) throws Exception {
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> path.toFile().delete());
        }
    }

}
