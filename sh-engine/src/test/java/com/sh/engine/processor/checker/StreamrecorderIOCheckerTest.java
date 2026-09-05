package com.sh.engine.processor.checker;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.manager.CacheBizManager;
import com.sh.engine.processor.recorder.stream.StreamRecorder;
import com.sh.engine.processor.recorder.stream.RangeVodStreamRecorder;
import org.apache.commons.lang3.time.DateUtils;
import org.junit.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class StreamrecorderIOCheckerTest {

    @Test
    public void cachesAllRunningRecordsAndWaits() throws Exception {
        TestCacheBizManager cache = new TestCacheBizManager();
        StreamrecorderIOChecker checker = checkerWithCache(cache);

        StreamRecorder recorder = fetchLatestRecord(checker, config(null),
                record("2", "running", "2026-08-28T19:47:50", 3600, null),
                record("1", "running", "2026-08-28T19:41:51", 3900, null));

        assertNull(recorder);
        assertEquals(ids("1", "2"), cache.runningIds);
    }

    @Test
    public void addsNewRunningRecordToActiveGroup() throws Exception {
        TestCacheBizManager cache = new TestCacheBizManager();
        cache.runningIds = ids("1");
        StreamrecorderIOChecker checker = checkerWithCache(cache);

        StreamRecorder recorder = fetchLatestRecord(checker, config(null),
                record("2", "running", "2026-08-28T19:47:50", 3600, null),
                record("1", "running", "2026-08-28T19:41:51", 3900, null));

        assertNull(recorder);
        assertEquals(ids("1", "2"), cache.runningIds);
    }

    @Test
    public void waitsWhileCachedRecordIsStillRunning() throws Exception {
        TestCacheBizManager cache = new TestCacheBizManager();
        cache.runningIds = ids("1", "2");
        StreamrecorderIOChecker checker = checkerWithCache(cache);

        StreamRecorder recorder = fetchLatestRecord(checker, config(null),
                record("2", "running", "2026-08-28T19:47:50", 3600, null),
                record("1", "finished", "2026-08-28T19:41:51", 3900, "one"));

        assertNull(recorder);
    }

    @Test
    public void downloadsLongestCachedRecordUsingLatestRecordedAt() throws Exception {
        TestCacheBizManager cache = new TestCacheBizManager();
        cache.runningIds = ids("188481636", "188478294", "188473113");
        StreamrecorderIOChecker checker = checkerWithCache(cache);

        StreamRecorder recorder = fetchLatestRecord(checker, config(lastRecordAt("2026-08-25T00:00:00")),
                record("188481636", "finished", "2026-08-26T21:55:50", 4 * 3600 + 12 * 60 + 40, "latest-link"),
                record("188478294", "finished", "2026-08-26T21:47:50", 4 * 3600 + 19 * 60 + 30, "middle-link"),
                record("188473113", "finished", "2026-08-26T21:37:51", 4 * 3600 + 29 * 60 + 14, "longest-link"));

        assertNotNull(recorder);
        assertEquals(lastRecordAt("2026-08-26T21:55:50"), recorder.getRegDate());
        assertEquals("longest-link", getStreamUrl(recorder));
    }

    @Test
    public void downloadsOnlyLatestFinishedRecordWithoutRunningCache() throws Exception {
        TestCacheBizManager cache = new TestCacheBizManager();
        StreamrecorderIOChecker checker = checkerWithCache(cache);

        StreamRecorder recorder = fetchLatestRecord(checker, config(lastRecordAt("2026-08-25T00:00:00")),
                record("28", "finished", "2026-08-28T20:00:00", 1800, "day-28"),
                record("27", "finished", "2026-08-27T20:00:00", 3600, "day-27"),
                record("26", "finished", "2026-08-26T20:00:00", 7200, "day-26"));

        assertNotNull(recorder);
        assertEquals(lastRecordAt("2026-08-28T20:00:00"), recorder.getRegDate());
        assertEquals("day-28", getStreamUrl(recorder));
    }

    @Test
    public void doesNotDownloadOlderFinishedRecordWhileLatestIsRunning() throws Exception {
        TestCacheBizManager cache = new TestCacheBizManager();
        StreamrecorderIOChecker checker = checkerWithCache(cache);

        StreamRecorder recorder = fetchLatestRecord(checker, config(lastRecordAt("2026-08-25T00:00:00")),
                record("28", "running", "2026-08-28T20:00:00", 1800, null),
                record("26", "finished", "2026-08-26T20:00:00", 7200, "day-26"));

        assertNull(recorder);
        assertEquals(ids("28"), cache.runningIds);
    }

    @Test
    public void clearsCachedGroupAlreadyCoveredByLastRecordTime() throws Exception {
        TestCacheBizManager cache = new TestCacheBizManager();
        cache.runningIds = ids("1", "2");
        StreamrecorderIOChecker checker = checkerWithCache(cache);

        StreamRecorder recorder = fetchLatestRecord(checker, config(lastRecordAt("2026-08-28T20:00:00")),
                record("2", "finished", "2026-08-28T20:00:00", 1800, "two"),
                record("1", "finished", "2026-08-28T19:00:00", 3600, "one"));

        assertNull(recorder);
        assertEquals(ids(), cache.runningIds);
    }

    @Test
    public void clearsCacheWhenCachedRecordIsMissingFromResponse() throws Exception {
        TestCacheBizManager cache = new TestCacheBizManager();
        cache.runningIds = ids("1", "2");
        StreamrecorderIOChecker checker = checkerWithCache(cache);

        StreamRecorder recorder = fetchLatestRecord(checker, config(null),
                record("2", "finished", "2026-08-28T20:00:00", 1800, "two"));

        assertNotNull(recorder);
        assertEquals(ids(), cache.runningIds);
        assertEquals("two", getStreamUrl(recorder));
    }

    @Test
    public void downloadsHighestResolutionImmediatelyWhenAtLeast1080p() throws Exception {
        TestCacheBizManager cache = new TestCacheBizManager();
        cache.waitStartTime = System.currentTimeMillis();
        StreamrecorderIOChecker checker = checkerWithCache(cache);
        JSONObject record = recordWithSources(
                source(2160, null),
                source(1080, "1080-link"),
                source(1088, "1088-link"));

        assertEquals("1088-link", resolveDownloadLink(checker, record, "video-id"));
        assertNull(cache.waitStartTime);
    }

    @Test
    public void waitsWhenHighestResolutionIsBelow1080p() throws Exception {
        TestCacheBizManager cache = new TestCacheBizManager();
        StreamrecorderIOChecker checker = checkerWithCache(cache);
        JSONObject record = recordWithSources(source(480, "480-link"), source(720, "720-link"));

        assertNull(resolveDownloadLink(checker, record, "video-id"));
        assertNotNull(cache.waitStartTime);
    }

    @Test
    public void downloadsHighestAvailableResolutionAfterWaitTimeout() throws Exception {
        TestCacheBizManager cache = new TestCacheBizManager();
        cache.waitStartTime = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(31);
        StreamrecorderIOChecker checker = checkerWithCache(cache);
        JSONObject record = recordWithSources(source(480, "480-link"), source(720, "720-link"));

        assertEquals("720-link", resolveDownloadLink(checker, record, "video-id"));
        assertNull(cache.waitStartTime);
    }

    @Test
    public void keepsWaitingAfterTimeoutWhenNoDownloadLinkExists() throws Exception {
        TestCacheBizManager cache = new TestCacheBizManager();
        cache.waitStartTime = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(31);
        StreamrecorderIOChecker checker = checkerWithCache(cache);
        JSONObject record = recordWithSources(source(1080, null));

        assertNull(resolveDownloadLink(checker, record, "video-id"));
        assertNotNull(cache.waitStartTime);
    }

    private StreamRecorder fetchLatestRecord(StreamrecorderIOChecker checker,
                                             StreamerConfig config,
                                             JSONObject... records) throws Exception {
        JSONArray data = new JSONArray();
        data.addAll(Arrays.asList(records));
        JSONObject response = new JSONObject();
        response.put("data", data);

        Method method = StreamrecorderIOChecker.class
                .getDeclaredMethod("fetchLatestRecord", StreamerConfig.class, JSONObject.class);
        method.setAccessible(true);
        return (StreamRecorder) method.invoke(checker, config, response);
    }

    private String resolveDownloadLink(StreamrecorderIOChecker checker,
                                       JSONObject record,
                                       String videoId) throws Exception {
        StreamerConfig config = config(null);
        Method method = StreamrecorderIOChecker.class
                .getDeclaredMethod("resolveDownloadLink", StreamerConfig.class, JSONObject.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(checker, config, record, videoId);
    }

    private StreamrecorderIOChecker checkerWithCache(CacheBizManager cacheBizManager) throws Exception {
        StreamrecorderIOChecker checker = new StreamrecorderIOChecker();
        setField(checker, "cacheBizManager", cacheBizManager);
        setField(checker, "eventPublisher", (ApplicationEventPublisher) event -> { });
        return checker;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private String getStreamUrl(StreamRecorder recorder) throws Exception {
        Field field = RangeVodStreamRecorder.class.getDeclaredField("streamUrl");
        field.setAccessible(true);
        return (String) field.get(recorder);
    }

    private static StreamerConfig config(Date lastRecordTime) {
        return StreamerConfig.builder()
                .name("test")
                .roomUrl("https://streamrecorder.io/target")
                .lastRecordTime(lastRecordTime)
                .build();
    }

    private static JSONObject record(String id,
                                     String status,
                                     String recordedAt,
                                     int duration,
                                     String downloadLink) {
        JSONObject record = new JSONObject();
        record.put("id", id);
        record.put("status", status);
        record.put("streamtitle", "test");
        record.put("recorded_at", recordedAt);
        record.put("duration", duration);
        if (downloadLink != null) {
            JSONArray sources = new JSONArray();
            sources.add(source(1080, downloadLink));
            record.put("sources", sources);
        }
        return record;
    }

    private static JSONObject recordWithSources(JSONObject... sources) {
        JSONObject record = new JSONObject();
        JSONArray sourceArray = new JSONArray();
        sourceArray.addAll(Arrays.asList(sources));
        record.put("sources", sourceArray);
        return record;
    }

    private static JSONObject source(int resolution, String downloadLink) {
        JSONObject source = new JSONObject();
        source.put("resolution", resolution);
        source.put("downloadlink", downloadLink);
        return source;
    }

    private static Set<String> ids(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static Date lastRecordAt(String value) throws Exception {
        return DateUtils.addHours(
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").parse(value), 8);
    }

    private static class TestCacheBizManager extends CacheBizManager {
        private Set<String> runningIds = new HashSet<>();
        private Long waitStartTime;

        @Override
        public Set<String> getStreamrecorderRunningIds(String streamerName) {
            return new HashSet<>(runningIds);
        }

        @Override
        public void saveStreamrecorderRunningIds(String streamerName, Set<String> videoIds) {
            runningIds = new HashSet<>(videoIds);
        }

        @Override
        public void clearStreamrecorderRunningIds(String streamerName) {
            runningIds.clear();
        }

        @Override
        public Long getWaitingFor1080StartTime(String streamerName, String videoId) {
            return waitStartTime;
        }

        @Override
        public void setWaitingFor1080(String streamerName, String videoId, long startTime) {
            waitStartTime = startTime;
        }

        @Override
        public void clearWaitingFor1080(String streamerName, String videoId) {
            waitStartTime = null;
        }
    }
}
