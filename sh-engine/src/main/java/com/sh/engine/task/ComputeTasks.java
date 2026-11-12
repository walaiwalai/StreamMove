package com.sh.engine.task;

import com.alibaba.fastjson.JSONObject;
import com.sh.engine.model.asr.AsrSegment;
import com.sh.engine.model.highlight.core.OcrTextDetection;
import com.sh.config.utils.ExecutorPoolUtil;
import com.sh.engine.model.ffmpeg.AudioExtractCmd;
import com.sh.engine.service.AsrService;
import com.sh.engine.service.LlmService;
import com.sh.engine.service.OcrService;
import com.sh.engine.service.OssUploadService;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import org.redisson.api.RExecutorFuture;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import javax.annotation.Resource;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Collections;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.concurrent.Callable;

/** driver 的标准能力入口；序列化对象只包含输入，不包含 Spring 服务和媒体文件。 */
@Service
@Slf4j
public class ComputeTasks {
    @Resource private OssUploadService oss;
    private final TaskClient client;

    public ComputeTasks(TaskClient client) {
        this.client = client;
    }

    /** 结果按输入图片顺序分组，不合并不同图片的文字框。 */
    public RExecutorFuture<List<List<OcrTextDetection>>> ocr(List<String> imageKeys) {
        return client.submit(TaskType.OCR, new OcrTask(imageKeys));
    }

    public RExecutorFuture<List<AsrSegment>> asr(String audioKey, int offsetSeconds) {
        return client.submit(TaskType.ASR, new AsrTask(audioKey, offsetSeconds));
    }

    public RExecutorFuture<JSONObject> llm(String prompt, List<String> imageKeys) {
        return client.submit(TaskType.LLM, new LlmTask(prompt, imageKeys));
    }

    /** 现有服务的 driver 路由入口：素材进入 OSS，Redis 只传对象 key。 */
    public List<OcrTextDetection> recognize(byte[] image, String fileName) {
        return withImage(TaskType.OCR, image, fileName,
                key -> new OcrTask(Collections.singletonList(key))).get(0);
    }

    public <R> R withImage(TaskType type, byte[] image, String fileName, Function<String, Callable<R>> task) {
        return execute(type, keys -> task.apply(uploadBytes(image, suffix(fileName), keys)));
    }

    public List<AsrSegment> transcribeAudio(File audio, int offsetSeconds) {
        if (audio == null || !audio.isFile() || offsetSeconds < 0) {
            throw new IllegalArgumentException("Invalid ASR audio or offset");
        }
        return execute(TaskType.ASR, keys -> new AsrTask(upload(audio, keys), offsetSeconds));
    }

    public List<AsrSegment> transcribeSegment(File video, int start, int end) {
        if (video == null || !video.isFile() || start < 0 || end <= start) {
            throw new IllegalArgumentException("Invalid ASR video interval");
        }
        Path audio = null;
        try {
            audio = Files.createTempFile("compute-input-", ".wav");
            AudioExtractCmd command = new AudioExtractCmd(video, audio.toFile(), start, end - start);
            command.execute(120);
            if (!command.isSuccess()) { throw new IllegalStateException("Audio extraction failed"); }
            return transcribeAudio(audio.toFile(), start);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot prepare ASR input", error);
        } finally {
            if (audio != null) { audio.toFile().delete(); }
        }
    }

    public JSONObject chatText(String prompt) {
        return execute(TaskType.LLM, keys -> new LlmTextTask(prompt));
    }

    public JSONObject chat(List<Content> content) {
        if (content == null || content.isEmpty()) { throw new IllegalArgumentException("Missing model input"); }
        return execute(TaskType.LLM, keys -> {
            List<JSONObject> blocks = new ArrayList<>();
            for (Content part : content) {
                JSONObject block = new JSONObject();
                if (part instanceof TextContent) {
                    block.put("text", ((TextContent) part).text());
                } else if (part instanceof ImageContent) {
                    ImageContent image = (ImageContent) part;
                    String data = image.image().base64Data();
                    String mime = image.image().mimeType();
                    String url = image.image().url() == null ? null : image.image().url().toString();
                    if (url != null && url.startsWith("data:")) {
                        int comma = url.indexOf(',');
                        if (comma < 0 || !url.substring(0, comma).endsWith(";base64")) {
                            throw new IllegalArgumentException("Invalid image data URL");
                        }
                        mime = url.substring(5, url.indexOf(';')); data = url.substring(comma + 1);
                    }
                    if (data != null) {
                        String extension = "image/png".equals(mime) ? ".png" : "image/webp".equals(mime) ? ".webp"
                                : "image/gif".equals(mime) ? ".gif" : ".jpg";
                        block.put("imageKey", uploadBytes(Base64.getDecoder().decode(data), extension, keys));
                    } else {
                        block.put("url", Objects.requireNonNull(url, "image URL"));
                    }
                    block.put("detail", image.detailLevel().name());
                } else { throw new IllegalArgumentException("Only text and image model inputs are supported"); }
                blocks.add(block);
            }
            return new LlmTask(blocks);
        });
    }

    private String upload(File file, List<String> keys) {
        String key = "compute-input/" + UUID.randomUUID() + suffix(file.getName());
        keys.add(key);
        oss.uploadAndGetUrl(file, key);
        return key;
    }

    private String uploadBytes(byte[] data, String extension, List<String> keys) {
        Path file = null;
        try {
            file = Files.createTempFile("compute-input-", extension);
            Files.write(file, data);
            return upload(file.toFile(), keys);
        } catch (IOException error) { throw new IllegalStateException("Cannot prepare image input", error); }
        finally { if (file != null) { file.toFile().delete(); } }
    }

    private static String suffix(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : ".jpg";
    }

    private <R> R execute(TaskType type, Function<List<String>, Callable<R>> prepare) {
        List<String> keys = new ArrayList<>();
        RExecutorFuture<R> task;
        try { task = client.submit(type, prepare.apply(keys)); }
        catch (RuntimeException error) { deleteInputs(keys); throw error; }
        // 等待超时或调用线程中断时，执行可能仍在继续；只在原任务终结后删除输入。
        task.whenCompleteAsync((value, error) -> deleteInputs(keys), ExecutorPoolUtil.getFlushPool());
        log.info("Compute task submitted, type: {}, taskId: {}", type, task.getTaskId());
        try { return client.result(task); }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Compute task wait interrupted", error);
        } catch (ExecutionException | TimeoutException error) {
            throw new IllegalStateException("Compute task failed: " + task.getTaskId(), error);
        }
    }

    private void deleteInputs(List<String> keys) {
        for (String key : keys) {
            try { oss.delete(key); }
            catch (RuntimeException error) { log.warn("Compute input cleanup failed, key: {}, errorType: {}", key, error.getClass().getName()); }
        }
    }

    private static List<String> imageKeys(List<String> keys) {
        Objects.requireNonNull(keys, "imageKeys");
        for (String key : keys) {
            if (key == null || key.trim().isEmpty()) {
                throw new IllegalArgumentException("Image object key is blank");
            }
        }
        return new ArrayList<>(keys);
    }

    /** Redisson 可为远端字节码创建独立类加载器，任务调用的共享方法必须公开。 */
    public static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Compute task interrupted");
        }
    }

    public static final class OcrTask implements Callable<List<List<OcrTextDetection>>>, Serializable {
        private static final long serialVersionUID = 1L;
        private final List<String> imageKeys;
        @Autowired private transient OcrService ocr;
        @Autowired private transient OssUploadService oss;

        public OcrTask(List<String> keys) {
            imageKeys = imageKeys(keys);
            if (imageKeys.isEmpty()) {
                throw new IllegalArgumentException("OCR requires at least one image");
            }
        }

        @Override
        public List<List<OcrTextDetection>> call() throws Exception {
            List<List<OcrTextDetection>> result = new ArrayList<>();
            for (String key : imageKeys) {
                checkInterrupted();
                ByteArrayOutputStream image = new ByteArrayOutputStream();
                oss.download(key, image);
                checkInterrupted();
                result.add(ocr.recognize(image.toByteArray(), key.substring(key.lastIndexOf('/') + 1)));
            }
            return result;
        }
    }

    public static final class AsrTask implements Callable<List<AsrSegment>>, Serializable {
        private static final long serialVersionUID = 1L;
        private final String audioKey;
        private final int offsetSeconds;
        @Autowired private transient AsrService asr;
        @Autowired private transient OssUploadService oss;

        public AsrTask(String audioKey, int offsetSeconds) {
            if (audioKey == null || audioKey.trim().isEmpty() || offsetSeconds < 0) {
                throw new IllegalArgumentException("Invalid ASR input");
            }
            this.audioKey = audioKey;
            this.offsetSeconds = offsetSeconds;
        }

        @Override
        public List<AsrSegment> call() throws Exception {
            String name = audioKey.substring(audioKey.lastIndexOf('/') + 1);
            int dot = name.lastIndexOf('.');
            String suffix = dot >= 0 ? name.substring(dot) : ".wav";
            Path audio = Files.createTempFile("compute-asr-", suffix);
            try {
                try (OutputStream out = Files.newOutputStream(audio)) {
                    oss.download(audioKey, out);
                }
                checkInterrupted();
                return asr.transcribeAudio(audio.toFile(), offsetSeconds);
            } finally {
                Files.deleteIfExists(audio);
            }
        }
    }

    public static final class LlmTask implements Callable<JSONObject>, Serializable {
        private static final long serialVersionUID = 1L;
        private final List<JSONObject> blocks;
        @Autowired private transient LlmService llm;
        @Autowired private transient OssUploadService oss;

        public LlmTask(String prompt, List<String> keys) {
            if (prompt == null || prompt.trim().isEmpty()) { throw new IllegalArgumentException("LLM prompt is blank"); }
            blocks = new ArrayList<>();
            JSONObject text = new JSONObject(); text.put("text", prompt); blocks.add(text);
            for (String key : imageKeys(keys)) {
                JSONObject image = new JSONObject(); image.put("imageKey", key); blocks.add(image);
            }
        }

        private LlmTask(List<JSONObject> blocks) { this.blocks = new ArrayList<>(blocks); }

        @Override
        public JSONObject call() throws Exception {
            List<Content> content = new ArrayList<>();
            for (JSONObject block : blocks) {
                checkInterrupted();
                if (block.containsKey("text")) { content.add(TextContent.from(block.getString("text"))); }
                else {
                    String url = block.containsKey("imageKey") ? oss.getImageDataUrl(block.getString("imageKey")) : block.getString("url");
                    String detail = block.getString("detail");
                    content.add(detail == null ? ImageContent.from(url) : ImageContent.from(url, ImageContent.DetailLevel.valueOf(detail)));
                }
            }
            checkInterrupted();
            return llm.chat(content);
        }
    }

    public static final class LlmTextTask implements Callable<JSONObject>, Serializable {
        private static final long serialVersionUID = 1L;
        private final String prompt;
        @Autowired private transient LlmService llm;
        public LlmTextTask(String prompt) { this.prompt = Objects.requireNonNull(prompt, "prompt"); }
        @Override public JSONObject call() throws Exception { checkInterrupted(); return llm.chat(prompt, JSONObject.class); }
    }
}
