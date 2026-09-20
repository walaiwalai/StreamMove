package com.sh.engine.processor.plugin.highlight.danmaku;

import com.sh.engine.model.asr.AsrSegment;
import com.sh.engine.model.danmaku.HighlightClipRange;
import com.sh.engine.model.danmaku.HighlightEvidenceCatalog;
import com.sh.engine.model.danmaku.HighlightEvidenceItem;
import com.sh.engine.model.danmaku.VisualObservation;
import com.sh.engine.model.danmaku.VisualTimelineResult;
import com.sh.engine.processor.recorder.danmu.SimpleDanmaku;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class DanmakuHighlightPromptFactoryTest {
    private static final List<String> SAMPLE_SPECIFIC_TERMS = Arrays.asList(
            "绝地求生", "PUBG", "98K", "厕所", "老六", "空投", "手雷", "吃鸡", "团灭");

    @Test
    public void shouldBuildGenericMultimodalPrompt() {
        AsrSegment asr = AsrSegment.builder()
                .startTime(10).endTime(12).text("现在开始新的尝试").build();
        VisualObservation observation = new VisualObservation();
        observation.setFrameIndex(1);
        observation.setTimestamp("00:00:15");
        observation.setObservableFacts("一名人物站在明亮舞台中央");
        observation.setVisibleChange("背景灯光由暗变亮");
        observation.setCertainty("high");
        observation.setCoverCandidate(true);
        VisualTimelineResult timeline = new VisualTimelineResult();
        timeline.setObservations(Collections.singletonList(observation));
        timeline.setSummary("舞台灯光发生可见变化");
        timeline.setUncertainties(Collections.emptyList());

        String prompt = new DanmakuHighlightPromptFactory().buildSparseReview(
                "主播", Collections.singletonList(asr), Collections.emptyList(),
                timeline, Collections.emptyList(), 0, 60, 0);

        Assert.assertTrue(prompt.contains("【多帧视觉时间线】"));
        Assert.assertTrue(prompt.contains("[ASR-001][00:00:10-00:00:12]"));
        Assert.assertTrue(prompt.contains("[VISION-001][00:00:15]"));
        Assert.assertTrue(prompt.contains("evidenceId"));
        Assert.assertTrue(prompt.contains("actionEvidence"));
        Assert.assertTrue(prompt.contains("ASR、OCR 或 VISION"));
        Assert.assertTrue(prompt.contains("score=45~59"));
        for (String term : SAMPLE_SPECIFIC_TERMS) {
            Assert.assertFalse("fixed prompt leaked sample term: " + term,
                    prompt.contains(term));
        }
    }

    @Test
    public void shouldAssignStableEvidenceIdToSelectedDanmaku() {
        SimpleDanmaku reaction = new SimpleDanmaku(
                20.0f, 0L, "太离谱了！", "ffffff");

        String prompt = new DanmakuHighlightPromptFactory().buildDenseEvidenceContext(
                "主播", Collections.emptyList(), Collections.emptyList(), null,
                Collections.singletonList(reaction), 0, 60, 0);

        Assert.assertTrue(prompt.contains("[DANMAKU-001][00:00:20] 太离谱了！"));
        Assert.assertTrue(prompt.contains("送审证据: 1条"));
    }

    @Test
    public void publicationContextShouldContainOnlyExactClipEvidence() {
        HighlightEvidenceCatalog catalog = new HighlightEvidenceCatalog(
                Collections.emptyList(), Collections.emptyList(), null,
                Arrays.asList(
                        new HighlightEvidenceItem(
                                "VISION-001", "VISION", 5, 5, "范围外背景"),
                        new HighlightEvidenceItem(
                                "VISION-002", "VISION", 25, 25, "范围内动作"),
                        new HighlightEvidenceItem(
                                "DANMAKU-001", "DANMAKU", 28, 28, "范围内反应"),
                        new HighlightEvidenceItem(
                                "DANMAKU-002", "DANMAKU", 70, 70, "迟到的范围外反应")));

        String prompt = new DanmakuHighlightPromptFactory()
                .buildPublicationEvidenceContext(
                        "主播", catalog, new HighlightClipRange(20, 40));

        Assert.assertTrue(prompt.contains("精剪范围为 00:00:20 ~ 00:00:40"));
        Assert.assertTrue(prompt.contains("[VISION-002][00:00:25] 范围内动作"));
        Assert.assertTrue(prompt.contains("[DANMAKU-001][00:00:28] 范围内反应"));
        Assert.assertFalse(prompt.contains("范围外背景"));
        Assert.assertFalse(prompt.contains("迟到的范围外反应"));
    }
}
