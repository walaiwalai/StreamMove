package com.sh.engine.task;

import com.alibaba.fastjson.JSONObject;
import com.sh.engine.model.asr.AsrSegment;
import com.sh.engine.model.highlight.core.OcrTextDetection;
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

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

/** driver 的标准能力入口；序列化对象只包含输入，不包含 Spring 服务和媒体文件。 */
@Service
public class ComputeTasks {
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

    private static List<String> imageKeys(List<String> keys) {
        Objects.requireNonNull(keys, "imageKeys");
        for (String key : keys) {
            if (key == null || key.trim().isEmpty()) {
                throw new IllegalArgumentException("Image object key is blank");
            }
        }
        return new ArrayList<>(keys);
    }

    private static void checkInterrupted() throws InterruptedException {
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
        private final String prompt;
        private final List<String> imageKeys;
        @Autowired private transient LlmService llm;
        @Autowired private transient OssUploadService oss;

        public LlmTask(String prompt, List<String> keys) {
            if (prompt == null || prompt.trim().isEmpty()) {
                throw new IllegalArgumentException("LLM prompt is blank");
            }
            this.prompt = prompt;
            imageKeys = imageKeys(keys);
        }

        @Override
        public JSONObject call() throws Exception {
            List<Content> content = new ArrayList<>();
            content.add(TextContent.from(prompt));
            for (String key : imageKeys) {
                checkInterrupted();
                content.add(ImageContent.from(oss.getImageDataUrl(key)));
            }
            checkInterrupted();
            return llm.chat(content);
        }
    }
}
