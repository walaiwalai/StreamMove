package com.sh.engine.processor.plugin;

import com.sh.config.exception.StreamerRecordException;
import com.sh.config.model.storage.FileStatusModel;
import com.sh.engine.constant.ProcessPluginEnum;
import com.sh.engine.model.Streamer;
import com.sh.engine.model.StreamerInfoHolder;
import com.sh.engine.model.danmaku.ConfirmedHighlight;
import com.sh.engine.model.danmaku.DanmakuTimeBucket;
import com.sh.engine.model.llm.LlmResponseException;
import com.sh.engine.processor.plugin.highlight.danmaku.DanmakuHighlightArtifactGenerator;
import com.sh.engine.processor.plugin.highlight.danmaku.DanmakuHighlightCandidateAnalyzer;
import com.sh.engine.service.DanmakuAnalysisService;
import com.sh.message.service.MsgSendService;
import org.apache.commons.io.FileUtils;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DanmakuAIHighlightPluginTest {

    @Test
    public void shouldExposePluginContract() {
        DanmakuAIHighlightPlugin plugin = new DanmakuAIHighlightPlugin();

        Assert.assertEquals(
                ProcessPluginEnum.DAN_MU_HL_VOD_CUT.getType(), plugin.getPluginName());
        Assert.assertEquals(1, plugin.getMaxProcessParallel());
    }

    @Test(expected = StreamerRecordException.class)
    public void shouldFailWhenDanmakuFileIsMissing() throws Exception {
        Path recordDirectory = Files.createTempDirectory("highlight-missing-danmaku-");
        try {
            new DanmakuAIHighlightPlugin().process(recordDirectory.toString());
        } finally {
            Files.deleteIfExists(recordDirectory);
        }
    }

    @Test
    public void shouldReturnFailureWhenSourceVideoIsMissing() throws Exception {
        Path recordDirectory = Files.createTempDirectory("highlight-missing-video-");
        try {
            List<String> danmakus = new ArrayList<>();
            for (int index = 0; index < 500; index++) {
                danmakus.add(index + ".000__SEP__1__SEP__test__SEP__ffffff");
            }
            Files.write(recordDirectory.resolve("damaku.txt"),
                    danmakus, StandardCharsets.UTF_8);

            Assert.assertFalse(new DanmakuAIHighlightPlugin()
                    .process(recordDirectory.toString()));
        } finally {
            FileUtils.deleteDirectory(recordDirectory.toFile());
        }
    }

    @Test
    public void shouldIsolateInvalidLlmResponseToOneCandidate() throws Exception {
        Path recordDirectory = Files.createTempDirectory("highlight-isolate-llm-");
        try {
            Streamer streamer = new Streamer();
            streamer.setName("test-streamer");
            streamer.setRecordPaths(new ArrayList<>());
            StreamerInfoHolder.addStreamer(streamer);
            writeMinimumDanmakus(recordDirectory);
            File video = Files.createFile(recordDirectory.resolve("1-P01.mp4")).toFile();
            writeVideoMetadata(recordDirectory, video);
            DanmakuTimeBucket first = candidate(0, 30);
            DanmakuTimeBucket second = candidate(40, 70);
            ConfirmedHighlight highlight = new ConfirmedHighlight(
                    video, 42, 62, 50, 75, 75, 75,
                    "完整事件", "完整事件突然反转", "突然反转");
            DanmakuAnalysisService analysisService = mock(DanmakuAnalysisService.class);
            DanmakuHighlightCandidateAnalyzer analyzer =
                    mock(DanmakuHighlightCandidateAnalyzer.class);
            DanmakuHighlightArtifactGenerator artifactGenerator =
                    mock(DanmakuHighlightArtifactGenerator.class);
            when(analysisService.analyzeDanmakuPeak(anyString(), anyList()))
                    .thenReturn(Arrays.asList(first, second));
            when(analyzer.analyze(any(File.class), any(DanmakuTimeBucket.class),
                    any(), anyInt(), anyInt(), anyInt()))
                    .thenThrow(new LlmResponseException("invalid", "raw"))
                    .thenReturn(Collections.singletonList(highlight));
            when(artifactGenerator.generate(eq(recordDirectory.toString()),
                    any(File.class), anyList())).thenReturn(true);
            DanmakuAIHighlightPlugin plugin = new DanmakuAIHighlightPlugin();
            inject(plugin, "danmakuAnalysisService", analysisService);
            inject(plugin, "candidateAnalyzer", analyzer);
            inject(plugin, "artifactGenerator", artifactGenerator);
            inject(plugin, "msgSendService", mock(MsgSendService.class));

            Assert.assertTrue(plugin.process(recordDirectory.toString()));
            verify(analyzer, times(2)).analyze(any(File.class),
                    any(DanmakuTimeBucket.class), any(), anyInt(), anyInt(), anyInt());
            verify(artifactGenerator).generate(eq(recordDirectory.toString()),
                    any(File.class), eq(Collections.singletonList(highlight)));
        } finally {
            StreamerInfoHolder.clear();
            FileUtils.deleteDirectory(recordDirectory.toFile());
        }
    }

    private void writeMinimumDanmakus(Path recordDirectory) throws Exception {
        List<String> danmakus = new ArrayList<>();
        for (int index = 0; index < 500; index++) {
            danmakus.add(index + ".000__SEP__1__SEP__test__SEP__ffffff");
        }
        Files.write(recordDirectory.resolve("damaku.txt"),
                danmakus, StandardCharsets.UTF_8);
    }

    private void writeVideoMetadata(Path recordDirectory, File video) {
        FileStatusModel.VideoMetaInfo metadata = FileStatusModel.VideoMetaInfo.builder()
                .durationSecond(100)
                .recordStartTimeStamp(0L)
                .recordEndTimeStamp(100L)
                .build();
        FileStatusModel fileStatus = new FileStatusModel();
        fileStatus.getMetaMap().put(video.getName(), metadata);
        fileStatus.writeSelfToFile(recordDirectory.toString());
    }

    private DanmakuTimeBucket candidate(int start, int end) {
        DanmakuTimeBucket candidate = new DanmakuTimeBucket();
        candidate.setStartTime(start);
        candidate.setEndTime(end);
        candidate.setSignalStartTime(start);
        candidate.setSignalEndTime(end);
        candidate.setDanmakus(Collections.emptyList());
        return candidate;
    }

    private void inject(Object target, String name, Object value) throws Exception {
        Field field = DanmakuAIHighlightPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

}
