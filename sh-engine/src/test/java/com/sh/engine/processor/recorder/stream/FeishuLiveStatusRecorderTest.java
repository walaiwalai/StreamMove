package com.sh.engine.processor.recorder.stream;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.sh.config.manager.CacheManager;
import com.sh.engine.constant.RecordTaskStateEnum;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.model.RecordContext;
import com.sh.engine.processor.StreamRecordStageProcessor;
import com.sh.message.service.feishu.FeishuBitableClient;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Date;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FeishuLiveStatusRecorderTest {
    @Test
    public void recognizesBitableLinksInWiki() {
        assertEquals(StreamChannelTypeEnum.FEISHU_BITABLE_MONITOR,
                StreamChannelTypeEnum.findChannelByUrl("https://example.feishu.cn/wiki/wikiNode123"));
        assertEquals(StreamChannelTypeEnum.FEISHU_BITABLE_MONITOR,
                StreamChannelTypeEnum.findChannelByUrl("https://example.feishu.cn/base/bas123"));
    }

    @Test
    public void unresolvedCredentialPlaceholdersDoNotPreventBeanStartup() {
        StandardEnvironment environment = new StandardEnvironment();
        Map<String, Object> properties = new HashMap<>();
        properties.put("feishu.app-id", "${feishu.app-id}");
        properties.put("feishu.app-secret", "${feishu.app-secret}");
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(environment);
            context.register(FeishuBitableClient.class);
            context.refresh();
            assertFalse(context.getBean(FeishuBitableClient.class).isConfigured());
        }
    }

    @Test
    public void configuredCredentialsActivateClient() {
        StandardEnvironment environment = new StandardEnvironment();
        Map<String, Object> properties = new HashMap<>();
        properties.put("feishu.app-id", "cli_test");
        properties.put("feishu.app-secret", "secret");
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));

        assertTrue(new FeishuBitableClient(environment).isConfigured());
    }

    @Test
    public void monitorStageSkipsMediaPreparationAndFinishes() {
        MemoryCache cache = new MemoryCache();
        RecordingClient client = new RecordingClient();
        FeishuLiveStatusRecorder.RoomObservation observation = new FeishuLiveStatusRecorder.RoomObservation(
                "主播A", "https://live.example.com/1", true, new Date(1_000L));
        FeishuLiveStatusRecorder recorder = new FeishuLiveStatusRecorder(new Date(1_000L),
                "https://example.feishu.cn/base/bas123", "bas123",
                Collections.singletonList(observation), cache, client);
        RecordContext context = new RecordContext();
        context.setState(RecordTaskStateEnum.ROOM_CHECK_FINISH);
        context.setChannelEnum(StreamChannelTypeEnum.FEISHU_BITABLE_MONITOR);
        context.setStreamRecorder(recorder);

        new StreamRecordStageProcessor().process(context);

        assertEquals(RecordTaskStateEnum.END, context.getState());
        assertEquals(1, cache.values.size());
    }

    @Test
    public void writesOneRecordForRepeatedLivePolls() {
        MemoryCache cache = new MemoryCache();
        RecordingClient client = new RecordingClient();

        poll(cache, client, 1_000L, true);
        poll(cache, client, 2_000L, true);
        poll(cache, client, 3_000L, false);
        poll(cache, client, 4_000L, false);
        poll(cache, client, 5_000L, false);
        poll(cache, client, 6_000L, false);
        assertEquals(0, client.writeCount);
        poll(cache, client, 7_000L, false);
        poll(cache, client, 8_000L, false);

        assertEquals(1, client.writeCount);
        assertEquals("主播A", client.name);
        assertEquals(1_000L, client.start);
        assertEquals(3_000L, client.end);
        assertEquals(0, cache.values.size());
    }

    @Test
    public void liveStatusCancelsPendingOfflineConfirmation() {
        MemoryCache cache = new MemoryCache();
        RecordingClient client = new RecordingClient();

        poll(cache, client, 1_000L, true);
        poll(cache, client, 2_000L, false);
        poll(cache, client, 3_000L, false);
        poll(cache, client, 4_000L, true);
        poll(cache, client, 5_000L, false);
        poll(cache, client, 6_000L, false);
        poll(cache, client, 7_000L, false);
        poll(cache, client, 8_000L, false);
        assertEquals(0, client.writeCount);
        poll(cache, client, 9_000L, false);

        assertEquals(1, client.writeCount);
        assertEquals(1_000L, client.start);
        assertEquals(5_000L, client.end);
    }

    @Test
    public void confirmsOfflineForSessionCachedBeforeUpgrade() {
        MemoryCache cache = new MemoryCache();
        RecordingClient client = new RecordingClient();
        String key = "feishu:live:session:"
                + DigestUtils.sha256Hex("bas123|https://live.example.com/1");
        cache.values.put(key, "{\"streamerName\":\"主播A\",\"startTimeMillis\":1000}");

        for (int i = 0; i < 5; i++) {
            poll(cache, client, 2_000L + i * 1_000L, false);
        }

        assertEquals(1, client.writeCount);
        assertEquals(1_000L, client.start);
        assertEquals(2_000L, client.end);
    }

    @Test
    public void retriesWithOriginalEndTimeAfterWriteFailure() {
        MemoryCache cache = new MemoryCache();
        RecordingClient client = new RecordingClient();
        client.failNextWrite = true;

        poll(cache, client, 1_000L, true);
        poll(cache, client, 3_000L, false);
        poll(cache, client, 4_000L, false);
        poll(cache, client, 5_000L, false);
        poll(cache, client, 6_000L, false);
        poll(cache, client, 7_000L, false);
        assertEquals(1, cache.values.size());
        assertEquals(0, client.writeCount);

        poll(cache, client, 8_000L, false);
        assertEquals(1, client.writeCount);
        assertEquals(1_000L, client.start);
        assertEquals(3_000L, client.end);
        assertEquals(0, cache.values.size());
    }

    private static void poll(MemoryCache cache, RecordingClient client, long time, boolean live) {
        FeishuLiveStatusRecorder.RoomObservation observation = new FeishuLiveStatusRecorder.RoomObservation(
                "主播A", "https://live.example.com/1", live, new Date(time));
        new FeishuLiveStatusRecorder(new Date(time), "https://example.feishu.cn/base/bas123", "bas123",
                Collections.singletonList(observation), cache, client).start(null);
    }

    private static class MemoryCache extends CacheManager {
        private final Map<String, String> values = new HashMap<>();

        @Override
        public void set(String key, Object value) {
            values.put(key, JSON.toJSONString(value));
        }

        @Override
        public <T> T get(String key, TypeReference<T> typeReference) {
            String value = values.get(key);
            return value == null ? null : JSON.parseObject(value, typeReference);
        }

        @Override
        public void delete(String key) {
            values.remove(key);
        }
    }

    private static class RecordingClient extends FeishuBitableClient {
        private RecordingClient() {
            super(new StandardEnvironment());
        }

        private int writeCount;
        private boolean failNextWrite;
        private String name;
        private long start;
        private long end;

        @Override
        public void createLiveRecord(String baseToken, String name, long startTimeMillis, long endTimeMillis) {
            if (failNextWrite) {
                failNextWrite = false;
                throw new IllegalStateException("simulated Feishu failure");
            }
            writeCount++;
            this.name = name;
            this.start = startTimeMillis;
            this.end = endTimeMillis;
        }
    }
}
