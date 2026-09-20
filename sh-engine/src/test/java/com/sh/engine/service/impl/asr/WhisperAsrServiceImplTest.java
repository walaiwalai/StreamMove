package com.sh.engine.service.impl.asr;

import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.engine.model.asr.AsrSegment;
import okhttp3.Request;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class WhisperAsrServiceImplTest {
    @Test
    public void shouldBuildWhisperAsrEndpointWithExpectedOptions() throws Exception {
        WhisperAsrServiceImpl service = new WhisperAsrServiceImpl();
        setField(service, "baseUrl", "http://127.0.0.1:9001");
        setField(service, "token", "test-token");
        setField(service, "language", "zh");

        service.init();

        Field endpointField = WhisperAsrServiceImpl.class.getDeclaredField("asrEndpoint");
        endpointField.setAccessible(true);
        String endpoint = endpointField.get(service).toString();
        Assert.assertTrue(endpoint.startsWith("http://127.0.0.1:9001/asr?"));
        Assert.assertTrue(endpoint.contains("output=json"));
        Assert.assertTrue(endpoint.contains("task=transcribe"));
        Assert.assertTrue(endpoint.contains("language=zh"));
        Assert.assertTrue(endpoint.contains("vad_filter=true"));
    }

    @Test
    public void shouldSendBearerTokenToWhisper() throws Exception {
        WhisperAsrServiceImpl service = new WhisperAsrServiceImpl();
        setField(service, "baseUrl", "http://127.0.0.1:9001");
        setField(service, "token", "test-token");
        setField(service, "language", "zh");
        service.init();

        Path audioFile = Files.createTempFile("whisper-token-", ".wav");
        try {
            Method method = WhisperAsrServiceImpl.class.getDeclaredMethod(
                    "buildTranscriptionRequest", File.class);
            method.setAccessible(true);
            Request request = (Request) method.invoke(service, audioFile.toFile());

            Assert.assertEquals("Bearer test-token", request.header("Authorization"));
        } finally {
            Files.deleteIfExists(audioFile);
        }
    }

    @Test
    public void shouldRejectBlankWhisperToken() throws Exception {
        WhisperAsrServiceImpl service = new WhisperAsrServiceImpl();
        setField(service, "baseUrl", "http://127.0.0.1:9001");
        setField(service, "token", "");
        setField(service, "language", "zh");

        try {
            service.init();
            Assert.fail("Blank Whisper token should be rejected");
        } catch (StreamerRecordException e) {
            Assert.assertEquals(ErrorEnum.INVALID_PARAM, e.getErrorEnum());
        }
    }

    @Test
    public void shouldConvertWhisperSegmentsToVideoRelativeTime() throws Exception {
        String response = "{\"segments\":["
                + "{\"start\":1.2,\"end\":2.1,\"text\":\" 发现人了 \"},"
                + "{\"start\":2.2,\"end\":3.0,\"text\":\"   \"},"
                + "{\"start\":3.1,\"text\":\"缺少结束时间\"}]}";

        List<AsrSegment> segments = parse(response, 120);

        Assert.assertEquals(1, segments.size());
        Assert.assertEquals(121, segments.get(0).getStartTime());
        Assert.assertEquals(123, segments.get(0).getEndTime());
        Assert.assertEquals("发现人了", segments.get(0).getText());
    }

    @Test
    public void shouldRejectInvalidWhisperJson() throws Exception {
        try {
            parse("not-json", 0);
            Assert.fail("invalid JSON must be rejected");
        } catch (InvocationTargetException e) {
            Assert.assertTrue(e.getCause() instanceof StreamerRecordException);
            StreamerRecordException cause = (StreamerRecordException) e.getCause();
            Assert.assertEquals(ErrorEnum.ASR_REQUEST_ERROR, cause.getErrorEnum());
        }
    }

    @SuppressWarnings("unchecked")
    private List<AsrSegment> parse(String response, int offset) throws Exception {
        WhisperAsrServiceImpl service = new WhisperAsrServiceImpl();
        Method method = WhisperAsrServiceImpl.class.getDeclaredMethod(
                "parseTranscription", String.class, int.class);
        method.setAccessible(true);
        return (List<AsrSegment>) method.invoke(service, response, offset);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = WhisperAsrServiceImpl.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
