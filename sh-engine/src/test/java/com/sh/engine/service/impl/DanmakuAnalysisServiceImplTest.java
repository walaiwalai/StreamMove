package com.sh.engine.service.impl;

import com.sh.config.model.storage.FileStatusModel;
import com.sh.engine.model.danmaku.DanmakuTimeBucket;
import com.sh.engine.processor.recorder.danmu.SimpleDanmaku;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DanmakuAnalysisServiceImplTest {
    private Path recordDirectory;

    @Before
    public void setUp() throws Exception {
        recordDirectory = Files.createTempDirectory("danmaku-story-window-");
        Files.createFile(recordDirectory.resolve("P01.mp4"));
        Map<String, FileStatusModel.VideoMetaInfo> metadata = new HashMap<>();
        metadata.put("P01.mp4", FileStatusModel.VideoMetaInfo.builder()
                .durationSecond(600)
                .recordStartTimeStamp(1_000)
                .recordEndTimeStamp(1_600)
                .build());
        FileStatusModel.builder().metaMap(metadata).build()
                .writeSelfToFile(recordDirectory.toString());
    }

    @After
    public void tearDown() throws Exception {
        if (recordDirectory == null) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(recordDirectory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @Test
    public void shouldPreferCompleteStoryOverRepeatedSpam() {
        List<SimpleDanmaku> danmakus = new ArrayList<>();
        add(danmakus, 96, "刚说要完成挑战");
        add(danmakus, 101, "进度开始变化了");
        add(danmakus, 106, "突然停住了");
        add(danmakus, 111, "又继续尝试");
        add(danmakus, 116, "画面变了");
        add(danmakus, 120, "结果完全没想到");
        add(danmakus, 124, "这也太意外了");
        add(danmakus, 128, "哈哈哈精彩");
        for (int index = 0; index < 100; index++) {
            add(danmakus, 300 + index % 10, "哈哈哈哈哈哈");
        }

        List<DanmakuTimeBucket> result = newAnalysisService()
                .analyzeDanmakuPeak(recordDirectory.toString(), danmakus);

        Assert.assertFalse(result.isEmpty());
        Assert.assertTrue(result.stream().anyMatch(
                bucket -> bucket.getStartTime() <= 96 && bucket.getEndTime() > 128));
        Assert.assertFalse(result.stream().anyMatch(
                bucket -> bucket.getStartTime() >= 280 && bucket.getStartTime() <= 320));
    }

    @Test
    public void shouldKeepCandidateInsideItsSourceVideoBoundary() throws Exception {
        Files.createFile(recordDirectory.resolve("P02.mp4"));
        Map<String, FileStatusModel.VideoMetaInfo> metadata = new HashMap<>();
        metadata.put("P01.mp4", FileStatusModel.VideoMetaInfo.builder()
                .durationSecond(600).recordStartTimeStamp(1_000)
                .recordEndTimeStamp(1_600).build());
        metadata.put("P02.mp4", FileStatusModel.VideoMetaInfo.builder()
                .durationSecond(600).recordStartTimeStamp(1_600)
                .recordEndTimeStamp(2_200).build());
        FileStatusModel.builder().metaMap(metadata).build()
                .writeSelfToFile(recordDirectory.toString());
        List<SimpleDanmaku> danmakus = new ArrayList<>();
        add(danmakus, 605, "最后一次尝试开始");
        add(danmakus, 610, "进度突然加快");
        add(danmakus, 620, "刚开始就出现变化");
        add(danmakus, 625, "主播完全没想到");
        add(danmakus, 630, "结果居然是这样");
        add(danmakus, 635, "哈哈哈太意外了");

        List<DanmakuTimeBucket> result = newAnalysisService()
                .analyzeDanmakuPeak(recordDirectory.toString(), danmakus);

        Assert.assertFalse(result.isEmpty());
        Assert.assertTrue(result.stream().allMatch(
                bucket -> bucket.getStartTime() >= 600 && bucket.getEndTime() <= 1_200));
        Assert.assertTrue(result.stream().allMatch(
                bucket -> bucket.getSignalStartTime() >= bucket.getStartTime()
                        && bucket.getSignalEndTime() <= bucket.getEndTime()));
    }

    @Test
    public void shouldBuildRangeForSegmentMarkerInsideFileName() throws Exception {
        Files.delete(recordDirectory.resolve("P01.mp4"));
        String sourceName = "1-P01-1080P.mp4";
        Files.createFile(recordDirectory.resolve(sourceName));
        Map<String, FileStatusModel.VideoMetaInfo> metadata = new HashMap<>();
        metadata.put(sourceName, FileStatusModel.VideoMetaInfo.builder()
                .durationSecond(600).recordStartTimeStamp(1_000)
                .recordEndTimeStamp(1_600).build());
        FileStatusModel.builder().metaMap(metadata).build()
                .writeSelfToFile(recordDirectory.toString());
        List<SimpleDanmaku> danmakus = new ArrayList<>();
        add(danmakus, 96, "刚说要完成挑战");
        add(danmakus, 101, "进度开始变化了");
        add(danmakus, 106, "突然停住了");
        add(danmakus, 111, "又继续尝试");
        add(danmakus, 116, "画面变了");
        add(danmakus, 120, "结果完全没想到");
        add(danmakus, 124, "这也太意外了");
        add(danmakus, 128, "哈哈哈精彩");

        List<DanmakuTimeBucket> result = newAnalysisService()
                .analyzeDanmakuPeak(recordDirectory.toString(), danmakus);

        Assert.assertFalse(result.isEmpty());
    }

    @Test
    public void shouldNotForceUniformOrdinaryConversationIntoCandidates() {
        List<SimpleDanmaku> danmakus = new ArrayList<>();
        for (int second = 0; second < 600; second += 10) {
            add(danmakus, second, "平常聊天内容" + second);
        }

        List<DanmakuTimeBucket> result = newAnalysisService()
                .analyzeDanmakuPeak(recordDirectory.toString(), danmakus);

        Assert.assertTrue(result.isEmpty());
    }

    @Test
    public void shouldNotTreatUniformReactionsAsAHighlight() {
        List<SimpleDanmaku> danmakus = new ArrayList<>();
        for (int second = 0; second < 600; second += 10) {
            add(danmakus, second, "哈哈日常互动" + second);
        }

        List<DanmakuTimeBucket> result = newAnalysisService()
                .analyzeDanmakuPeak(recordDirectory.toString(), danmakus);

        Assert.assertTrue(result.isEmpty());
    }

    @Test
    public void shouldRankAChangedLocalBurstWithoutSaturatingEveryWindow() {
        List<SimpleDanmaku> danmakus = new ArrayList<>();
        for (int second = 0; second < 600; second += 10) {
            add(danmakus, second, "持续讨论内容" + second);
        }
        add(danmakus, 302, "刚开始就突然变化");
        add(danmakus, 306, "居然出现新情况");
        add(danmakus, 310, "这结果完全没想到");
        add(danmakus, 314, "下一秒又变了");
        add(danmakus, 318, "哈哈确实很意外");
        add(danmakus, 322, "最后终于完成了");

        List<DanmakuTimeBucket> result = newAnalysisService()
                .analyzeDanmakuPeak(recordDirectory.toString(), danmakus);

        Assert.assertFalse(result.isEmpty());
        Assert.assertTrue(result.get(0).getStartTime() <= 302);
        Assert.assertTrue(result.get(0).getEndTime() > 322);
        Assert.assertTrue(result.get(0).getRecallScore() >= 60D);
        Assert.assertTrue(result.get(0).getRecallScore() < 100D);
    }

    private void add(List<SimpleDanmaku> danmakus, float second, String text) {
        danmakus.add(new SimpleDanmaku(second, 1_000L + (long) second, text, "ffffff"));
    }

    private DanmakuAnalysisServiceImpl newAnalysisService() {
        return new DanmakuAnalysisServiceImpl(new DanmakuCandidateRanker());
    }
}
