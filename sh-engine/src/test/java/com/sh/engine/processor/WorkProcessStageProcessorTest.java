package com.sh.engine.processor;

import com.sh.config.manager.ConfigFetcher;
import com.sh.config.manager.StatusManager;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.model.storage.FileStatusModel;
import com.sh.engine.constant.ProcessPluginEnum;
import com.sh.engine.constant.RecordTaskStateEnum;
import com.sh.engine.model.RecordContext;
import com.sh.engine.model.Streamer;
import com.sh.engine.model.StreamerInfoHolder;
import com.sh.engine.processor.plugin.VideoProcessPlugin;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.util.Collections;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class WorkProcessStageProcessorTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void failedHighlightDoesNotAdvanceStageOrMarkPluginFinished() throws Exception {
        assertFailurePolicy(ProcessPluginEnum.VALORANT_HL_VOD_CUT, null, true);
    }

    @Test public void highlightFailureContractAlsoAppliesToOtherGames() throws Exception {
        assertFailurePolicy(ProcessPluginEnum.LOL_HL_VOD_CUT, null, true);
    }

    @Test public void ordinaryPluginReturningUnfinishedUsesTheSameRetryContract() throws Exception {
        assertFailurePolicy(ProcessPluginEnum.DAMAKU_MERGE, null, true);
    }

    @Test public void optionalPluginExceptionsKeepTheExistingContinuePolicy() throws Exception {
        assertFailurePolicy(ProcessPluginEnum.DAMAKU_MERGE, new IllegalStateException("merge failed"), false);
        assertFailurePolicy(ProcessPluginEnum.DAMAKU_MERGE,
                new StreamerRecordException(ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR), false);
    }

    @Test public void systemPluginExceptionStillBlocksTheNextStage() throws Exception {
        assertFailurePolicy(ProcessPluginEnum.TS_2_MP4_TRANSFER, new IllegalStateException("transfer failed"), true);
    }

    private void assertFailurePolicy(ProcessPluginEnum pluginType, RuntimeException failure, boolean retryable) throws Exception {
        File directory = temporary.newFolder();
        new FileStatusModel().writeSelfToFile(directory.getAbsolutePath());
        Streamer streamer = new Streamer(); streamer.setName("test");
        streamer.setRecordPaths(Collections.singletonList(directory.getAbsolutePath()));
        Object previous = getField(ConfigFetcher.class, "name2StreamerMap");
        String name = pluginType.getType();
        StreamerConfig config = StreamerConfig.builder().name("test").videoPlugins(Collections.singletonList(name)).build();
        setField(ConfigFetcher.class, "name2StreamerMap", Collections.singletonMap("test", config));
        VideoProcessPlugin plugin = mock(VideoProcessPlugin.class);
        when(plugin.getPluginName()).thenReturn(name); when(plugin.getMaxProcessParallel()).thenReturn(1);
        if (failure == null) { when(plugin.process(directory.getAbsolutePath())).thenReturn(false); }
        else { when(plugin.process(directory.getAbsolutePath())).thenThrow(failure); }
        WorkProcessStageProcessor processor = new WorkProcessStageProcessor();
        StatusManager status = new StatusManager();
        setField(processor, "videoProcessPlugins", Collections.singletonList(plugin));
        setField(processor, "statusManager", status);
        java.lang.reflect.Method init = WorkProcessStageProcessor.class.getDeclaredMethod("init");
        init.setAccessible(true); init.invoke(processor);
        RecordContext context = RecordContext.builder().state(RecordTaskStateEnum.STREAM_RECORD_FINISH).build();
        StreamerInfoHolder.addStreamer(streamer);
        try {
            if (retryable) {
                try { processor.process(context); fail("failure was swallowed"); }
                catch (RuntimeException expected) { assertEquals(RecordTaskStateEnum.STREAM_RECORD_FINISH, context.getState()); }
            } else {
                processor.process(context);
                assertEquals(RecordTaskStateEnum.VIDEO_PROCESS_FINISH, context.getState());
            }
            assertFalse(FileStatusModel.loadFromFile(directory.getAbsolutePath()).isFinishedPlugin(name));
            assertFalse(status.isPathOccupied(directory.getAbsolutePath(), "test"));
            doReturn(true).when(plugin).process(directory.getAbsolutePath());
            context.setState(RecordTaskStateEnum.STREAM_RECORD_FINISH);
            processor.process(context);
            assertEquals(RecordTaskStateEnum.VIDEO_PROCESS_FINISH, context.getState());
            assertTrue(FileStatusModel.loadFromFile(directory.getAbsolutePath()).isFinishedPlugin(name));
        } finally {
            StreamerInfoHolder.clear();
            setField(ConfigFetcher.class, "name2StreamerMap", previous);
        }
    }

    @Test public void existingOccupationCannotBeOverwrittenBySecondEntry() {
        StatusManager status = new StatusManager();
        String path = "/test/" + System.nanoTime();
        try {
            assertTrue(status.tryStartPostProcess(path, "first"));
            assertFalse(status.tryStartPostProcess(path, "second"));
            assertEquals("first", status.getCurPostProcessType(path));
        } finally { status.finishPostProcess(path); }
    }
    private static Object getField(Object target, String name) throws Exception {
        java.lang.reflect.Field field = (target instanceof Class ? (Class<?>) target : target.getClass()).getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target instanceof Class ? null : target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field field = (target instanceof Class ? (Class<?>) target : target.getClass()).getDeclaredField(name);
        field.setAccessible(true);
        field.set(target instanceof Class ? null : target, value);
    }

}
