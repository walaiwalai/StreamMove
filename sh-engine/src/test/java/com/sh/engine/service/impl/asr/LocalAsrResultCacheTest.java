package com.sh.engine.service.impl.asr;

import com.sh.engine.model.asr.AsrSegment;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class LocalAsrResultCacheTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void shouldPersistAndReloadSegmentsByAudioContent() throws Exception {
        File recordDirectory = temporaryFolder.newFolder("record");
        File audioFile = temporaryFolder.newFile("segment.wav");
        Files.write(audioFile.toPath(), "deterministic-audio".getBytes(StandardCharsets.UTF_8));
        LocalAsrResultCache cache = new LocalAsrResultCache();
        String key = cache.createKey(audioFile, "fun-asr", 120, 180).get();
        List<AsrSegment> expected = Arrays.asList(
                AsrSegment.builder().startTime(121).endTime(123).text("第一句").build(),
                AsrSegment.builder().startTime(125).endTime(128).text("第二句").build());

        Assert.assertFalse(cache.load(recordDirectory, key).isPresent());
        cache.save(recordDirectory, key, expected);

        Optional<List<AsrSegment>> actual = cache.load(recordDirectory, key);
        Assert.assertTrue(actual.isPresent());
        Assert.assertEquals(expected, actual.get());
    }

    @Test
    public void shouldCacheEmptyResultAndIncludeRequestParametersInKey() throws Exception {
        File recordDirectory = temporaryFolder.newFolder("record");
        File audioFile = temporaryFolder.newFile("segment.wav");
        Files.write(audioFile.toPath(), "same-audio".getBytes(StandardCharsets.UTF_8));
        LocalAsrResultCache cache = new LocalAsrResultCache();
        String firstKey = cache.createKey(audioFile, "fun-asr", 10, 20).get();
        String changedRangeKey = cache.createKey(audioFile, "fun-asr", 11, 20).get();
        String changedModelKey = cache.createKey(audioFile, "other-model", 10, 20).get();

        Assert.assertNotEquals(firstKey, changedRangeKey);
        Assert.assertNotEquals(firstKey, changedModelKey);
        cache.save(recordDirectory, firstKey, Collections.emptyList());

        Optional<List<AsrSegment>> cached = cache.load(recordDirectory, firstKey);
        Assert.assertTrue(cached.isPresent());
        Assert.assertTrue(cached.get().isEmpty());
    }
}
