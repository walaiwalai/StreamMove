package com.sh.engine.service.impl.asr;

import cn.hutool.extra.spring.SpringUtil;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.env.MapPropertySource;

import com.sh.engine.model.asr.AsrSegment;
import com.sh.engine.service.AsrService;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import java.util.*;
import static org.junit.Assert.*;

public class WhisperAsrServiceImplTest {
    private ApplicationContext previousContext;
    private StaticApplicationContext roleContext;
    @org.junit.Before
    public void taskRole() {
        previousContext = SpringUtil.getApplicationContext();
        roleContext = new StaticApplicationContext();
        roleContext.getEnvironment().getPropertySources().addFirst(new MapPropertySource("role",
                java.util.Collections.singletonMap("streammove.mode", "task")));
        new SpringUtil().setApplicationContext(roleContext);
    }
    @org.junit.After
    public void restoreRole() {
        new SpringUtil().setApplicationContext(previousContext);
        roleContext.close();
    }

    @Test public void transcribesDownloadedAudioWithoutRunningFfmpeg() throws Exception {
        try (okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer()) {
            server.start();
            WhisperAsrServiceImpl service = new WhisperAsrServiceImpl();
            java.lang.reflect.Field url = WhisperAsrServiceImpl.class.getDeclaredField("url");
            url.setAccessible(true); url.set(service, server.url("/asr").toString());
            java.nio.file.Path audio = java.nio.file.Files.createTempFile("asr-input-", ".wav");
            try {
                java.nio.file.Files.write(audio, new byte[]{1,2,3});
                server.enqueue(new okhttp3.mockwebserver.MockResponse().setBody("{\"segments\":[{\"start\":1,\"end\":2,\"text\":\"speech\",\"emotion\":\"neutral\",\"event\":\"BGM\"}]}"));
                AsrSegment segment = service.transcribeAudio(audio.toFile(), 40).get(0);
                assertEquals(41, segment.getStartTime());
                assertEquals("neutral", segment.getEmotion());
                assertEquals("BGM", segment.getEvent());
                okhttp3.mockwebserver.RecordedRequest request = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(request.getPath().startsWith("/asr?"));
                assertTrue(request.getBody().readUtf8().contains("name=\"audio_file\""));
            } finally { java.nio.file.Files.deleteIfExists(audio); }
        }
    }
    @Test public void convertsCandidateTimesBackToSourceTime() {
        List<AsrSegment> rows = WhisperAsrServiceImpl.parse("{\"segments\":[{\"start\":1.3,\"end\":2.7,\"text\":\"看这里\"}]}", 500);
        assertEquals(501, rows.get(0).getStartTime());
        assertEquals(503, rows.get(0).getEndTime());
        assertNull(rows.get(0).getEmotion());
        assertNull(rows.get(0).getEvent());
        try { WhisperAsrServiceImpl.parse("{\"text\":\"no timestamps\"}", 0); fail("missing segments accepted"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("segments")); }
    }

    @Test public void preservesSegmentLabelsWithoutCopyingWholeCandidateEmotion() {
        String response = "{\"candidate_analysis\":{\"emotion\":\"happy\",\"event\":\"BGM\"},"
                + "\"segments\":[{\"start\":0.3,\"end\":2.4,\"text\":\"看这里\","
                + "\"emotion\":\"angry\",\"event\":\"Speech\"},"
                + "{\"start\":3,\"end\":4,\"text\":\"下一句\"}]}";
        List<AsrSegment> rows = WhisperAsrServiceImpl.parse(response, 500);
        assertEquals(500, rows.get(0).getStartTime());
        assertEquals(503, rows.get(0).getEndTime());
        assertEquals("angry", rows.get(0).getEmotion());
        assertEquals("Speech", rows.get(0).getEvent());
        assertNull(rows.get(1).getEmotion());
        assertNull(rows.get(1).getEvent());
        // ASR cache and JSON evidence use the same DTO.
        List<AsrSegment> restored = com.alibaba.fastjson.JSON.parseArray(
                com.alibaba.fastjson.JSON.toJSONString(rows), AsrSegment.class);
        assertEquals(rows, restored);
    }
    @Test public void whisperSelectionDoesNotRegisterTwoAsrProviders() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Collections.singletonMap("asr.provider", "whisper")));
            context.getBeanFactory().registerSingleton("computeTasks", org.mockito.Mockito.mock(com.sh.engine.task.ComputeTasks.class));
            context.register(WhisperAsrServiceImpl.class, AliyunAsrServiceImpl.class); context.refresh();
            assertEquals(1, context.getBeansOfType(AsrService.class).size());
            assertTrue(context.getBean(AsrService.class) instanceof WhisperAsrServiceImpl);
        }
    }
}
