package com.sh.config.manager;

import com.sh.config.mapper.CommonParamMapper;
import com.sh.config.model.config.CommonParamKey;
import com.sh.config.model.dao.CommonParamDO;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CommonParamCacheTest {
    private CommonParamCache cache;
    private CommonParamMapper mapper;
    private CacheManager cacheManager;

    @Before
    public void setUp() throws Exception {
        cache = new CommonParamCache();
        mapper = mock(CommonParamMapper.class);
        cacheManager = mock(CacheManager.class);
        setField("commonParamMapper", mapper);
        setField("cacheManager", cacheManager);
        setField("currentEnv", "prod");
    }

    @Test
    public void readsOnlyCurrentEnvironmentHash() {
        when(cacheManager.getHashValue("streamCommonParam:prod", "biliSeasonCoverUrl"))
                .thenReturn("covers/season.jpg")
                .thenReturn(null);

        assertEquals("covers/season.jpg", cache.get(CommonParamKey.BILI_SEASON_COVER_URL));
        assertNull(cache.get(CommonParamKey.BILI_SEASON_COVER_URL));
    }

    @Test
    public void refreshReplacesTheWholeSnapshot() {
        CommonParamDO prod = param("prod", "biliSeasonCoverUrl", "covers/season.jpg");
        when(mapper.selectByEnv("prod")).thenReturn(Collections.singletonList(prod));

        cache.refresh();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> values = ArgumentCaptor.forClass(Map.class);
        verify(cacheManager).replaceHash(eq("streamCommonParam:prod"), values.capture());
        Map<String, String> expected = new HashMap<>();
        expected.put("biliSeasonCoverUrl", "covers/season.jpg");
        assertEquals(expected, values.getValue());
    }

    @Test
    public void emptySnapshotRemovesOldParameters() {
        when(mapper.selectByEnv("prod")).thenReturn(Collections.emptyList());

        cache.refresh();

        verify(cacheManager).replaceHash("streamCommonParam:prod", Collections.emptyMap());
    }

    @Test
    public void integerParameterUsesEnvironmentValueAndDefault() {
        when(cacheManager.getHashValue("streamCommonParam:prod", "videoPartLimitSize"))
                .thenReturn("100")
                .thenReturn(null);

        assertEquals(100, cache.getInt(CommonParamKey.VIDEO_PART_LIMIT_SIZE, 0));
        assertEquals(0, cache.getInt(CommonParamKey.VIDEO_PART_LIMIT_SIZE, 0));
    }

    @Test
    public void stringListParameterParsesJsonAndDefaultsToEmpty() {
        when(cacheManager.getHashValue("streamCommonParam:prod", "outApiRoomCheckPlatforms"))
                .thenReturn("[\"TWITCH\",\"YOUTUBE\"]")
                .thenReturn(null);

        assertEquals(Arrays.asList("TWITCH", "YOUTUBE"),
                cache.getStringList(CommonParamKey.OUT_API_ROOM_CHECK_PLATFORMS));
        assertEquals(Collections.emptyList(),
                cache.getStringList(CommonParamKey.OUT_API_ROOM_CHECK_PLATFORMS));
    }

    private CommonParamDO param(String env, String key, String value) {
        CommonParamDO param = new CommonParamDO();
        param.setEnv(env);
        param.setKey(key);
        param.setValue(value);
        return param;
    }

    private void setField(String name, Object value) throws Exception {
        Field field = CommonParamCache.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(cache, value);
    }
}
