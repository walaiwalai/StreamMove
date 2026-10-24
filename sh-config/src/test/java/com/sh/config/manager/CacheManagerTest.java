package com.sh.config.manager;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CacheManagerTest {
    private CacheManager cacheManager;
    private RedisTemplate<String, Object> redisTemplate;
    private HashOperations<String, String, String> hashOps;

    @SuppressWarnings("unchecked")
    @Before
    public void setUp() throws Exception {
        cacheManager = new CacheManager();
        redisTemplate = mock(RedisTemplate.class);
        hashOps = mock(HashOperations.class);
        setField("redisTemplate", redisTemplate);
        setField("hashOps", hashOps);
    }

    @Test
    public void replaceHashPublishesCompleteSnapshot() {
        Map<String, String> values = new HashMap<>();
        values.put("cover", "https://example.com/cover.jpg");

        cacheManager.replaceHash("streamCommonParam:prod", values);

        ArgumentCaptor<String> loadingKey = ArgumentCaptor.forClass(String.class);
        verify(hashOps).putAll(loadingKey.capture(), eq(values));
        verify(redisTemplate).rename(loadingKey.getValue(), "streamCommonParam:prod");
    }

    @Test
    public void replaceHashDeletesEmptySnapshot() {
        cacheManager.replaceHash("streamCommonParam:prod", Collections.emptyMap());

        verify(redisTemplate).delete("streamCommonParam:prod");
    }

    @Test
    public void getHashValueReadsOneField() {
        when(hashOps.get("streamCommonParam:prod", "cover"))
                .thenReturn("https://example.com/cover.jpg");

        assertEquals("https://example.com/cover.jpg",
                cacheManager.getHashValue("streamCommonParam:prod", "cover"));
    }

    private void setField(String name, Object value) throws Exception {
        Field field = CacheManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(cacheManager, value);
    }
}
