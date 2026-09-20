package com.sh.engine.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sh.engine.model.llm.LlmImageInput;
import com.sh.engine.model.llm.LlmResponseException;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okio.Buffer;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class LlmServiceImplVisionTest {

    @Test
    public void shouldBuildDeepSeekMultiImageRequest() throws Exception {
        LlmServiceImpl service = new LlmServiceImpl();
        setField(service, "visionModelName", "deepseek-v4-flash-vision-exp");
        setField(service, "chatApiKey", "test-key");
        setField(service, "chatCompletionEndpoint",
                HttpUrl.parse("https://api.deepseek.com/chat/completions"));
        Method method = LlmServiceImpl.class.getDeclaredMethod(
                "createVisionRequest", String.class, java.util.List.class);
        method.setAccessible(true);

        Request request = (Request) method.invoke(service, "只记录可见事实", Arrays.asList(
                new LlmImageInput("frame-1.jpg", new byte[]{1, 2, 3}),
                new LlmImageInput("frame-2.jpg", new byte[]{4, 5, 6})));
        Buffer buffer = new Buffer();
        request.body().writeTo(buffer);
        JSONObject body = JSON.parseObject(buffer.readUtf8());

        Assert.assertEquals("deepseek-v4-flash-vision-exp", body.getString("model"));
        Assert.assertEquals("disabled", body.getJSONObject("thinking").getString("type"));
        Assert.assertEquals("json_object",
                body.getJSONObject("response_format").getString("type"));
        Assert.assertEquals(5000, body.getIntValue("max_tokens"));
        JSONArray content = body.getJSONArray("messages")
                .getJSONObject(0).getJSONArray("content");
        Assert.assertEquals("text", content.getJSONObject(0).getString("type"));
        Assert.assertEquals(3, content.size());
        Assert.assertEquals("image_url", content.getJSONObject(1).getString("type"));
        Assert.assertEquals("original", content.getJSONObject(1)
                .getJSONObject("image_url").getString("detail"));
        Assert.assertTrue(content.getJSONObject(1).getJSONObject("image_url")
                .getString("url").startsWith("data:image/jpeg;base64,"));
    }

    @Test
    public void shouldKeepProviderEnvelopeWhenStructuredJsonIsTruncated()
            throws Exception {
        LlmServiceImpl service = new LlmServiceImpl();
        String envelope = "{\"choices\":[{\"message\":{"
                + "\"content\":\"{\\\"highlight\\\":true\"}}]}";
        Class<?> responseType = Class.forName(
                LlmServiceImpl.class.getName() + "$RawChatResponse");
        java.lang.reflect.Constructor<?> constructor = responseType
                .getDeclaredConstructor(String.class, String.class);
        constructor.setAccessible(true);
        Object response = constructor.newInstance(
                envelope, "{\"highlight\":true");
        Method method = LlmServiceImpl.class.getDeclaredMethod(
                "toCallResult", responseType, Class.class);
        method.setAccessible(true);

        try {
            method.invoke(service, response, JSONObject.class);
            Assert.fail("expected truncated JSON to fail");
        } catch (InvocationTargetException e) {
            Assert.assertTrue(e.getCause() instanceof LlmResponseException);
            Assert.assertEquals(envelope,
                    ((LlmResponseException) e.getCause()).getRawEnvelope());
        }
    }

    @Test
    public void shouldAcceptThirtyTwoImagesForDenseVisualReview() throws Exception {
        LlmServiceImpl service = serviceWithVisionModel();

        invokeVisionValidation(service, images(32));
    }

    @Test
    public void shouldRejectImageCountAboveLocalRequestLimit() throws Exception {
        LlmServiceImpl service = serviceWithVisionModel();

        try {
            invokeVisionValidation(service, images(49));
            Assert.fail("expected excessive image count to fail");
        } catch (InvocationTargetException e) {
            Assert.assertTrue(e.getCause().getMessage().contains("49/48"));
        }
    }

    private LlmServiceImpl serviceWithVisionModel() throws Exception {
        LlmServiceImpl service = new LlmServiceImpl();
        setField(service, "visionModelName", "deepseek-v4-flash-vision-exp");
        return service;
    }

    private List<LlmImageInput> images(int count) {
        List<LlmImageInput> images = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            images.add(new LlmImageInput("frame-" + index + ".jpg", new byte[]{1}));
        }
        return images;
    }

    private void invokeVisionValidation(
            LlmServiceImpl service, List<LlmImageInput> images) throws Exception {
        Method method = LlmServiceImpl.class.getDeclaredMethod(
                "validateVisionRequest", String.class, List.class, Class.class);
        method.setAccessible(true);
        method.invoke(service, "输出 JSON", images, JSONObject.class);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
