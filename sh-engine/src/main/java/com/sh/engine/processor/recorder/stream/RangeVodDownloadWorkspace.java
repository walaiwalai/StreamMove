package com.sh.engine.processor.recorder.stream;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * 管理 Range VOD 的预分配文件、源文件身份和已完成分片标记。
 */
final class RangeVodDownloadWorkspace {
    private static final String SOURCE_FILE_NAME = "source.mp4.download";
    private static final String METADATA_FILE_NAME = "metadata.properties";
    private static final String COMPLETED_DIRECTORY_NAME = "completed";

    private final Path sourceFile;
    private final Path completedDirectory;

    private RangeVodDownloadWorkspace(Path sourceFile, Path completedDirectory) {
        this.sourceFile = sourceFile;
        this.completedDirectory = completedDirectory;
    }

    /**
     * 打开可复用的工作区；源文件身份或大小变化时重置本组件的续传状态。
     */
    static RangeVodDownloadWorkspace prepare(Path workPath,
                                             long totalLength,
                                             int chunkSize,
                                             String validator,
                                             String sourceIdentity) throws IOException {
        Files.createDirectories(workPath);
        Path sourceFile = workPath.resolve(SOURCE_FILE_NAME);
        Path metadataFile = workPath.resolve(METADATA_FILE_NAME);
        Path completedDirectory = workPath.resolve(COMPLETED_DIRECTORY_NAME);
        Files.createDirectories(completedDirectory);

        RangeVodDownloadWorkspace workspace = new RangeVodDownloadWorkspace(
                sourceFile, completedDirectory);
        boolean reusable = metadataMatches(
                metadataFile, totalLength, chunkSize, validator, sourceIdentity)
                && Files.exists(sourceFile)
                && Files.size(sourceFile) == totalLength;
        if (!reusable) {
            deleteCompletedMarkers(completedDirectory);
            try (RandomAccessFile target = new RandomAccessFile(sourceFile.toFile(), "rw")) {
                target.setLength(totalLength);
            }
            writeMetadata(metadataFile, totalLength, chunkSize, validator, sourceIdentity);
        }
        return workspace;
    }

    /**
     * 删除本组件创建的文件；工作目录内存在未知文件时保留目录并报告失败。
     */
    static void cleanup(Path workPath) throws IOException {
        if (!Files.exists(workPath)) {
            return;
        }
        Path completedDirectory = workPath.resolve(COMPLETED_DIRECTORY_NAME);
        deleteCompletedMarkers(completedDirectory);
        Files.deleteIfExists(completedDirectory);
        Files.deleteIfExists(workPath.resolve(SOURCE_FILE_NAME));
        Files.deleteIfExists(workPath.resolve(METADATA_FILE_NAME));
        Files.deleteIfExists(workPath.resolve(METADATA_FILE_NAME + ".tmp"));
        Files.deleteIfExists(workPath);
    }

    Path getSourceFile() {
        return sourceFile;
    }

    boolean isChunkCompleted(int index) {
        return Files.exists(marker(index));
    }

    /**
     * 数据同步到磁盘后创建原子完成标记。
     */
    void markChunkCompleted(int index) throws IOException {
        Files.createFile(marker(index));
    }

    /**
     * 核验预分配文件和全部完成标记，避免把稀疏未下载区误判为成功。
     */
    void validate(long totalLength, int totalChunks) throws IOException {
        if (Files.size(sourceFile) != totalLength) {
            throw new IOException("Completed source file length does not match remote length");
        }
        for (int index = 0; index < totalChunks; index++) {
            if (!isChunkCompleted(index)) {
                throw new IOException("Missing completed marker for chunk: " + index);
            }
        }
    }

    private Path marker(int index) {
        return completedDirectory.resolve(String.format("%08d.done", index));
    }

    private static boolean metadataMatches(Path metadataFile,
                                           long totalLength,
                                           int chunkSize,
                                           String validator,
                                           String sourceIdentity) throws IOException {
        if (!Files.exists(metadataFile)) {
            return false;
        }
        Properties properties = new Properties();
        try (FileInputStream inputStream = new FileInputStream(metadataFile.toFile())) {
            properties.load(inputStream);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return String.valueOf(totalLength).equals(properties.getProperty("totalLength"))
                && String.valueOf(chunkSize).equals(properties.getProperty("chunkSize"))
                && validator.equals(properties.getProperty("validator", ""))
                && sourceIdentity.equals(properties.getProperty("sourceIdentity"));
    }

    private static void writeMetadata(Path metadataFile,
                                      long totalLength,
                                      int chunkSize,
                                      String validator,
                                      String sourceIdentity) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("totalLength", String.valueOf(totalLength));
        properties.setProperty("chunkSize", String.valueOf(chunkSize));
        properties.setProperty("validator", validator);
        properties.setProperty("sourceIdentity", sourceIdentity);
        Path temporaryFile = metadataFile.resolveSibling(METADATA_FILE_NAME + ".tmp");
        try (FileOutputStream outputStream = new FileOutputStream(temporaryFile.toFile())) {
            properties.store(outputStream, "Range VOD resume metadata");
            outputStream.getFD().sync();
        }
        try {
            Files.move(temporaryFile, metadataFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporaryFile, metadataFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteCompletedMarkers(Path completedDirectory) throws IOException {
        if (!Files.exists(completedDirectory)) {
            return;
        }
        try (DirectoryStream<Path> markers = Files.newDirectoryStream(completedDirectory, "*.done")) {
            for (Path completedMarker : markers) {
                Files.delete(completedMarker);
            }
        }
    }
}
