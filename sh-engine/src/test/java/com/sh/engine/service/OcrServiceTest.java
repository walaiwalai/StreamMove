package com.sh.engine.service;

import cn.hutool.extra.spring.SpringUtil;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.env.MapPropertySource;

import com.sh.engine.model.highlight.core.OcrTextDetection;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class OcrServiceTest {
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

    @Test
    public void sharedOcrUsesExistingMultipartProtocolAndParsesBoxes() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            OcrService service = new OcrService();
            set(service, "host", "127.0.0.1"); set(service, "port", String.valueOf(server.getPort())); set(service, "token", "test-token");
            server.enqueue(new MockResponse().setBody("[{\"text\":\"100\",\"score\":0.9,\"boxes\":[1,2,3,4]}]"));
            List<OcrTextDetection> result = service.recognize(new byte[]{1,2,3}, "frame.jpg");
            assertEquals("100", result.get(0).getText()); assertEquals(4, result.get(0).getBoxes().size());
            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertEquals("/ocrDet", request.getPath()); assertEquals("Bearer test-token", request.getHeader("Authorization"));
            assertTrue(request.getBody().readUtf8().contains("name=\"image\"; filename=\"frame.jpg\""));
        }
    }

    private void set(Object bean, String name, Object value) throws Exception {
        Field field = bean.getClass().getDeclaredField(name); field.setAccessible(true); field.set(bean, value);
    }
}
