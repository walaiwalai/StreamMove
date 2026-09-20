package com.sh.engine.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import okhttp3.HttpUrl;
import okhttp3.Request;
import org.apache.commons.lang3.StringUtils;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;

public class LlmServiceImplImageTest {

    @Test
    public void shouldDisableThinkingForDeepSeekChatRequest() throws Exception {
        LlmServiceImpl service = new LlmServiceImpl();
        setField(service, "chatApiKey", "test-key");
        setField(service, "chatModelName", "deepseek-v4-flash");
        setField(service, "chatCompletionEndpoint",
                HttpUrl.parse("https://api.deepseek.com/v1/chat/completions"));

        JSONObject requestBody = JSON.parseObject(createChatRequest(service, "只输出 JSON"));
        Assert.assertEquals("deepseek-v4-flash", requestBody.getString("model"));
        Assert.assertEquals("disabled",
                requestBody.getJSONObject("thinking").getString("type"));
        Assert.assertEquals("json_object",
                requestBody.getJSONObject("response_format").getString("type"));
        Assert.assertEquals("只输出 JSON", requestBody.getJSONArray("messages")
                .getJSONObject(0).getString("content"));
    }

    @Test
    public void shouldNotSendDeepSeekOptionToGenericChatEndpoint() throws Exception {
        LlmServiceImpl service = new LlmServiceImpl();
        setField(service, "chatApiKey", "test-key");
        setField(service, "chatModelName", "generic-model");
        setField(service, "chatCompletionEndpoint",
                HttpUrl.parse("https://example.com/v1/chat/completions"));

        JSONObject requestBody = JSON.parseObject(createChatRequest(service, "只输出 JSON"));
        Assert.assertFalse(requestBody.containsKey("thinking"));
    }

    @Test
    public void shouldBuildQwenImageEditRequest() throws Exception {
        LlmServiceImpl service = new LlmServiceImpl();
        setField(service, "imageModelName", "qwen-image-3.0");

        JSONObject request = JSON.parseObject(createImageEditRequest(
                service, new byte[]{1, 2, 3}, "enhance this frame"));
        Assert.assertEquals("qwen-image-3.0", request.getString("model"));
        JSONArray content = request.getJSONObject("input")
                .getJSONArray("messages").getJSONObject(0).getJSONArray("content");
        Assert.assertTrue(content.getJSONObject(0).getString("image")
                .startsWith("data:image/jpeg;base64,"));
        Assert.assertEquals("enhance this frame", content.getJSONObject(1).getString("text"));
        Assert.assertEquals("1280*720",
                request.getJSONObject("parameters").getString("size"));
        Assert.assertFalse(request.getJSONObject("parameters")
                .getBooleanValue("prompt_extend"));
        Assert.assertFalse(request.getJSONObject("parameters").getBooleanValue("watermark"));
    }

    @Test
    public void missingQwenApiKeyShouldUseDomainError() throws Exception {
        LlmServiceImpl service = new LlmServiceImpl();
        setField(service, "imageApiKey", "");
        setField(service, "imageModelName", "qwen-image-3.0");

        try {
            service.editImage(new byte[]{1}, "enhance this frame");
            Assert.fail("Missing Qwen API key should fail");
        } catch (StreamerRecordException e) {
            Assert.assertEquals(ErrorEnum.INVALID_PARAM, e.getErrorEnum());
        }
    }

    @Test
    public void shouldParseQwenImageUrlResponse() throws Exception {
        String response = "{\"output\":{\"choices\":[{\"message\":{\"content\":["
                + "{\"image\":\"https://example.com/generated.png\"}]}}]}}";

        Assert.assertEquals("https://example.com/generated.png",
                parseImageUrl(new LlmServiceImpl(), response));
    }

    @Test
    public void invalidImageResponseShouldUseDomainError() throws Exception {
        try {
            parseImageUrl(new LlmServiceImpl(), "{\"output\":{\"choices\":[]}}");
            Assert.fail("Empty image data should fail");
        } catch (StreamerRecordException e) {
            Assert.assertEquals(ErrorEnum.COVER_GENERATION_ERROR, e.getErrorEnum());
        }
    }

    /** 使用真实 DashScope Key 验证统一 LlmService 的千问图片编辑链路，默认不执行。 */
    @Test
    public void shouldEditImageWithQwenWhenIntegrationTestIsEnabled() throws Exception {
        Assume.assumeTrue("Qwen image integration test is disabled",
                "true".equalsIgnoreCase(System.getenv("RUN_QWEN_IMAGE_INTEGRATION")));
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        Assume.assumeTrue("DASHSCOPE_API_KEY is missing", StringUtils.isNotBlank(apiKey));

        LlmServiceImpl service = new LlmServiceImpl();
        setField(service, "chatApiKey", "unused-in-image-test");
        setField(service, "chatBaseUrl", "https://api.deepseek.com/v1");
        setField(service, "chatModelName", "deepseek-chat");
        setField(service, "proxyUrl", "");
        setField(service, "imageApiKey", apiKey);
        setField(service, "imageBaseUrl", StringUtils.defaultIfBlank(
                System.getenv("QWEN_IMAGE_BASE_URL"),
                "https://dashscope.aliyuncs.com/api/v1/services/aigc/"
                        + "multimodal-generation/generation"));
        setField(service, "imageModelName", "qwen-image-3.0");
        service.init();

        byte[] editedImage = service.editImage(createSourceImage(),
                "Enhance contrast and lighting while preserving every object. Do not add text.");
        BufferedImage decodedImage = ImageIO.read(new ByteArrayInputStream(editedImage));
        Assert.assertNotNull(decodedImage);
        Assert.assertEquals(1280, decodedImage.getWidth());
        Assert.assertEquals(720, decodedImage.getHeight());

        String outputPath = System.getenv("QWEN_IMAGE_TEST_OUTPUT");
        if (StringUtils.isNotBlank(outputPath)) {
            Files.write(Paths.get(outputPath), editedImage);
        }
    }

    private byte[] createSourceImage() throws Exception {
        BufferedImage image = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(30, 45, 70));
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setColor(new Color(220, 120, 45));
        graphics.fillOval(430, 150, 420, 420);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", output);
        return output.toByteArray();
    }

    private String createImageEditRequest(
            LlmServiceImpl service, byte[] image, String prompt) throws Exception {
        Method method = LlmServiceImpl.class.getDeclaredMethod(
                "createImageEditRequest", byte[].class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(service, image, prompt);
    }

    private String createChatRequest(LlmServiceImpl service, String prompt) throws Exception {
        Method method = LlmServiceImpl.class.getDeclaredMethod("createChatRequest", String.class);
        method.setAccessible(true);
        Request request = (Request) method.invoke(service, prompt);
        okio.Buffer body = new okio.Buffer();
        request.body().writeTo(body);
        return body.readUtf8();
    }

    private String parseImageUrl(LlmServiceImpl service, String response) throws Exception {
        Method method = LlmServiceImpl.class.getDeclaredMethod("parseImageUrl", String.class);
        method.setAccessible(true);
        try {
            return (String) method.invoke(service, response);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
