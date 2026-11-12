package com.sh.engine.task;

import com.alibaba.fastjson.JSONObject;
import com.sh.engine.service.LlmService;
import com.sh.engine.service.OssUploadService;
import dev.langchain4j.data.message.*;
import org.junit.Test;
import org.redisson.api.RExecutorFuture;
import org.redisson.executor.RedissonExecutorFuture;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ComputeTasksRoutingTest {
    @Test public void imagePartsTravelAsOssKeysAndPreserveTextOrderAndDetail() throws Exception {
        TaskClient client=mock(TaskClient.class); OssUploadService oss=mock(OssUploadService.class);
        LlmService llm=mock(LlmService.class); ComputeTasks tasks=new ComputeTasks(client); set(tasks,"oss",oss);
        List<String> uploads=new ArrayList<>();
        when(oss.uploadAndGetUrl(any(File.class),anyString())).thenAnswer(call -> {
            assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(((File)call.getArgument(0)).toPath()));
            String key=call.getArgument(1); assertTrue(key.endsWith(".png")); uploads.add(key); return "ignored-signed-url";
        });
        when(oss.getImageDataUrl(anyString())).thenReturn("data:image/png;base64,AQID");
        JSONObject answer=new JSONObject();answer.put("finalResponse","ok");
        when(llm.chat(org.mockito.ArgumentMatchers.<List<Content>>any())).thenAnswer(call -> {
            List<Content> blocks=call.getArgument(0); assertEquals(5,blocks.size());
            assertEquals("prompt",((TextContent)blocks.get(0)).text());
            assertEquals("frame at 40s",((TextContent)blocks.get(1)).text());
            assertEquals("data:image/png;base64,AQID",((ImageContent)blocks.get(2)).image().url().toString());
            assertEquals(ImageContent.DetailLevel.HIGH,((ImageContent)blocks.get(2)).detailLevel());
            assertEquals("frame at 44s",((TextContent)blocks.get(3)).text());
            assertEquals("https://example.com/frame.jpg",((ImageContent)blocks.get(4)).image().url().toString());
            return answer;
        });
        when(client.submit(eq(TaskType.LLM),any())).thenAnswer(call -> {
            // 模拟跨 JVM 序列化，确保没有把 SDK Content 或图片二进制放进任务对象。
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(ObjectOutputStream out=new ObjectOutputStream(bytes)) {out.writeObject(call.getArgument(1));}
            ComputeTasks.LlmTask task;
            try(ObjectInputStream in=new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {task=(ComputeTasks.LlmTask)in.readObject();}
            set(task,"llm",llm);set(task,"oss",oss);
            return new RedissonExecutorFuture<>(CompletableFuture.completedFuture(task.call()),"id");
        });
        when(client.result(any())).thenAnswer(call -> ((RExecutorFuture<?>)call.getArgument(0)).get());
        assertSame(answer,tasks.chat(Arrays.asList(TextContent.from("prompt"),TextContent.from("frame at 40s"),
                ImageContent.from("AQID","image/png",ImageContent.DetailLevel.HIGH),TextContent.from("frame at 44s"),
                ImageContent.from("https://example.com/frame.jpg"))));
        assertEquals(1,uploads.size());verify(oss,timeout(2000)).delete(uploads.get(0));
    }

    @Test public void waitTimeoutKeepsInputsUntilOriginalTaskFinishes() throws Exception {
        TaskClient client=mock(TaskClient.class);OssUploadService oss=mock(OssUploadService.class);
        ComputeTasks tasks=new ComputeTasks(client);set(tasks,"oss",oss);
        CompletableFuture<Object> running=new CompletableFuture<>();
        when(client.submit(eq(TaskType.OCR),any())).thenReturn(new RedissonExecutorFuture<>(running,"id"));
        when(client.result(any())).thenThrow(new TimeoutException("wait expired"));
        try {tasks.recognize(new byte[]{1},"frame.jpg");fail("timeout expected");}
        catch(IllegalStateException expected) {assertTrue(expected.getCause() instanceof TimeoutException);}
        verify(oss,never()).delete(anyString());assertFalse(running.isDone());
        running.complete(Collections.singletonList(Collections.emptyList()));
        verify(oss,timeout(2000)).delete(anyString());
    }

    @Test public void partialInputPreparationFailureCleansOnlyNewlyUploadedObjects() throws Exception {
        TaskClient client=mock(TaskClient.class);OssUploadService oss=mock(OssUploadService.class);
        ComputeTasks tasks=new ComputeTasks(client);set(tasks,"oss",oss);
        try {
            tasks.chat(Arrays.asList(ImageContent.from("AQID","image/png"),ImageContent.from("not-base64!","image/png")));
            fail("invalid input accepted");
        } catch(IllegalArgumentException expected) { }
        verify(oss).uploadAndGetUrl(any(File.class),anyString());verify(oss).delete(anyString());verifyZeroInteractions(client);
    }
    private void set(Object bean,String name,Object value) throws Exception {
        Field field=bean.getClass().getDeclaredField(name);field.setAccessible(true);field.set(bean,value);
    }
}
