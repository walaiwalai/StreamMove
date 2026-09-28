package com.sh.engine.processor.plugin.highlight.talk;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.engine.model.asr.AsrSegment;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 管理谈话直播的本地 ASR 与分析文件，所有写入均先落临时文件再原子替换。
 */
final class TalkTranscriptStore {
    private static final int CACHE_SCHEMA_VERSION = 1;
    private static final String WORKSPACE_NAME = ".talk-highlight";

    private final File workspaceDirectory;
    private final File asrDirectory;
    private final File analysisDirectory;
    private final File clipsDirectory;

    TalkTranscriptStore(File inputDirectory) {
        this.workspaceDirectory = new File(inputDirectory, WORKSPACE_NAME);
        this.asrDirectory = new File(workspaceDirectory, "asr");
        this.analysisDirectory = new File(workspaceDirectory, "analysis");
        this.clipsDirectory = new File(workspaceDirectory, "clips");
        createDirectory(asrDirectory);
        createDirectory(analysisDirectory);
        createDirectory(clipsDirectory);
    }

    /**
     * 读取与源文件元数据完全匹配的本地 ASR 缓存。
     */
    Optional<SourceTranscript> loadSourceTranscript(
            TalkVideoTimeline.SourceVideo sourceVideo, int sourceIndex) {
        String fingerprint = fingerprint(sourceVideo);
        File cacheFile = sourceCacheFile(sourceIndex, fingerprint);
        if (!cacheFile.isFile()) {
            return Optional.empty();
        }
        try {
            String json = new String(Files.readAllBytes(cacheFile.toPath()), StandardCharsets.UTF_8);
            SourceTranscriptCache cache = JSON.parseObject(json, SourceTranscriptCache.class);
            if (!isMatchingCache(cache, sourceVideo, fingerprint)) {
                return Optional.empty();
            }
            List<AsrSegment> segments = cache.getSegments() == null
                    ? Collections.emptyList() : cache.getSegments();
            return Optional.of(new SourceTranscript(sourceVideo, fingerprint, segments));
        } catch (IOException | RuntimeException e) {
            throw fileFailure("read ASR cache", cacheFile, e);
        }
    }

    /**
     * 保存单个源文件的 ASR 结果；调用方应在每个远程请求成功后立即调用。
     */
    SourceTranscript saveSourceTranscript(TalkVideoTimeline.SourceVideo sourceVideo,
                                          int sourceIndex,
                                          List<AsrSegment> segments) {
        String fingerprint = fingerprint(sourceVideo);
        SourceTranscriptCache cache = new SourceTranscriptCache();
        cache.setSchemaVersion(CACHE_SCHEMA_VERSION);
        cache.setFingerprint(fingerprint);
        cache.setSourceFile(sourceVideo.getVideoFile().getName());
        cache.setSourceLength(sourceVideo.getVideoFile().length());
        cache.setSourceLastModified(sourceVideo.getVideoFile().lastModified());
        cache.setDurationSeconds(sourceVideo.getDurationSeconds());
        cache.setCreatedAt(Instant.now().toString());
        cache.setSegments(new ArrayList<>(segments));
        writeJson(sourceCacheFile(sourceIndex, fingerprint), cache);
        return new SourceTranscript(sourceVideo, fingerprint, segments);
    }

    /**
     * 保存合并后的机器可读 JSON 和人可读 SRT，二者时间均相对整场直播。
     */
    void saveCombinedTranscript(List<TalkTranscriptSegment> segments, String transcriptFingerprint) {
        TranscriptDocument document = new TranscriptDocument();
        document.setSchemaVersion(CACHE_SCHEMA_VERSION);
        document.setFingerprint(transcriptFingerprint);
        document.setCreatedAt(Instant.now().toString());
        document.setSegments(new ArrayList<>(segments));
        writeJson(new File(workspaceDirectory, "transcript.json"), document);
        writeText(new File(workspaceDirectory, "transcript.srt"), toSrt(segments));
    }

    File analysisCacheFile(String transcriptFingerprint, int windowIndex) {
        File versionDirectory = new File(analysisDirectory, "v1-" + transcriptFingerprint.substring(0, 16));
        createDirectory(versionDirectory);
        return new File(versionDirectory, String.format("window-%03d.json", windowIndex));
    }

    <T> Optional<T> readJson(File file, Class<T> resultType) {
        if (!file.isFile()) {
            return Optional.empty();
        }
        try {
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            return Optional.ofNullable(JSON.parseObject(json, resultType));
        } catch (IOException | RuntimeException e) {
            throw fileFailure("read JSON", file, e);
        }
    }

    void writeJson(File file, Object value) {
        String json = JSON.toJSONString(value, SerializerFeature.PrettyFormat);
        writeText(file, json + System.lineSeparator());
    }

    File getWorkspaceDirectory() {
        return workspaceDirectory;
    }

    File getClipsDirectory() {
        return clipsDirectory;
    }

    static String combinedFingerprint(List<SourceTranscript> transcripts) {
        StringBuilder source = new StringBuilder();
        for (SourceTranscript transcript : transcripts) {
            source.append(transcript.getFingerprint()).append('|');
        }
        return sha256(source.toString());
    }

    private File sourceCacheFile(int sourceIndex, String fingerprint) {
        return new File(asrDirectory,
                String.format("source-%02d-%s.json", sourceIndex, fingerprint.substring(0, 16)));
    }

    private boolean isMatchingCache(SourceTranscriptCache cache,
                                    TalkVideoTimeline.SourceVideo sourceVideo,
                                    String fingerprint) {
        File sourceFile = sourceVideo.getVideoFile();
        return cache != null
                && cache.getSchemaVersion() == CACHE_SCHEMA_VERSION
                && fingerprint.equals(cache.getFingerprint())
                && sourceFile.getName().equals(cache.getSourceFile())
                && sourceFile.length() == cache.getSourceLength()
                && sourceFile.lastModified() == cache.getSourceLastModified();
    }

    private static String fingerprint(TalkVideoTimeline.SourceVideo sourceVideo) {
        File sourceFile = sourceVideo.getVideoFile();
        String identity;
        try {
            identity = sourceFile.getCanonicalPath();
        } catch (IOException e) {
            identity = sourceFile.getAbsolutePath();
        }
        identity += "|" + sourceFile.length()
                + "|" + sourceFile.lastModified()
                + "|" + sourceVideo.getDurationSeconds();
        return sha256(identity);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                hex.append(String.format("%02x", current & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private void writeText(File targetFile, String content) {
        createDirectory(targetFile.getParentFile());
        Path target = targetFile.toPath();
        Path temporary = target.resolveSibling(targetFile.getName() + ".tmp");
        try {
            Files.write(temporary, content.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temporary, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw fileFailure("write local cache", targetFile, e);
        }
    }

    private static void createDirectory(File directory) {
        try {
            Files.createDirectories(directory.toPath());
        } catch (IOException e) {
            throw fileFailure("create directory", directory, e);
        }
    }

    private static StreamerRecordException fileFailure(String operation, File file, Exception cause) {
        return new StreamerRecordException(
                ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR,
                operation + " failed: " + file.getAbsolutePath(), cause);
    }

    private static String toSrt(List<TalkTranscriptSegment> segments) {
        StringBuilder srt = new StringBuilder();
        for (int index = 0; index < segments.size(); index++) {
            TalkTranscriptSegment segment = segments.get(index);
            srt.append(index + 1).append('\n')
                    .append(formatSrtTime(segment.getGlobalStartSeconds()))
                    .append(" --> ")
                    .append(formatSrtTime(segment.getGlobalEndSeconds())).append('\n')
                    .append(segment.getText()).append("\n\n");
        }
        return srt.toString();
    }

    private static String formatSrtTime(int seconds) {
        int safeSeconds = Math.max(0, seconds);
        return String.format("%02d:%02d:%02d,000",
                safeSeconds / 3600, (safeSeconds % 3600) / 60, safeSeconds % 60);
    }

    @Data
    private static class SourceTranscriptCache {
        private int schemaVersion;
        private String fingerprint;
        private String sourceFile;
        private long sourceLength;
        private long sourceLastModified;
        private double durationSeconds;
        private String createdAt;
        private List<AsrSegment> segments;
    }

    @Data
    private static class TranscriptDocument {
        private int schemaVersion;
        private String fingerprint;
        private String createdAt;
        private List<TalkTranscriptSegment> segments;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class TalkTranscriptSegment {
        private String evidenceId;
        private String sourceFile;
        private int sourceStartSeconds;
        private int sourceEndSeconds;
        private int globalStartSeconds;
        private int globalEndSeconds;
        private String text;
    }

    @Data
    @AllArgsConstructor
    static class SourceTranscript {
        private TalkVideoTimeline.SourceVideo sourceVideo;
        private String fingerprint;
        private List<AsrSegment> segments;
    }
}
