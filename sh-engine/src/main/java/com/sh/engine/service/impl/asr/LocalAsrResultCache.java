package com.sh.engine.service.impl.asr;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.sh.engine.model.asr.AsrSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 将云端 ASR 结果持久化到录像目录，并用音频内容及请求参数寻址。
 * 缓存异常只影响复用，不阻断正常的云端转写。
 */
@Component
@Slf4j
public class LocalAsrResultCache {
    private static final int SCHEMA_VERSION = 1;
    private static final String CACHE_NAMESPACE = "aliyun-asr-cache-v1";
    private static final String CACHE_DIRECTORY = ".highlight-evidence/asr-cache";
    private static final int BUFFER_SIZE = 8192;

    private final Gson gson = new Gson();

    /**
     * 根据实际音频内容、模型和绝对时间段生成稳定缓存键。
     *
     * @return 计算失败时返回空，由调用方继续云端识别
     */
    public Optional<String> createKey(
            File audioFile, String model, int startSeconds, int endSeconds) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, CACHE_NAMESPACE);
            updateDigest(digest, model);
            updateDigest(digest, String.valueOf(startSeconds));
            updateDigest(digest, String.valueOf(endSeconds));
            updateDigest(digest, audioFile);
            return Optional.of(toHex(digest.digest()));
        } catch (IOException | NoSuchAlgorithmException e) {
            log.warn("Cannot build local ASR cache key: {}", audioFile, e);
            return Optional.empty();
        }
    }

    /**
     * 读取本地持久缓存。Optional 可区分缓存未命中和已缓存的空转写结果。
     */
    public Optional<List<AsrSegment>> load(File recordDirectory, String cacheKey) {
        Path cacheFile = cacheFile(recordDirectory, cacheKey);
        if (!Files.isRegularFile(cacheFile)) {
            return Optional.empty();
        }
        try {
            String json = new String(Files.readAllBytes(cacheFile), StandardCharsets.UTF_8);
            CacheDocument document = gson.fromJson(json, CacheDocument.class);
            if (document == null
                    || document.schemaVersion != SCHEMA_VERSION
                    || document.segments == null) {
                log.warn("Ignoring incompatible local ASR cache: {}", cacheFile);
                return Optional.empty();
            }
            return Optional.of(Collections.unmodifiableList(
                    new ArrayList<>(document.segments)));
        } catch (IOException | JsonParseException e) {
            log.warn("Cannot read local ASR cache: {}", cacheFile, e);
            return Optional.empty();
        }
    }

    /** 将 ASR 结果原子写入录像目录，应用重启后仍可复用。 */
    public void save(
            File recordDirectory, String cacheKey, List<AsrSegment> segments) {
        Path cacheFile = cacheFile(recordDirectory, cacheKey);
        CacheDocument document = new CacheDocument(
                SCHEMA_VERSION, new ArrayList<>(segments));
        Path temporaryFile = null;
        try {
            Files.createDirectories(cacheFile.getParent());
            temporaryFile = Files.createTempFile(
                    cacheFile.getParent(), cacheKey + "-", ".tmp");
            Files.write(temporaryFile,
                    gson.toJson(document).getBytes(StandardCharsets.UTF_8));
            moveAtomically(temporaryFile, cacheFile);
        } catch (IOException e) {
            log.warn("Cannot write local ASR cache: {}", cacheFile, e);
            if (temporaryFile != null) {
                deleteTemporaryFile(temporaryFile);
            }
        }
    }

    private void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private void updateDigest(MessageDigest digest, File file) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file.toPath()))) {
            int length;
            while ((length = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, length);
            }
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            value.append(String.format("%02x", item & 0xff));
        }
        return value.toString();
    }

    private Path cacheFile(File recordDirectory, String cacheKey) {
        return recordDirectory.toPath()
                .resolve(CACHE_DIRECTORY)
                .resolve(cacheKey + ".json");
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteTemporaryFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Cannot delete temporary local ASR cache file: {}", path, e);
        }
    }

    private static final class CacheDocument {
        private int schemaVersion;
        private List<AsrSegment> segments;

        private CacheDocument() {
        }

        private CacheDocument(int schemaVersion, List<AsrSegment> segments) {
            this.schemaVersion = schemaVersion;
            this.segments = segments;
        }
    }
}
