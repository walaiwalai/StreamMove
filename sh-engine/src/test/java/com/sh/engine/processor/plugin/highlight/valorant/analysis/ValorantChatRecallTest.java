package com.sh.engine.processor.plugin.highlight.valorant.analysis;

import com.alibaba.fastjson.JSONObject;
import com.sh.engine.model.highlight.core.HighlightEvent;
import com.sh.engine.processor.plugin.highlight.analysis.SeiMediaClock;
import org.apache.commons.io.FileUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class ValorantChatRecallTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final File video = new File("P01.mp4");
    private final ValorantCandidateFinder finder = new ValorantCandidateFinder();

    @Test public void ordinaryBurstAcrossOldBucketBoundaryIsRecalled() {
        List<ValorantCandidateFinder.Chat> chats = new ArrayList<>();
        for (int i = 0; i < 6; i++) { chats.add(chat(197 + i, "普通发言" + i)); }
        List<ValorantCandidateFinder.Candidate> selected = find(chats, 500);
        assertEquals(1, selected.size()); assertTrue(selected.get(0).getStart() < 197); assertTrue(selected.get(0).getEnd() > 202);
    }

    @Test public void threeKeywordMessagesAreNotEnoughWithoutAnActivityBurst() {
        assertTrue(find(Arrays.asList(chat(201, "牛"), chat(202, "帅"), chat(203, "666")), 500).isEmpty());
    }

    @Test public void steadyTrafficDoesNotInventPeaksAtSourceEdges() {
        for (int duration : new int[]{50, 500}) {
            List<ValorantCandidateFinder.Chat> chats = new ArrayList<>();
            for (int i = 0; i < duration; i++) { chats.add(chat(i + .1, "发言" + i)); }
            assertTrue("uniform source length=" + duration, find(chats, duration).isEmpty());
        }
    }

    @Test public void finalWindowCoversTheVideoTailWithFullExposure() {
        List<ValorantCandidateFinder.Chat> chats = new ArrayList<>();
        for (int i = 0; i < 6; i++) { chats.add(chat(500 + i, "尾部发言" + i)); }
        List<ValorantCandidateFinder.Candidate> selected = find(chats, 507);
        assertEquals(1, selected.size()); assertTrue(selected.get(0).getEnd() >= 505); assertTrue(selected.get(0).getEnd() < 507);
        assertTrue(find(Arrays.asList(chat(1, "a"), chat(2, "b"), chat(3, "c"), chat(4, "d"), chat(5, "e"), chat(6, "f")), 15).isEmpty());
    }

    @Test public void shortCrowdReactionsAreKeptButRepeatedLongTemplatesCannotDominate() {
        List<ValorantCandidateFinder.Chat> shortReplies = new ArrayList<>(), templates = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            shortReplies.add(chat(201 + i / 100.0, "666"));
            templates.add(chat(201 + i / 100.0, "这个重复长模板虽然带哈哈但不应该无限刷出候选"));
        }
        assertFalse(find(shortReplies, 500).isEmpty()); assertTrue(find(templates, 500).isEmpty());
        assertTrue(find(Collections.nCopies(100, chat(201, "点点关注领福袋")), 500).isEmpty());
    }

    @Test public void unrelatedEarlierCopiesDoNotReduceAlignedReactionWeight() throws Exception {
        long epoch = 1700000000L;
        SeiMediaClock.Source source = new SeiMediaClock.Source(video, 1000, 0,
                Arrays.asList(new SeiMediaClock.Anchor(0, epoch * 1000), new SeiMediaClock.Anchor(1000, (epoch + 1000) * 1000)));
        File first = temporary.newFile(), second = temporary.newFile();
        String target = "800__SEP__" + (epoch + 800) + "__SEP__哈哈哈__SEP__ffffff\n";
        FileUtils.writeStringToFile(first, target, StandardCharsets.UTF_8);
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 200; i++) { rows.append("50__SEP__").append(epoch + 50).append("__SEP__哈哈哈__SEP__ffffff\n"); }
        rows.append(target); FileUtils.writeStringToFile(second, rows.toString(), StandardCharsets.UTF_8);
        List<ValorantCandidateFinder.Chat> before = finder.align(first, Collections.singletonList(source), new JSONObject());
        List<ValorantCandidateFinder.Chat> after = finder.align(second, Collections.singletonList(source), new JSONObject());
        assertEquals(before.get(0).getWeight(), after.get(after.size()-1).getWeight(), 0);
    }

    @Test public void ocrRecallStillWorksWithoutAnyChat() {
        List<HighlightEvent> events = Arrays.asList(
                HighlightEvent.builder().sourceVideo(video).secondFromVideoStart(200).eventType("SELF_KILL").build(),
                HighlightEvent.builder().sourceVideo(video).secondFromVideoStart(210).eventType("SELF_KILL").build());
        List<ValorantCandidateFinder.Candidate> selected = finder.find(Collections.singletonList(source(500)), Collections.emptyList(), events);
        assertEquals(1, selected.size()); assertTrue(selected.get(0).getTriggers().contains("ocr-multi-kill-candidate"));
    }

    @Test public void chatVolumeDoesNotLinearlyCrowdOutAnOcrHighlightAtTheSameBudget() {
        List<ValorantCandidateFinder.Chat> chats = new ArrayList<>();
        for (int burst = 0; burst < 8; burst++) {
            for (int i = 0; i < 100; i++) { chats.add(chat(200 + burst * 400 + i / 100.0, "666")); }
        }
        List<HighlightEvent> events = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            events.add(HighlightEvent.builder().sourceVideo(video).secondFromVideoStart(3500 + i * 5).eventType("SELF_KILL").build());
        }
        List<ValorantCandidateFinder.Candidate> selected = finder.find(Collections.singletonList(source(4000)), chats, events);
        assertEquals(8, selected.size());
        assertTrue(selected.stream().anyMatch(candidate -> candidate.getTriggers().contains("ocr-multi-kill-candidate")));
    }

    @Test public void evidenceDoesNotDiscardLongOrPromotionRelatedDiscussionEvenWithZeroRecallWeight() {
        String longText = String.join("", Collections.nCopies(8, "这段过程需要结合前面的画面才能说明原因"));
        List<ValorantCandidateFinder.Chat> chats = Arrays.asList(
                new ValorantCandidateFinder.Chat(video, 105, longText, 0),
                new ValorantCandidateFinder.Chat(video, 106, "这波比抽奖还刺激", 0),
                new ValorantCandidateFinder.Chat(video, 110, "", 0),
                new ValorantCandidateFinder.Chat(new File("P02.mp4"), 105, "不同视频", 1),
                chat(99, "范围之外"));
        List<JSONObject> selected = ValorantHighlightAnalyzer.selectChatEvidence(candidate(), chats);
        assertEquals(2, selected.size()); assertEquals(longText, selected.get(0).getString("text"));
        assertEquals("这波比抽奖还刺激", selected.get(1).getString("text"));
        assertTrue(ValorantCandidateFinder.activityWeight(longText) > 0);
        assertTrue(ValorantCandidateFinder.activityWeight("这波比抽奖还刺激") > 0);
    }

    @Test public void evidenceAggregatesCopiesWithinTenSecondsButPreservesTemporalGroups() {
        List<ValorantCandidateFinder.Chat> chats = Arrays.asList(chat(111, "666"), chat(101, "666"), chat(102, "666"));
        List<JSONObject> selected = ValorantHighlightAnalyzer.selectChatEvidence(candidate(), chats);
        assertEquals(2, selected.size()); assertEquals(101, selected.get(0).getDoubleValue("second"), 0);
        assertEquals(102, selected.get(0).getDoubleValue("lastSecond"), 0);
        assertEquals(2, selected.get(0).getIntValue("messageCount")); assertEquals(1, selected.get(1).getIntValue("messageCount"));
    }

    @Test public void evidenceBudgetIsTimeDistributedInsteadOfFilledWithHighWeightSlogans() {
        List<ValorantCandidateFinder.Chat> chats = new ArrayList<>();
        for (int i = 0; i < 100; i++) { chats.add(new ValorantCandidateFinder.Chat(video, 100 + i, "不同的发言" + i, i < 80 ? 100 : 0)); }
        List<JSONObject> selected = ValorantHighlightAnalyzer.selectChatEvidence(candidate(), chats);
        assertEquals(80, selected.size()); assertEquals(100, selected.get(0).getDoubleValue("second"), 0);
        assertEquals(199, selected.get(79).getDoubleValue("second"), 0);
        for (int i = 1; i < selected.size(); i++) { assertTrue(selected.get(i).getDoubleValue("second") > selected.get(i-1).getDoubleValue("second")); }
    }

    private ValorantCandidateFinder.Chat chat(double second, String text) {
        return new ValorantCandidateFinder.Chat(video, second, text, ValorantCandidateFinder.activityWeight(text));
    }
    private SeiMediaClock.Source source(double duration) { return new SeiMediaClock.Source(video, duration, 0, Collections.emptyList()); }
    private List<ValorantCandidateFinder.Candidate> find(List<ValorantCandidateFinder.Chat> chats, double duration) {
        return finder.find(Collections.singletonList(source(duration)), chats, Collections.emptyList());
    }
    private ValorantCandidateFinder.Candidate candidate() { return new ValorantCandidateFinder.Candidate(video, 100, 200, 1, Collections.singleton("chat-burst")); }
}
