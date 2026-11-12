package com.sh.engine.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class LlmServiceImplTest {
    private MockWebServer server;
    private LlmServiceImpl service;

    @Before public void start() throws Exception {
        server = new MockWebServer(); server.start();
        service = new LlmServiceImpl();
        set("apiKey", "test-model-key");
        set("baseUrl", server.url("/v1/").toString());
        set("modelName", "self-hosted-vision");
        service.init();
    }

    @After public void stop() throws Exception { server.shutdown(); }

    @Test public void oldOpenAiSettingsSendAllImagesWithoutHarnessOrConversationHistory() throws Exception {
        List<Content> content = new ArrayList<>();
        content.add(TextContent.from("original prompt and evidence"));
        for (int i = 0; i < 26; i++) {
            content.add(TextContent.from("frame " + i));
            content.add(ImageContent.from("image-data-" + i, "image/jpeg"));
        }
        server.enqueue(reply("stop", "{\"keep\":false,\"score\":3}"));
        server.enqueue(reply("stop", "{\"keep\":false,\"score\":3}"));
        JSONObject first = service.chat(content), second = service.chat(content);
        assertTrue(service.isEnabled());
        assertNotEquals(first.getString("requestId"), second.getString("requestId"));
        assertEquals("openai-compatible-http", first.getString("transport"));
        assertEquals("STOP", first.getString("finishReason"));
        assertEquals("self-hosted-vision", first.getString("requestedModel"));
        assertFalse(first.containsKey("responseModel"));
        assertFalse(first.containsKey("sessionId"));
        assertFalse(first.toJSONString().contains("test-model-key"));
        for (int call = 0; call < 2; call++) {
            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("/v1/chat/completions", request.getPath());
            assertEquals("Bearer test-model-key", request.getHeader("Authorization"));
            assertNull(request.getHeader("Cookie"));
            JSONObject body = JSON.parseObject(request.getBody().readUtf8());
            assertEquals("self-hosted-vision", body.getString("model"));
            JSONArray messages = body.getJSONArray("messages");
            assertEquals(1, messages.size());
            assertEquals("user", messages.getJSONObject(0).getString("role"));
            JSONArray blocks = messages.getJSONObject(0).getJSONArray("content");
            assertEquals(53, blocks.size());
            assertEquals("original prompt and evidence", blocks.getJSONObject(0).getString("text"));
            for (int i = 0; i < 26; i++) {
                assertEquals("frame " + i, blocks.getJSONObject(1 + i * 2).getString("text"));
                assertEquals("data:image/jpeg;base64,image-data-" + i,
                        blocks.getJSONObject(2 + i * 2).getJSONObject("image_url").getString("url"));
            }
        }
    }

    @Test public void ordinaryTextCallsKeepTheirExistingParsingAndEndpoint() throws Exception {
        server.enqueue(reply("stop", "prefix {\"ok\":true} suffix"));
        assertTrue(service.chat("ordinary text request", JSONObject.class).getBooleanValue("ok"));
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertEquals("/v1/chat/completions", request.getPath());
        JSONObject body = JSON.parseObject(request.getBody().readUtf8());
        assertEquals("ordinary text request", body.getJSONArray("messages").getJSONObject(0).getString("content"));
    }

    @Test public void truncatedFilteredAndToolRepliesAreNotCompletedReviews() throws Exception {
        for (String reason : new String[]{"length", "content_filter", "tool_calls"}) {
            server.enqueue(reply(reason, "{\"keep\":true}"));
            expectMultimodalFailure();
        }
        assertEquals(3, server.getRequestCount());
    }

    @Test public void emptyReplyIsNotACompletedReview() throws Exception {
        server.enqueue(reply("stop", " "));
        expectMultimodalFailure();
    }

    @Test public void authenticationFailureDoesNotRetryOrFallBack() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"message\":\"unauthorized\",\"type\":\"authentication_error\"}}"));
        expectMultimodalFailure();
        assertEquals(1, server.getRequestCount());
    }

    @Test public void missingOldSettingsRemainOptionalUntilInvoked() throws Exception {
        service = new LlmServiceImpl(); service.init();
        assertFalse(service.isEnabled());
        expectMultimodalFailure();
        assertEquals(0, server.getRequestCount());
    }

    private void expectMultimodalFailure() throws Exception {
        try { service.chat(Collections.singletonList(TextContent.from("review"))); fail("incomplete reply accepted"); }
        catch (IOException expected) { assertFalse(expected.toString().contains("test-model-key")); }
    }

    private MockResponse reply(String reason, String text) {
        JSONObject message = new JSONObject(); message.put("role", "assistant"); message.put("content", text);
        JSONObject choice = new JSONObject(); choice.put("index", 0); choice.put("message", message); choice.put("finish_reason", reason);
        JSONObject body = new JSONObject(); body.put("id", "server-response-id"); body.put("model", "server-model-name");
        body.put("object", "chat.completion"); body.put("choices", Collections.singletonList(choice));
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body.toJSONString());
    }

    private void set(String name, String value) throws Exception {
        java.lang.reflect.Field field = LlmServiceImpl.class.getDeclaredField(name);
        field.setAccessible(true); field.set(service, value);
    }
}
