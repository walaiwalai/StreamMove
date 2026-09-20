package com.sh.engine.processor.plugin.highlight;

import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class OpenAiCoverClientTest {
    @Test
    public void shouldInitializeOfficialImageEditEndpoint() throws Exception {
        OpenAiCoverClient client = configuredClient();

        client.init();

        Field endpoint = OpenAiCoverClient.class.getDeclaredField("editEndpoint");
        endpoint.setAccessible(true);
        Assert.assertEquals("https://api.openai.com/v1/images/edits",
                endpoint.get(client).toString());
        Assert.assertTrue(client.isEnabled());
    }

    @Test
    public void shouldDecodeBase64ImageResponse() throws Exception {
        byte[] expected = "jpeg-data".getBytes(StandardCharsets.UTF_8);
        String response = "{\"data\":[{\"b64_json\":\""
                + Base64.getEncoder().encodeToString(expected) + "\"}]}";

        Assert.assertArrayEquals(expected, parseImage(new OpenAiCoverClient(), response));
    }

    @Test
    public void shouldRejectResponseWithoutImage() throws Exception {
        try {
            parseImage(new OpenAiCoverClient(), "{\"data\":[]}");
            Assert.fail("response without image must be rejected");
        } catch (InvocationTargetException e) {
            Assert.assertTrue(e.getCause() instanceof StreamerRecordException);
            StreamerRecordException cause = (StreamerRecordException) e.getCause();
            Assert.assertEquals(ErrorEnum.COVER_GENERATION_ERROR, cause.getErrorEnum());
        }
    }

    private OpenAiCoverClient configuredClient() throws Exception {
        OpenAiCoverClient client = new OpenAiCoverClient();
        setField(client, "provider", "openai");
        setField(client, "apiKey", "test-key");
        setField(client, "baseUrl", "https://api.openai.com/v1/");
        setField(client, "model", "gpt-image-2");
        setField(client, "size", "1280x720");
        setField(client, "quality", "medium");
        setField(client, "connectTimeoutSeconds", 20L);
        setField(client, "callTimeoutSeconds", 180L);
        return client;
    }

    private byte[] parseImage(OpenAiCoverClient client, String response) throws Exception {
        Method method = OpenAiCoverClient.class.getDeclaredMethod("parseImage", String.class);
        method.setAccessible(true);
        return (byte[]) method.invoke(client, response);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = OpenAiCoverClient.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
