package com.sh.engine.processor.plugin.highlight.valorant.analysis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.sh.engine.service.LlmService;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ImageContent;
import com.sh.engine.processor.plugin.highlight.analysis.SeiMediaClock;
import com.sh.engine.model.highlight.core.OcrTextDetection;
import com.sh.engine.processor.plugin.highlight.HighlightOcrClient;
import com.alibaba.fastjson.JSONObject;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ValorantHighlightAnalysisTest {
    @Test public void rejectsOutOfBoundsAndFabricatedVisualReferences() {
        ValorantCandidateFinder.Candidate candidate = new ValorantCandidateFinder.Candidate(new File("P01.mp4"), 100, 170, 10,
                new HashSet<>(Collections.singletonList("chat-burst")));
        JSONObject decision = JSON.parseObject("{\"keep\":true,\"score\":8,\"start\":100,\"end\":160,\"title\":\"反转\",\"evidenceTimes\":[110,150]}");
        ValorantHighlightAnalyzer.validate(decision, candidate, Arrays.asList(100, 110, 150, 169));
        assertTrue(decision.getBooleanValue("valid"));
        decision.put("end", 180);
        ValorantHighlightAnalyzer.validate(decision, candidate, Arrays.asList(100, 110, 150, 169));
        assertFalse(decision.getBooleanValue("valid"));
        decision.put("end", 160); decision.put("evidenceTimes", Arrays.asList(111, 150));
        ValorantHighlightAnalyzer.validate(decision, candidate, Arrays.asList(100, 110, 150, 169));
        assertFalse(decision.getBooleanValue("valid"));
    }

    @Test public void recallWorksWithoutOcrAndDiscountsPromotion() {
        File video = new File("P01.mp4");
        List<ValorantCandidateFinder.Chat> chat = new ArrayList<>();
        for (int i = 0; i < 8; i++) { chat.add(new ValorantCandidateFinder.Chat(video, 201 + i, "哈哈哈", ValorantCandidateFinder.activityWeight("哈哈哈"))); }
        ValorantCandidateFinder finder = new ValorantCandidateFinder();
        List<ValorantCandidateFinder.Candidate> candidates = finder.find(Collections.singletonList(
                new SeiMediaClock.Source(video, 500, 0, Collections.emptyList())), chat, Collections.emptyList());
        assertEquals(1, candidates.size());
        assertTrue(candidates.get(0).getStart() < 201);
        assertTrue(candidates.get(0).getEnd() > 208);
        assertEquals(0.25, ValorantCandidateFinder.activityWeight("点点关注领福袋"), 0);
        assertEquals(1.2, ValorantCandidateFinder.activityWeight("哈哈哈"), 0);
    }
    @Test public void preservesLeadInWhenModelStartsAtFirstKeyFrame() {
        ValorantCandidateFinder.Candidate candidate = new ValorantCandidateFinder.Candidate(new File("P01.mp4"), 20, 130, 10,
                new HashSet<>(Collections.singletonList("chat-burst")));
        JSONObject decision = JSON.parseObject("{\"start\":52,\"end\":77,\"evidenceTimes\":[52,58,65,71,77]}");
        ValorantPlanRefiner.boundaries(decision, candidate, Collections.emptyList());
        assertEquals(45, decision.getDoubleValue("start"), 0);
        assertEquals(80, decision.getDoubleValue("end"), 0);
        assertEquals(52, decision.getDoubleValue("modelStart"), 0);
        decision = JSON.parseObject("{\"start\":20,\"end\":130,\"evidenceTimes\":[20,129]}");
        ValorantPlanRefiner.boundaries(decision, candidate, Collections.emptyList());
        assertEquals(20, decision.getDoubleValue("start"), 0);
        assertEquals(130, decision.getDoubleValue("end"), 0);
    }

    @Test public void rejectsActionAcrossBuyPhaseButAllowsResultFollowedByBuyPhase() {
        assertTrue(ValorantPlanRefiner.crossesRound(Arrays.asList(false, false, true, true, true, false, false)));
        assertTrue(ValorantPlanRefiner.crossesRound(Arrays.asList(false, true, true, false, false, true, true)));
        assertFalse(ValorantPlanRefiner.crossesRound(Arrays.asList(false, false, false, true, true, true)));
        assertFalse(ValorantPlanRefiner.crossesRound(Arrays.asList(false, false, true, false, false)));
        assertFalse(ValorantPlanRefiner.crossesRound(Arrays.asList(true, true, false, false)));
    }

    @Test public void completesShortSpeechWithoutKeepingLongUnrelatedTail() {
        assertEquals(105, ValorantPlanRefiner.speechEnd(103, JSON.parseArray("[{\"startTime\":101,\"endTime\":105}]")), 0);
        assertEquals(103, ValorantPlanRefiner.speechEnd(103, JSON.parseArray("[{\"startTime\":101,\"endTime\":114}]")), 0);
    }

    @Test public void preservesActionLeadInFromOcrEvenWhenModelCitesLaterFrames() {
        File video = new File("P01.mp4");
        ValorantCandidateFinder.Candidate candidate = new ValorantCandidateFinder.Candidate(video, 250, 400, 10,
                new HashSet<>(Collections.singletonList("ocr")));
        JSONObject decision = JSON.parseObject("{\"start\":310,\"end\":350,\"evidenceTimes\":[310,340]}");
        com.sh.engine.model.highlight.core.HighlightEvent event = com.sh.engine.model.highlight.core.HighlightEvent.builder()
                .sourceVideo(video).secondFromVideoStart(305).eventType("SELF_KILL").confidence(1).build();
        ValorantPlanRefiner.boundaries(decision, candidate, Collections.singletonList(event));
        assertEquals(298, decision.getDoubleValue("start"), 0);
    }

    @Test public void avoidsCuttingOverlappingAsrSegmentsAfterActionEnds() {
        com.alibaba.fastjson.JSONArray speech = JSON.parseArray("[{\"startTime\":100,\"endTime\":104},{\"startTime\":103,\"endTime\":107},{\"startTime\":106,\"endTime\":109}]");
        assertEquals(100, ValorantPlanRefiner.actionSpeechEnd(105, speech, 99), 0);
        assertEquals(105, ValorantPlanRefiner.actionSpeechEnd(105, speech, 102), 0);
        assertEquals(109, ValorantPlanRefiner.actionSpeechEnd(107, speech, 102), 0);
    }

    @org.junit.Rule public org.junit.rules.TemporaryFolder temporary = new org.junit.rules.TemporaryFolder();

    @Test public void malformedReviewsRemainAuditOnly() throws Exception {
        File directory = temporary.newFolder();
        File file = new File(directory, "review.json");
        JSONObject review = JSON.parseObject("{\"frameTimes\":[10,20],\"finishReason\":\"STOP\"}");
        review.put("finalResponse", "invalid JSON");
        try { ValorantHighlightAnalyzer.saveReview(file, review); fail("malformed response saved as valid"); }
        catch (IllegalArgumentException expected) { assertFalse(file.exists()); }
        assertTrue(new File(directory, "review-error.json").isFile());
        review.put("finalResponse", "{\"keep\":true,\"score\":8,\"start\":\"broken\"}");
        try { ValorantHighlightAnalyzer.saveReview(file, review); fail("malformed fields saved as valid"); }
        catch (IllegalArgumentException expected) { assertFalse(file.exists()); }
        review.put("finalResponse", "{\"keep\":false,\"score\":3}");
        ValorantHighlightAnalyzer.saveReview(file, review);
        assertTrue(file.isFile());
        review.remove("frameTimes");
        try { ValorantHighlightAnalyzer.reviewDecision(review); fail("missing image times accepted"); }
        catch (IllegalArgumentException expected) { /* evidence metadata is required */ }
    }

    @Test public void retriesMalformedJsonOnceWithoutChangingEvidenceOrForcingKeep() throws Exception {
        LlmService llm = mock(LlmService.class);
        String malformed = "{\"keep\":true,\"score\":7,\"outcome\":\"camera \"one\" joke\"}";
        JSONObject first = completedReview("first-request", malformed);
        JSONObject second = completedReview("second-request", "{\"keep\":false,\"score\":3}");
        List<Content> blocks = Arrays.asList(TextContent.from("original prompt and evidence"),
                ImageContent.from("original-image", "image/jpeg"));
        List<Content> original = new ArrayList<>(blocks);
        when(llm.chat(blocks)).thenReturn(first, second);
        ValorantHighlightAnalyzer analyzer = analyzerWith(llm);
        File file = new File(temporary.newFolder(), "review.json");
        JSONObject result = analyzer.requestReview(blocks, Arrays.asList(10, 20), file);
        assertEquals("second-request", result.getString("requestId"));
        assertFalse(ValorantHighlightAnalyzer.reviewDecision(result).getBooleanValue("keep"));
        assertEquals(original, blocks);
        JSONObject retained = JSON.parseObject(new String(java.nio.file.Files.readAllBytes(
                new File(file.getParentFile(), "review-error-first.json").toPath()), StandardCharsets.UTF_8));
        assertEquals(malformed, retained.getString("finalResponse"));
        assertTrue(file.isFile());
        verify(llm, times(2)).chat(same(blocks));
    }

    @Test public void secondMalformedReplyFailsAndRetainsBothAttempts() throws Exception {
        LlmService llm = mock(LlmService.class);
        List<Content> blocks = new ArrayList<>();
        when(llm.chat(blocks)).thenReturn(completedReview("first-request", "invalid first"),
                completedReview("second-request", "invalid second"));
        File file = new File(temporary.newFolder(), "review.json");
        try {
            analyzerWith(llm).requestReview(blocks, Arrays.asList(10, 20), file);
            fail("second malformed reply accepted");
        } catch (IllegalArgumentException expected) {
            assertFalse(file.exists());
        }
        assertTrue(new File(file.getParentFile(), "review-error-first.json").isFile());
        assertTrue(new File(file.getParentFile(), "review-error.json").isFile());
        verify(llm, times(2)).chat(same(blocks));
    }

    @Test public void transportFailureIsNotRetriedAsAFormatFailure() throws Exception {
        LlmService llm = mock(LlmService.class);
        List<Content> blocks = new ArrayList<>();
        when(llm.chat(blocks)).thenThrow(new IOException("transport failed"));
        File file = new File(temporary.newFolder(), "review.json");
        try {
            analyzerWith(llm).requestReview(blocks, Arrays.asList(10, 20), file);
            fail("transport failure hidden");
        } catch (IOException expected) {
            assertFalse(file.exists());
        }
        verify(llm).chat(same(blocks));
    }

    private JSONObject completedReview(String request, String response) {
        JSONObject review = new JSONObject();
        review.put("requestId", request);
        review.put("finishReason", "STOP");
        review.put("finalResponse", response);
        return review;
    }

    private ValorantHighlightAnalyzer analyzerWith(LlmService llm) throws Exception {
        ValorantHighlightAnalyzer analyzer = new ValorantHighlightAnalyzer();
        java.lang.reflect.Field field = ValorantHighlightAnalyzer.class.getDeclaredField("llmService");
        field.setAccessible(true);
        field.set(analyzer, llm);
        return analyzer;
    }

    @Test public void leadingPurchasePhaseDoesNotHideTailAfterCombat() {
        List<File> frames = new ArrayList<>();
        for (int time : new int[]{100, 105, 110, 115, 120, 125}) {
            frames.add(new File(String.format(Locale.ROOT, "frame-%06d.jpg", time)));
        }
        com.alibaba.fastjson.JSONArray events = JSON.parseArray("[{\"eventType\":\"SELF_KILL\",\"secondFromVideoStart\":115}]");
        List<Boolean> phases = Arrays.asList(true, true, false, false, true, true);
        assertFalse(ValorantPlanRefiner.crossesRound(phases));
        assertEquals(120, ValorantPlanRefiner.tailBuyStart(phases, frames, events, 130), 0);
        events.add(JSON.parseObject("{\"eventType\":\"SELF_KILL\",\"secondFromVideoStart\":126}"));
        assertEquals(-1, ValorantPlanRefiner.tailBuyStart(phases, frames, events, 130), 0);
        assertEquals(-1, ValorantPlanRefiner.tailBuyStart(Arrays.asList(false, false, false, false, true, false), frames, events, 130), 0);
    }

    @Test public void purchaseButtonConfirmsTailWhenLargeBannerIsUnreadable() {
        List<Boolean> phases = new ArrayList<>();
        List<File> frames = new ArrayList<>();
        String[] texts = {"绊线已触发", "价段", "购买", "购买阶段", "捷风150"};
        for (int i = 0; i < texts.length; i++) {
            phases.add(ValorantPlanRefiner.purchasePhase(Collections.singletonList(
                    new OcrTextDetection(texts[i], .98f, Collections.emptyList()))));
            frames.add(new File(String.format(Locale.ROOT, "frame-%06d.jpg", 200 + i * 6)));
        }
        JSONArray events = JSON.parseArray("[{\"eventType\":\"SELF_KILL\",\"secondFromVideoStart\":199}]");
        assertEquals(212, ValorantPlanRefiner.tailBuyStart(phases, frames, events, 230), 0);
        for (String text : Arrays.asList("购买链接", "点击购买", "购买武器", "粉丝团")) {
            assertFalse(ValorantPlanRefiner.purchasePhase(Collections.singletonList(
                    new OcrTextDetection(text, .99f, Collections.emptyList()))));
        }
        assertFalse(ValorantPlanRefiner.purchasePhase(Collections.singletonList(
                new OcrTextDetection("购买", .6f, Collections.emptyList()))));
    }

    @Test public void funnyGameplayCannotJoinAnotherRoundButPostRoundReactionRemainsAllowed() throws Exception {
        assertFalse(refinedStory("搞笑", Arrays.asList(false, true, true, false, false)).getBooleanValue("valid"));
        assertTrue(refinedStory("搞笑", Arrays.asList(false, false, false, true, true)).getBooleanValue("valid"));
        assertTrue(refinedStory("互动", Arrays.asList(false, true, true, false, false)).getBooleanValue("valid"));
    }

    private JSONObject refinedStory(String kind, List<Boolean> phases) throws Exception {
        File directory = temporary.newFolder();
        HighlightOcrClient ocr = mock(HighlightOcrClient.class);
        when(ocr.recognize(any(byte[].class), anyString())).thenAnswer(call -> {
            String name = call.getArgument(1);
            int index = Integer.parseInt(name.substring(6, 12)) / 10;
            return name.endsWith("-round.jpg") && phases.get(index)
                    ? Collections.singletonList(new OcrTextDetection("购买阶段", .99f, Collections.emptyList()))
                    : Collections.emptyList();
        });
        for (int i = 0; i < phases.size(); i++) {
            javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1280, 720,
                    java.awt.image.BufferedImage.TYPE_INT_RGB), "jpg",
                    new File(directory, String.format(Locale.ROOT, "frame-%06d.jpg", i * 10)));
        }
        java.nio.file.Files.write(new File(directory, "evidence.json").toPath(),
                "{\"ocr\":[],\"asr\":[],\"candidateEnd\":50}".getBytes(StandardCharsets.UTF_8));
        ValorantPlanRefiner refiner = new ValorantPlanRefiner();
        java.lang.reflect.Field field = ValorantPlanRefiner.class.getDeclaredField("ocrClient");
        field.setAccessible(true); field.set(refiner, ocr);
        JSONObject decision = JSON.parseObject("{\"start\":0,\"end\":40,\"valid\":true,\"evidenceTimes\":[0,40]}");
        decision.put("kind", kind); decision.put("title", "model title");
        refiner.refine(decision, directory);
        return decision;
    }

}
