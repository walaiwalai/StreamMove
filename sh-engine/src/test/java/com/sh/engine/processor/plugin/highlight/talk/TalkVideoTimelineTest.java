package com.sh.engine.processor.plugin.highlight.talk;

import com.sh.engine.model.asr.AsrSegment;
import com.sh.engine.model.highlight.VideoInterval;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TalkVideoTimelineTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void shouldOrderNumberedFilesBeforeFallbackNames() throws Exception {
        List<File> files = new ArrayList<>(Arrays.asList(
                new File("talk.mp4"), new File("10-last.mp4"), new File("2-middle.mp4")));
        Method comparator = TalkVideoTimeline.class.getDeclaredMethod(
                "compareVideoFile", File.class, File.class);
        comparator.setAccessible(true);
        files.sort((left, right) -> invokeComparator(comparator, left, right));

        Assert.assertEquals("2-middle.mp4", files.get(0).getName());
        Assert.assertEquals("10-last.mp4", files.get(1).getName());
        Assert.assertEquals("talk.mp4", files.get(2).getName());
    }

    @Test
    public void shouldSplitGlobalRangeAcrossSourceBoundary() throws Exception {
        File first = new File("1-first.mp4");
        File second = new File("2-second.mp4");
        TalkVideoTimeline timeline = timeline(
                Arrays.asList(first, second), Arrays.asList(100.0, 80.0));

        List<VideoInterval> intervals = timeline.toVideoIntervals(90, 115);

        Assert.assertEquals(2, intervals.size());
        Assert.assertEquals(first, intervals.get(0).getFromVideo());
        Assert.assertEquals(90.0, intervals.get(0).getSecondFromVideoStart(), 0.001);
        Assert.assertEquals(100.0, intervals.get(0).getSecondToVideoEnd(), 0.001);
        Assert.assertEquals(second, intervals.get(1).getFromVideo());
        Assert.assertEquals(0.0, intervals.get(1).getSecondFromVideoStart(), 0.001);
        Assert.assertEquals(15.0, intervals.get(1).getSecondToVideoEnd(), 0.001);
    }

    @Test
    public void shouldPersistAndReuseSourceTranscriptCache() throws Exception {
        File inputDirectory = temporaryFolder.newFolder("talk-input");
        File sourceFile = new File(inputDirectory, "1-source.mp4");
        Files.write(sourceFile.toPath(), "video".getBytes(StandardCharsets.UTF_8));
        TalkVideoTimeline timeline = timeline(
                Arrays.asList(sourceFile), Arrays.asList(120.0));
        TalkVideoTimeline.SourceVideo sourceVideo = timeline.getSourceVideos().get(0);
        TalkTranscriptStore store = new TalkTranscriptStore(inputDirectory);
        List<AsrSegment> segments = Arrays.asList(AsrSegment.builder()
                .startTime(3)
                .endTime(8)
                .text("测试转写")
                .build());

        store.saveSourceTranscript(sourceVideo, 1, segments);
        TalkTranscriptStore.SourceTranscript loaded =
                store.loadSourceTranscript(sourceVideo, 1).orElse(null);

        Assert.assertNotNull(loaded);
        Assert.assertEquals(1, loaded.getSegments().size());
        Assert.assertEquals("测试转写", loaded.getSegments().get(0).getText());
    }

    @SuppressWarnings("unchecked")
    private TalkVideoTimeline timeline(List<File> files, List<Double> durationValues) throws Exception {
        Map<File, Double> durations = new HashMap<>();
        for (int index = 0; index < files.size(); index++) {
            durations.put(files.get(index), durationValues.get(index));
        }
        Constructor<TalkVideoTimeline> constructor = TalkVideoTimeline.class
                .getDeclaredConstructor(List.class, Map.class);
        constructor.setAccessible(true);
        return constructor.newInstance(files, durations);
    }

    private int invokeComparator(Method comparator, File left, File right) {
        try {
            return (Integer) comparator.invoke(null, left, right);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
