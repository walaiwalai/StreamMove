package com.sh.engine.task;

import com.sh.engine.service.LlmService;
import com.sh.engine.service.OcrService;
import com.sh.engine.service.OssUploadService;
import org.junit.Test;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.Collections;

import static org.junit.Assert.fail;
import static org.mockito.Mockito.*;

public class ComputeTasksInterruptionTest {
    @Test
    public void interruptedDownloadDoesNotStartAnotherOcrRequest() throws Exception {
        ComputeTasks.OcrTask task = new ComputeTasks.OcrTask(Collections.singletonList("frame.jpg"));
        OcrService ocr = mock(OcrService.class);
        OssUploadService oss = mock(OssUploadService.class);
        set(task, "ocr", ocr); set(task, "oss", oss);
        doAnswer(call -> { Thread.currentThread().interrupt(); return null; })
                .when(oss).download(anyString(), any(OutputStream.class));
        try { task.call(); fail("interrupted input was sent to OCR"); }
        catch (InterruptedException expected) { verifyZeroInteractions(ocr); }
        finally { Thread.interrupted(); }
    }

    @Test
    public void interruptedLastImageDownloadDoesNotStartModelRequest() throws Exception {
        ComputeTasks.LlmTask task = new ComputeTasks.LlmTask("review", Collections.singletonList("frame.jpg"));
        LlmService llm = mock(LlmService.class);
        OssUploadService oss = mock(OssUploadService.class);
        set(task, "llm", llm); set(task, "oss", oss);
        when(oss.getImageDataUrl(anyString())).thenAnswer(call -> {
            Thread.currentThread().interrupt(); return "data:image/png;base64,AQID";
        });
        try { task.call(); fail("interrupted input was sent to model"); }
        catch (InterruptedException expected) { verifyZeroInteractions(llm); }
        finally { Thread.interrupted(); }
    }

    @Test
    public void remoteTaskCanRunWithAnIndependentClassLoader() throws Exception {
        Class<?> type = ComputeTasks.OcrTask.class;
        byte[] code;
        try (java.io.InputStream in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            code = org.apache.commons.io.IOUtils.toByteArray(in);
        }
        Class<?> remoteType = new ClassLoader(type.getClassLoader()) {
            Class<?> defineTask() { return defineClass(type.getName(), code, 0, code.length); }
        }.defineTask();
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(bytes)) {
            out.writeObject(new ComputeTasks.OcrTask(Collections.singletonList("frame.jpg")));
        }
        Object task;
        try (java.io.ObjectInputStream in = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray())) {
            @Override protected Class<?> resolveClass(java.io.ObjectStreamClass descriptor) throws java.io.IOException, ClassNotFoundException {
                return descriptor.getName().equals(type.getName()) ? remoteType : super.resolveClass(descriptor);
            }
        }) { task = in.readObject(); }
        OcrService ocr = mock(OcrService.class);
        OssUploadService oss = mock(OssUploadService.class);
        set(task, "ocr", ocr); set(task, "oss", oss);
        ((java.util.concurrent.Callable<?>) task).call();
        verify(ocr).recognize(any(byte[].class), eq("frame.jpg"));
    }

    private void set(Object bean, String name, Object value) throws Exception {
        Field field = bean.getClass().getDeclaredField(name); field.setAccessible(true); field.set(bean, value);
    }
}
