package com.sh.engine.service.impl.asr;


import com.sh.engine.model.asr.AsrSegment;
import com.sh.engine.service.AsrService;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import java.util.*;
import static org.junit.Assert.*;

public class WhisperAsrServiceImplTest {

    @Test public void transcribesDownloadedAudioWithoutRunningFfmpeg() throws Exception {
        try (okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer()) {
            server.start();
            WhisperAsrServiceImpl service = new WhisperAsrServiceImpl();
            java.lang.reflect.Field url = WhisperAsrServiceImpl.class.getDeclaredField("url");
            url.setAccessible(true); url.set(service, server.url("/asr").toString());
            java.nio.file.Path audio = java.nio.file.Files.createTempFile("asr-input-", ".wav");
            try {
                java.nio.file.Files.write(audio, new byte[]{1,2,3});
                server.enqueue(new okhttp3.mockwebserver.MockResponse().setBody("{\"segments\":[{\"start\":1,\"end\":2,\"text\":\"speech\"}]}"));
                assertEquals(41, service.transcribeAudio(audio.toFile(), 40).get(0).getStartTime());
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
        try { WhisperAsrServiceImpl.parse("{\"text\":\"no timestamps\"}", 0); fail("missing segments accepted"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("segments")); }
    }
    @Test public void whisperSelectionDoesNotRegisterTwoAsrProviders() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Collections.singletonMap("asr.provider", "whisper")));
            context.register(WhisperAsrServiceImpl.class, AliyunAsrServiceImpl.class); context.refresh();
            assertEquals(1, context.getBeansOfType(AsrService.class).size());
            assertTrue(context.getBean(AsrService.class) instanceof WhisperAsrServiceImpl);
        }
    }

    @Test public void httpTimeoutFailsTheCallAndReleasesAsrCapacity() throws Exception {
        try (okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer()) {
            server.start();
            WhisperAsrServiceImpl service = new WhisperAsrServiceImpl();
            java.lang.reflect.Field url = WhisperAsrServiceImpl.class.getDeclaredField("url");
            url.setAccessible(true); url.set(service, server.url("/asr").toString());
            java.lang.reflect.Field client = WhisperAsrServiceImpl.class.getDeclaredField("client");
            client.setAccessible(true);
            client.set(service, new okhttp3.OkHttpClient.Builder()
                    .callTimeout(150, java.util.concurrent.TimeUnit.MILLISECONDS).build());
            java.nio.file.Path audio = java.nio.file.Files.createTempFile("asr-timeout-", ".wav");
            try {
                java.nio.file.Files.write(audio, new byte[]{1});
                server.enqueue(new okhttp3.mockwebserver.MockResponse().setHeadersDelay(1, java.util.concurrent.TimeUnit.SECONDS)
                        .setBody("{\"segments\":[]}"));
                try { service.transcribeAudio(audio.toFile(), 0); fail("HTTP timeout ignored"); }
                catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("Whisper")); }
                server.enqueue(new okhttp3.mockwebserver.MockResponse().setBody("{\"segments\":[]}"));
                assertTrue(service.transcribeAudio(audio.toFile(), 0).isEmpty());
            } finally { java.nio.file.Files.deleteIfExists(audio); }
        }
    }
}
