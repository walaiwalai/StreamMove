package com.sh.engine.task;

import cn.hutool.extra.spring.SpringUtil;
import com.alibaba.fastjson.JSONObject;
import com.sh.engine.service.OcrService;
import com.sh.engine.service.impl.LlmServiceImpl;
import com.sh.engine.service.impl.asr.AliyunAsrServiceImpl;
import com.sh.engine.service.impl.asr.WhisperAsrServiceImpl;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.TextContent;
import org.junit.*;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.env.MapPropertySource;
import java.io.File;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class DriverComputeRoutingTest {
    private ApplicationContext previous;
    private StaticApplicationContext context;
    private ComputeTasks tasks;
    @Before public void start() {
        previous = SpringUtil.getApplicationContext(); context = new StaticApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("role", Collections.singletonMap("streammove.mode", "driver")));
        new SpringUtil().setApplicationContext(context); tasks = mock(ComputeTasks.class);
    }
    @After public void stop() { new SpringUtil().setApplicationContext(previous); context.close(); }

    @Test public void existingOcrEntryRoutesWithoutCallingConfiguredHttpEndpoint() throws Exception {
        OcrService service = new OcrService(); set(service, "computeTasks", tasks);
        byte[] image = {1,2}; when(tasks.recognize(image, "frame.jpg")).thenReturn(Collections.emptyList());
        assertTrue(service.recognize(image, "frame.jpg").isEmpty()); verify(tasks).recognize(image, "frame.jpg");
        assertTrue(service.recognize(new byte[0], "empty.jpg").isEmpty()); verifyNoMoreInteractions(tasks);
    }

    @Test public void bothAsrProvidersRouteBeforeCheckingCloudOrWhisperSettings() throws Exception {
        File video = new File("source.mp4"), audio = new File("source.wav");
        AliyunAsrServiceImpl aliyun = new AliyunAsrServiceImpl(); set(aliyun,"computeTasks",tasks);
        WhisperAsrServiceImpl whisper = new WhisperAsrServiceImpl(); set(whisper,"computeTasks",tasks);
        aliyun.transcribeSegment(video,40,80); whisper.transcribeSegment(video,40,80);
        aliyun.transcribeAudio(audio,40); whisper.transcribeAudio(audio,40);
        verify(tasks,times(2)).transcribeSegment(video,40,80); verify(tasks,times(2)).transcribeAudio(audio,40);
    }

    @Test public void textMultimodalAndAsyncModelCallsRouteWithoutDriverModelCredentials() throws Exception {
        LlmServiceImpl service = new LlmServiceImpl(); set(service,"computeTasks",tasks); service.init();
        assertTrue(service.isEnabled());
        JSONObject answer = new JSONObject(); answer.put("ok",true); when(tasks.chatText("text")).thenReturn(answer);
        assertTrue(service.chat("text",JSONObject.class).getBooleanValue("ok"));
        assertTrue(service.chatAsync("text",JSONObject.class).get(2,TimeUnit.SECONDS).getBooleanValue("ok"));
        List<Content> content=Collections.singletonList(TextContent.from("review")); when(tasks.chat(content)).thenReturn(answer);
        assertSame(answer,service.chat(content)); verify(tasks,times(2)).chatText("text"); verify(tasks).chat(content);
    }
    @Test public void lolDetailDetectionUsesYoloQueueInsteadOfOldOcrHost() throws Exception {
        com.sh.engine.processor.plugin.highlight.HighlightOcrClient client = new com.sh.engine.processor.plugin.highlight.HighlightOcrClient();
        set(client,"computeTasks",tasks);
        byte[] image={1,2};
        com.sh.engine.model.highlight.lol.LoLPicData.HeroKillOrAssistDetail result =
                new com.sh.engine.model.highlight.lol.LoLPicData.HeroKillOrAssistDetail(Collections.emptyList(),Collections.emptyList());
        when(tasks.withImage(eq(TaskType.YOLO),eq(image),eq("frame.jpg"),any())).thenReturn(result);
        assertSame(result,client.recognizeKillDetail(image,"frame.jpg"));
        verify(tasks).withImage(eq(TaskType.YOLO),eq(image),eq("frame.jpg"),any());
    }
    private void set(Object bean,String name,Object value) throws Exception {
        Field field=bean.getClass().getDeclaredField(name);field.setAccessible(true);field.set(bean,value);
    }
}
