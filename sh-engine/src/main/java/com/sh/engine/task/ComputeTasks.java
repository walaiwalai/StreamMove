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
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.concurrent.Callable;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.util.zip.Deflater;

/** driver 的标准能力入口；小批图片内联，大批图片打包 OSS，音频使用 OSS。 */
@Service
@Slf4j
public class ComputeTasks {
    public static final int OCR_BATCH_SIZE = 8;
    private static final int INLINE_IMAGE_BYTES = 32 * 1024;
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

    /** 单图入口沿用相同的内联/打包规则。 */
    public List<OcrTextDetection> recognize(byte[] image, String fileName) {
        return recognizeBatch(Collections.singletonList(image), Collections.singletonList(fileName)).get(0);
    }

    public List<List<OcrTextDetection>> recognizeBatch(List<byte[]> images, List<String> fileNames) {
        validateImages(images, fileNames);
        List<List<OcrTextDetection>> result = new ArrayList<>();
        for (int from = 0; from < images.size(); from += OCR_BATCH_SIZE) {
            int end = Math.min(from + OCR_BATCH_SIZE, images.size());
            List<byte[]> batch = images.subList(from, end);
            List<String> names = fileNames.subList(from, end);
            result.addAll(execute(TaskType.OCR, keys -> new OcrTask(prepareImages(batch, names, keys))));
        }
        return result;
    }

    public <R> R withImage(TaskType type, byte[] image, String fileName, Function<ImageBatch, Callable<R>> task) {
        return execute(type, keys -> task.apply(prepareImages(Collections.singletonList(image),
                Collections.singletonList(fileName), keys)));
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
            List<byte[]> images = new ArrayList<>();
            List<String> names = new ArrayList<>();
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
                        block.put("imageIndex", images.size());
                        images.add(Base64.getDecoder().decode(data));
                        names.add("image-" + names.size() + extension);
                    } else {
                        block.put("url", Objects.requireNonNull(url, "image URL"));
                    }
                    block.put("detail", image.detailLevel().name());
                } else { throw new IllegalArgumentException("Only text and image model inputs are supported"); }
                blocks.add(block);
            }
            return new LlmTask(blocks, images.isEmpty() ? null : prepareImages(images, names, keys));
        });
    }

    private String upload(File file, List<String> keys) {
        String key = "compute-input/" + UUID.randomUUID() + suffix(file.getName());
        keys.add(key);
        oss.uploadAndGetUrl(file, key);
        return key;
    }

    private ImageBatch prepareImages(List<byte[]> images, List<String> names, List<String> keys) {
        validateImages(images, names);
        long size = images.stream().mapToLong(image -> image.length).sum();
        if (size <= INLINE_IMAGE_BYTES) {
            List<byte[]> data = new ArrayList<>();
            for (byte[] image : images) { data.add(image.clone()); }
            return new ImageBatch(names, data, null);
        }
        Path archive = null;
        try {
            archive = Files.createTempFile("compute-images-", ".zip");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
                zip.setLevel(Deflater.NO_COMPRESSION);
                for (int index = 0; index < images.size(); index++) {
                    zip.putNextEntry(new ZipEntry(String.valueOf(index)));
                    zip.write(images.get(index));
                    zip.closeEntry();
                }
            }
            return new ImageBatch(names, null, upload(archive.toFile(), keys));
        } catch (IOException error) { throw new IllegalStateException("Cannot prepare image archive", error); }
        finally { if (archive != null) { archive.toFile().delete(); } }
    }

    private static void validateImages(List<byte[]> images, List<String> names) {
        Objects.requireNonNull(images, "images"); Objects.requireNonNull(names, "fileNames");
        if (images.size() != names.size()) { throw new IllegalArgumentException("Image/name count mismatch"); }
        for (int index = 0; index < images.size(); index++) {
            if (images.get(index) == null || images.get(index).length == 0
                    || names.get(index) == null || names.get(index).trim().isEmpty()) {
                throw new IllegalArgumentException("Image or filename is empty");
            }
        }
    }

    private static String suffix(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : ".jpg";
    }

    private <R> R execute(TaskType type, Function<List<String>, Callable<R>> prepare) {
        long started = System.nanoTime();
        List<String> keys = new ArrayList<>();
        RExecutorFuture<R> task;
        try { task = client.submit(type, prepare.apply(keys)); }
        catch (RuntimeException error) { deleteInputs(keys); throw error; }
        // 等待超时或调用线程中断时，执行可能仍在继续；只在原任务终结后删除输入。
        if (!keys.isEmpty()) {
            task.whenCompleteAsync((value, error) -> deleteInputs(keys), ExecutorPoolUtil.getFlushPool());
        }
        log.info("Compute task submitted, type: {}, taskId: {}", type, task.getTaskId());
        try {
            R result = client.result(task);
            log.info("Compute task completed, type: {}, taskId: {}, totalMillis: {}", type, task.getTaskId(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            return result;
        } catch (InterruptedException error) {
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

    public static final class ImageBatch implements Serializable {
        private static final long serialVersionUID = 1L;
        private final List<String> fileNames;
        private final List<byte[]> inline;
        private final String archiveKey;

        public ImageBatch(List<String> fileNames, List<byte[]> inline, String archiveKey) {
            this.fileNames = new ArrayList<>(fileNames);
            this.inline = inline;
            this.archiveKey = archiveKey;
        }

        public List<String> getFileNames() { return Collections.unmodifiableList(fileNames); }

        /** 在内存中按索引读取，不向磁盘解压文件名。 */
        public List<byte[]> read(OssUploadService oss) throws IOException, InterruptedException {
            checkInterrupted();
            if (inline != null) { return inline; }
            ByteArrayOutputStream archive = new ByteArrayOutputStream();
            oss.download(archiveKey, archive);
            checkInterrupted();
            List<byte[]> images = new ArrayList<>();
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive.toByteArray()))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    checkInterrupted();
                    if (images.size() >= fileNames.size() || entry.isDirectory()
                            || !entry.getName().equals(String.valueOf(images.size()))) {
                        throw new IOException("Unexpected image archive entry");
                    }
                    ByteArrayOutputStream image = new ByteArrayOutputStream();
                    org.apache.commons.io.IOUtils.copy(zip, image);
                    if (image.size() == 0) { throw new IOException("Empty image archive entry"); }
                    images.add(image.toByteArray());
                }
            }
            if (images.size() != fileNames.size()) { throw new IOException("Image archive count mismatch"); }
            return images;
        }

        public String dataUrl(List<byte[]> images, int index) {
            String name = fileNames.get(index);
            String mime = name.endsWith(".png") ? "image/png" : name.endsWith(".webp") ? "image/webp"
                    : name.endsWith(".gif") ? "image/gif" : "image/jpeg";
            return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(images.get(index));
        }
    }

    public static final class OcrTask implements Callable<List<List<OcrTextDetection>>>, Serializable {
        private static final long serialVersionUID = 1L;
        private final List<String> imageKeys;
        private final ImageBatch images;
        @Autowired private transient OcrService ocr;
        @Autowired private transient OssUploadService oss;

        public OcrTask(List<String> keys) {
            images = null;
            imageKeys = imageKeys(keys);
            if (imageKeys.isEmpty()) {
                throw new IllegalArgumentException("OCR requires at least one image");
            }
        }

        public OcrTask(ImageBatch images) { this.images = images; this.imageKeys = null; }

        @Override
        public List<List<OcrTextDetection>> call() throws Exception {
            List<List<OcrTextDetection>> result = new ArrayList<>();
            if (images != null) {
                List<byte[]> data = images.read(oss);
                for (int index = 0; index < data.size(); index++) {
                    checkInterrupted();
                    result.add(ocr.recognize(data.get(index), images.getFileNames().get(index)));
                }
                return result;
            }
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
        private final ImageBatch images;
        @Autowired private transient LlmService llm;
        @Autowired private transient OssUploadService oss;

        public LlmTask(String prompt, List<String> keys) {
            images = null;
            if (prompt == null || prompt.trim().isEmpty()) { throw new IllegalArgumentException("LLM prompt is blank"); }
            blocks = new ArrayList<>();
            JSONObject text = new JSONObject(); text.put("text", prompt); blocks.add(text);
            for (String key : imageKeys(keys)) {
                JSONObject image = new JSONObject(); image.put("imageKey", key); blocks.add(image);
            }
        }

        private LlmTask(List<JSONObject> blocks, ImageBatch images) { this.blocks = new ArrayList<>(blocks); this.images = images; }

        @Override
        public JSONObject call() throws Exception {
            List<Content> content = new ArrayList<>();
            List<byte[]> data = images == null ? Collections.emptyList() : images.read(oss);
            for (JSONObject block : blocks) {
                checkInterrupted();
                if (block.containsKey("text")) { content.add(TextContent.from(block.getString("text"))); }
                else {
                    String url = block.containsKey("imageIndex") ? images.dataUrl(data, block.getIntValue("imageIndex"))
                            : block.containsKey("imageKey") ? oss.getImageDataUrl(block.getString("imageKey")) : block.getString("url");
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
