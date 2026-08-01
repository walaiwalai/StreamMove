package com.sh.engine.processor.checker;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.sh.config.model.config.StreamerConfig;
import org.apache.commons.lang3.time.DateUtils;
import org.junit.Test;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;

import static org.junit.Assert.assertEquals;

public class StreamrecorderIOCheckerTest {

    @Test
    public void selectsOldestPendingRecordWhenSeveralRecordsAreNew() throws Exception {
        JSONArray records = records(
                record("2025-08-20T12:00:00", 1800),
                record("2025-08-20T11:30:00", 1800),
                record("2025-08-20T11:00:00", 1800));

        assertEquals(2, findNextRecordIndex(lastRecordAt("2025-08-20T10:00:00"), records));
    }

    @Test
    public void selectsOldestPendingRecordAfterLastRecordTime() throws Exception {
        JSONArray records = records(
                record("2025-08-20T12:00:00", 1800),
                record("2025-08-20T11:30:00", 1800),
                record("2025-08-20T09:00:00", 1800));

        assertEquals(1, findNextRecordIndex(lastRecordAt("2025-08-20T11:00:00"), records));
    }

    @Test
    public void returnsNoRecordWhenAllRecordsAreOld() throws Exception {
        JSONArray records = records(
                record("2025-08-20T12:00:00", 1800),
                record("2025-08-20T11:30:00", 1800));

        assertEquals(-1, findNextRecordIndex(lastRecordAt("2025-08-20T13:00:00"), records));
    }

    @Test
    public void skipsOverlappingPendingRecordAndContinuesToNewerRecord() throws Exception {
        // data 按时间倒序：最新记录、重叠记录、已处理的更早记录。
        JSONArray records = records(
                record("2025-08-20T12:06:00", 1800),
                record("2025-08-20T11:00:00", 1800),
                record("2025-08-20T10:00:00", 7200));

        assertEquals(0, findNextRecordIndex(lastRecordAt("2025-08-20T10:00:00"), records));
    }

    @Test
    public void keepsPendingRecordWhenItDoesNotOverlapOlderRecord() throws Exception {
        JSONArray records = records(
                record("2025-08-20T12:00:00", 1800),
                record("2025-08-20T11:00:00", 1800),
                record("2025-08-20T10:00:00", 1800));

        assertEquals(1, findNextRecordIndex(lastRecordAt("2025-08-20T10:00:00"), records));
    }

    @Test
    public void skipsAllLaterRecordsOverlappingTheHandledLongestVideo() throws Exception {
        JSONArray records = records(
                record("2026-07-30T21:04:24", 5 * 3600 + 17 * 60 + 35),
                record("2026-07-30T20:58:23", 5 * 3600 + 23 * 60 + 21),
                record("2026-07-30T20:52:22", 5 * 3600 + 29 * 60 + 37));

        assertEquals(2, findNextRecordIndex(lastRecordAt("2026-07-30T20:00:00"), records));
        assertEquals(-1, findNextRecordIndex(lastRecordAt("2026-07-30T20:52:22"), records));
    }

    @Test
    public void selectsLongestVideoWithinAnOverlappingGroup() throws Exception {
        JSONArray records = records(
                record("2025-08-20T11:10:00", 1800),
                record("2025-08-20T11:05:00", 3600),
                record("2025-08-20T11:00:00", 1200));

        assertEquals(1, findNextRecordIndex(lastRecordAt("2025-08-20T10:00:00"), records));
    }

    @Test
    public void returnsNoRecordForEmptyResponse() throws Exception {
        assertEquals(-1, findNextRecordIndex(lastRecordAt("2025-08-20T10:00:00"), new JSONArray()));
    }

    @Test
    public void treatsRecordAtLastRecordTimeAsHandled() throws Exception {
        JSONArray records = records(record("2025-08-20T10:00:00", 1800));

        assertEquals(-1, findNextRecordIndex(lastRecordAt("2025-08-20T10:00:00"), records));
    }

    @Test
    public void treatsStartAtBufferedEndAsOverlapping() throws Exception {
        JSONArray records = records(
                record("2025-08-20T10:35:00", 600),
                record("2025-08-20T10:00:00", 1800));

        assertEquals(-1, findNextRecordIndex(lastRecordAt("2025-08-20T10:00:00"), records));
    }

    @Test
    public void treatsStartAfterBufferedEndAsNewGroup() throws Exception {
        JSONArray records = records(
                record("2025-08-20T10:35:01", 600),
                record("2025-08-20T10:00:00", 1800));

        assertEquals(0, findNextRecordIndex(lastRecordAt("2025-08-20T10:00:00"), records));
    }

    @Test
    public void skipsTransitivelyOverlappingHandledGroup() throws Exception {
        JSONArray records = records(
                record("2025-08-20T11:08:00", 1800),
                record("2025-08-20T10:34:00", 1800),
                record("2025-08-20T10:00:00", 1800));

        assertEquals(-1, findNextRecordIndex(lastRecordAt("2025-08-20T10:00:00"), records));
    }

    @Test
    public void selectsFromOldestPendingGroupBeforeNewerGroup() throws Exception {
        JSONArray records = records(
                record("2025-08-20T13:00:00", 7200),
                record("2025-08-20T11:05:00", 3600),
                record("2025-08-20T11:00:00", 1800));

        assertEquals(1, findNextRecordIndex(lastRecordAt("2025-08-20T10:00:00"), records));
    }

    @Test
    public void treatsAllRecordsAsPendingWhenLastRecordTimeIsNull() throws Exception {
        JSONArray records = records(
                record("2025-08-20T11:05:00", 3600),
                record("2025-08-20T11:00:00", 1800));

        assertEquals(0, findNextRecordIndex(null, records));
    }

    private int findNextRecordIndex(Date lastRecordTime, JSONArray records) throws Exception {
        StreamrecorderIOChecker checker = new StreamrecorderIOChecker();
        StreamerConfig config = StreamerConfig.builder()
                .name("test")
                .lastRecordTime(lastRecordTime)
                .build();

        Method method = StreamrecorderIOChecker.class
                .getDeclaredMethod("findNextRecordIndex", StreamerConfig.class, JSONArray.class);
        method.setAccessible(true);
        return (Integer) method.invoke(checker, config, records);
    }

    private static JSONArray records(String... records) {
        JSONArray result = new JSONArray();
        for (String record : records) {
            result.add(JSON.parseObject(record));
        }
        return result;
    }

    private static String record(String recordedAt, int duration) {
        return "{\"status\":\"finished\",\"streamtitle\":\"test\","
                + "\"recorded_at\":\"" + recordedAt + "\",\"duration\":" + duration + "}";
    }

    private static Date lastRecordAt(String value) throws Exception {
        return DateUtils.addHours(
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").parse(value), 8);
    }
}
