package com.sh.engine.processor.plugin.highlight.danmaku;

import com.sh.engine.constant.RecordConstant;
import com.sh.engine.model.danmaku.ConfirmedHighlight;
import com.sh.engine.model.highlight.VideoInterval;
import com.sh.engine.model.highlight.core.HighlightMaskPlan;
import com.sh.engine.processor.plugin.highlight.HighlightAdvertisementMaskDetector;
import com.sh.engine.processor.plugin.highlight.HighlightCoverGenerator;
import com.sh.engine.service.VideoMergeService;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DanmakuHighlightArtifactGeneratorTest {

    @Test
    public void shouldPublishOnlyBestRankedIndependentClip() throws Exception {
        Path root = Files.createTempDirectory("highlight-artifact-");
        Path recordDirectory = Files.createDirectory(
                root.resolve("2026-01-01-01-01-02"));
        try {
            File firstVideo = Files.createFile(recordDirectory.resolve("P01.mp4")).toFile();
            File secondVideo = Files.createFile(recordDirectory.resolve("P02.mp4")).toFile();
            ConfirmedHighlight first = new ConfirmedHighlight(
                    firstVideo, 10, 40, 20, 38, 90, 80, 70, "第一段可发布",
                    "第一段出现清晰变化", "第一段变化");
            ConfirmedHighlight second = new ConfirmedHighlight(
                    secondVideo, 50, 90, 70, 90, 80, 90, "第二段价值更高",
                    "第二段出现明确反转", "明确反转");
            ConfirmedHighlight duplicate = new ConfirmedHighlight(
                    firstVideo, 12, 42, 22, 65, 65, 65, "第一段重复窗口",
                    "不应重复输出的片段", "重复片段");
            ConfirmedHighlight sameEventWithShortOverlap = new ConfirmedHighlight(
                    firstVideo, 35, 55, 39, 39, 70, 70, 70,
                    "同事件的短重叠窗口", "同事件不应再次输出", "同事件重复");
            VideoMergeService mergeService = mock(VideoMergeService.class);
            HighlightAdvertisementMaskDetector maskDetector =
                    mock(HighlightAdvertisementMaskDetector.class);
            HighlightCoverGenerator coverGenerator = mock(HighlightCoverGenerator.class);
            when(maskDetector.detect(anyList(), any(File.class)))
                    .thenReturn(HighlightMaskPlan.empty());
            when(mergeService.mergeWithCover(
                    anyList(), any(File.class), anyString(), any(HighlightMaskPlan.class)))
                    .thenAnswer(invocation -> {
                        File output = invocation.getArgument(1);
                        Files.write(output.toPath(), new byte[]{1, 2, 3});
                        return true;
                    });
            when(coverGenerator.generate(anyString(), any(File.class),
                    org.mockito.ArgumentMatchers.anyInt(), anyString()))
                    .thenReturn(recordDirectory.resolve(
                            RecordConstant.HIGHLIGHT_THUMBNAIL_FILE_NAME).toFile());
            DanmakuHighlightArtifactGenerator generator =
                    new DanmakuHighlightArtifactGenerator();
            inject(generator, "videoMergeService", mergeService);
            inject(generator, "advertisementMaskDetector", maskDetector);
            inject(generator, "coverGenerator", coverGenerator);
            File target = recordDirectory.resolve(RecordConstant.HL_VIDEO).toFile();

            Assert.assertTrue(generator.generate(
                    recordDirectory.toString(), target,
                    Arrays.asList(first, second, duplicate,
                            sameEventWithShortOverlap)));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<VideoInterval>> intervals =
                    ArgumentCaptor.forClass(List.class);
            verify(mergeService, times(2)).mergeWithCover(
                    intervals.capture(), any(File.class),
                    anyString(), any(HighlightMaskPlan.class));
            Assert.assertEquals(1, intervals.getAllValues().get(0).size());
            Assert.assertEquals(secondVideo,
                    intervals.getAllValues().get(0).get(0).getFromVideo());
            Assert.assertTrue(target.isFile());
            Assert.assertTrue(recordDirectory.resolve(
                    "highlight-review/clip-01.mp4").toFile().isFile());
            Assert.assertTrue(recordDirectory.resolve(
                    "highlight-review/clip-02.mp4").toFile().isFile());
            String manifest = new String(Files.readAllBytes(recordDirectory.resolve(
                    "highlight-review/manifest.json")), StandardCharsets.UTF_8);
            Assert.assertTrue(manifest.contains("第二段出现明确反转"));
            Assert.assertTrue(manifest.contains("第一段出现清晰变化"));
            Assert.assertFalse(manifest.contains("不应重复输出的片段"));
            Assert.assertFalse(manifest.contains("同事件不应再次输出"));
            verify(coverGenerator).generate(recordDirectory.toString(),
                    secondVideo, 70, "明确反转");
            String title = new String(Files.readAllBytes(recordDirectory.resolve(
                    RecordConstant.HIGHLIGHT_TITLE_FILE_NAME)), StandardCharsets.UTF_8).trim();
            Assert.assertEquals("第二段出现明确反转", title);
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void shouldClearOnlyKnownArtifactsWhenNoHighlightIsConfirmed()
            throws Exception {
        Path recordDirectory = Files.createTempDirectory("highlight-clear-");
        try {
            Files.write(recordDirectory.resolve(RecordConstant.HL_VIDEO), new byte[]{1});
            Files.write(recordDirectory.resolve(
                    RecordConstant.HIGHLIGHT_TITLE_FILE_NAME), new byte[]{1});
            Files.write(recordDirectory.resolve(
                    RecordConstant.HIGHLIGHT_THUMBNAIL_FILE_NAME), new byte[]{1});
            Path reviewDirectory = Files.createDirectory(
                    recordDirectory.resolve("highlight-review"));
            Files.write(reviewDirectory.resolve("clip-01.mp4"), new byte[]{1});
            Files.write(reviewDirectory.resolve("manifest.json"), new byte[]{1});
            Path unrelated = Files.write(
                    recordDirectory.resolve("source.mp4"), new byte[]{1});
            DanmakuHighlightArtifactGenerator generator =
                    new DanmakuHighlightArtifactGenerator();

            generator.clearGeneratedArtifacts(recordDirectory.toString());

            Assert.assertFalse(recordDirectory.resolve(
                    RecordConstant.HL_VIDEO).toFile().exists());
            Assert.assertFalse(reviewDirectory.resolve("clip-01.mp4").toFile().exists());
            Assert.assertFalse(reviewDirectory.resolve("manifest.json").toFile().exists());
            Assert.assertTrue(unrelated.toFile().isFile());
        } finally {
            deleteRecursively(recordDirectory);
        }
    }

    private void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = DanmakuHighlightArtifactGenerator.class.getDeclaredField(fieldName);
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
