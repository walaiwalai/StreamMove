package com.sh.engine.task;

import com.alibaba.fastjson.JSONObject;
import com.sh.engine.model.highlight.core.OcrTextDetection;
import com.sh.engine.service.*;
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
    @Test public void smallImagePartsStayInlineAndPreserveTextOrderAndDetail() throws Exception {
        TaskClient client=mock(TaskClient.class); OssUploadService oss=mock(OssUploadService.class);
        LlmService llm=mock(LlmService.class); ComputeTasks tasks=new ComputeTasks(client); set(tasks,"oss",oss);
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
            ComputeTasks.LlmTask task=roundTrip(call.getArgument(1));set(task,"llm",llm);set(task,"oss",oss);
            return new RedissonExecutorFuture<>(CompletableFuture.completedFuture(task.call()),"id");
        });
        waitOnOriginal(client);
        assertSame(answer,tasks.chat(Arrays.asList(TextContent.from("prompt"),TextContent.from("frame at 40s"),
                ImageContent.from("AQID","image/png",ImageContent.DetailLevel.HIGH),TextContent.from("frame at 44s"),
                ImageContent.from("https://example.com/frame.jpg"))));
        verifyZeroInteractions(oss);
    }

    @Test public void ocrUsesAtMostEightImagesAndExactly32KiBStaysInline() throws Exception {
        TaskClient client=mock(TaskClient.class); OssUploadService oss=mock(OssUploadService.class);
        OcrService ocr=mock(OcrService.class);ComputeTasks tasks=new ComputeTasks(client);set(tasks,"oss",oss);
        when(ocr.recognize(any(byte[].class),anyString())).thenAnswer(call -> Collections.singletonList(
                new OcrTextDetection(String.valueOf(((byte[])call.getArgument(0))[0]),1f,Collections.emptyList())));
        when(client.submit(eq(TaskType.OCR),any())).thenAnswer(call -> {
            ComputeTasks.OcrTask task=roundTrip(call.getArgument(1));set(task,"ocr",ocr);set(task,"oss",oss);
            return new RedissonExecutorFuture<>(CompletableFuture.completedFuture(task.call()),"id");
        });
        waitOnOriginal(client);
        List<byte[]> images=new ArrayList<>();List<String> names=new ArrayList<>();
        for(int i=0;i<9;i++){byte[] image=new byte[4096];image[0]=(byte)i;images.add(image);names.add("same.jpg");}
        List<List<OcrTextDetection>> result=tasks.recognizeBatch(images,names);
        assertEquals(9,result.size());for(int i=0;i<9;i++)assertEquals(String.valueOf(i),result.get(i).get(0).getText());
        verify(client,times(2)).submit(eq(TaskType.OCR),any());verifyZeroInteractions(oss);
    }

    @Test public void largerBatchUsesOneZipAndKeepsDuplicateFilenamesAndResultOrder() throws Exception {
        TaskClient client=mock(TaskClient.class);OssUploadService oss=mock(OssUploadService.class);
        OcrService ocr=mock(OcrService.class);ComputeTasks tasks=new ComputeTasks(client);set(tasks,"oss",oss);
        byte[][] archive={null};File[] temporary={null};
        when(oss.uploadAndGetUrl(any(File.class),anyString())).thenAnswer(call -> {
            temporary[0]=call.getArgument(0);archive[0]=Files.readAllBytes(temporary[0].toPath());
            assertTrue(((String)call.getArgument(1)).endsWith(".zip"));return "unused-url";
        });
        doAnswer(call -> {((OutputStream)call.getArgument(1)).write(archive[0]);return null;}).when(oss).download(anyString(),any(OutputStream.class));
        when(ocr.recognize(any(byte[].class),eq("same.jpg"))).thenAnswer(call -> Collections.singletonList(
                new OcrTextDetection(String.valueOf(((byte[])call.getArgument(0))[0]),1f,Collections.emptyList())));
        when(client.submit(eq(TaskType.OCR),any())).thenAnswer(call -> {
            ComputeTasks.OcrTask task=roundTrip(call.getArgument(1));set(task,"ocr",ocr);set(task,"oss",oss);
            return new RedissonExecutorFuture<>(CompletableFuture.completedFuture(task.call()),"id");
        });
        waitOnOriginal(client);
        byte[] large=new byte[32769];large[0]=9;
        List<List<OcrTextDetection>> result=tasks.recognizeBatch(Arrays.asList(large,new byte[]{10}),Arrays.asList("same.jpg","same.jpg"));
        assertEquals("9",result.get(0).get(0).getText());assertEquals("10",result.get(1).get(0).getText());
        verify(oss).uploadAndGetUrl(any(File.class),anyString());verify(oss).download(anyString(),any(OutputStream.class));
        verify(oss,timeout(2000)).delete(anyString());assertFalse(temporary[0].exists());
    }

    @Test public void multimodalInlineBudgetIsSharedAcrossAllImagesInOneRequest() throws Exception {
        TaskClient client=mock(TaskClient.class);OssUploadService oss=mock(OssUploadService.class);
        LlmService llm=mock(LlmService.class);ComputeTasks tasks=new ComputeTasks(client);set(tasks,"oss",oss);
        byte[][] archive={null};
        when(oss.uploadAndGetUrl(any(File.class),anyString())).thenAnswer(call -> {archive[0]=Files.readAllBytes(((File)call.getArgument(0)).toPath());return "unused";});
        doAnswer(call -> {((OutputStream)call.getArgument(1)).write(archive[0]);return null;}).when(oss).download(anyString(),any(OutputStream.class));
        when(llm.chat(org.mockito.ArgumentMatchers.<List<Content>>any())).thenAnswer(call -> {
            List<Content> parts=call.getArgument(0);assertEquals(4,parts.size());
            assertEquals("first",((TextContent)parts.get(0)).text());assertEquals("second",((TextContent)parts.get(2)).text());
            assertTrue(((ImageContent)parts.get(1)).image().url().toString().startsWith("data:image/png;base64,"));
            assertTrue(((ImageContent)parts.get(3)).image().url().toString().startsWith("data:image/jpeg;base64,"));return new JSONObject();
        });
        when(client.submit(eq(TaskType.LLM),any())).thenAnswer(call -> {
            ComputeTasks.LlmTask task=roundTrip(call.getArgument(1));set(task,"llm",llm);set(task,"oss",oss);
            return new RedissonExecutorFuture<>(CompletableFuture.completedFuture(task.call()),"id");
        });waitOnOriginal(client);
        String image=Base64.getEncoder().encodeToString(new byte[20000]);
        tasks.chat(Arrays.asList(TextContent.from("first"),ImageContent.from(image,"image/png"),TextContent.from("second"),ImageContent.from(image,"image/jpeg")));
        verify(oss).uploadAndGetUrl(any(File.class),anyString());verify(oss).download(anyString(),any(OutputStream.class));
        verify(oss,timeout(2000)).delete(anyString());
    }

    @Test public void waitTimeoutKeepsArchiveUntilOriginalTaskFinishes() throws Exception {
        TaskClient client=mock(TaskClient.class);OssUploadService oss=mock(OssUploadService.class);
        ComputeTasks tasks=new ComputeTasks(client);set(tasks,"oss",oss);
        CompletableFuture<Object> running=new CompletableFuture<>();
        when(client.submit(eq(TaskType.OCR),any())).thenReturn(new RedissonExecutorFuture<>(running,"id"));
        when(client.result(any())).thenThrow(new TimeoutException("wait expired"));
        try {tasks.recognize(new byte[32769],"frame.jpg");fail("timeout expected");}
        catch(IllegalStateException expected) {assertTrue(expected.getCause() instanceof TimeoutException);}
        verify(oss,never()).delete(anyString());assertFalse(running.isDone());
        running.complete(Collections.singletonList(Collections.emptyList()));verify(oss,timeout(2000)).delete(anyString());
    }

    @Test public void invalidImageDoesNotUploadOrSubmitPartOfBatch() throws Exception {
        TaskClient client=mock(TaskClient.class);OssUploadService oss=mock(OssUploadService.class);
        ComputeTasks tasks=new ComputeTasks(client);set(tasks,"oss",oss);
        try {tasks.chat(Arrays.asList(ImageContent.from("AQID","image/png"),ImageContent.from("not-base64!","image/png")));fail("invalid input accepted");}
        catch(IllegalArgumentException expected) { }
        verifyZeroInteractions(client,oss);
    }

    @Test public void uploadFailureCleansOwnedKeyWithoutSubmittingTask() throws Exception {
        TaskClient client=mock(TaskClient.class);OssUploadService oss=mock(OssUploadService.class);
        ComputeTasks tasks=new ComputeTasks(client);set(tasks,"oss",oss);
        when(oss.uploadAndGetUrl(any(File.class),anyString())).thenThrow(new IllegalStateException("upload failed"));
        try {tasks.recognize(new byte[32769],"frame.jpg");fail("upload failure ignored");}catch(IllegalStateException expected){}
        verify(oss).delete(anyString());verifyZeroInteractions(client);
    }

    private void waitOnOriginal(TaskClient client) throws Exception {when(client.result(any())).thenAnswer(call -> ((RExecutorFuture<?>)call.getArgument(0)).get());}
    @SuppressWarnings("unchecked") private <T> T roundTrip(Object value) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ObjectOutputStream out=new ObjectOutputStream(bytes)){out.writeObject(value);}
        try(ObjectInputStream in=new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))){return (T)in.readObject();}
    }
    private void set(Object bean,String name,Object value) throws Exception {Field field=bean.getClass().getDeclaredField(name);field.setAccessible(true);field.set(bean,value);}
}
