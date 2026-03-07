package com.sh.config.utils;

import cn.hutool.core.io.FileUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;

/**
 * @author caiWen
 * @date 2023/2/16 23:28
 */
@Slf4j
public class VideoFileUtil {
    public static final String SEG_FILE_PREFIX = "P%02d";
    /**
     * 临时处理文件的路径
     */
    public static final String PROCESS_TMP_DIR = "/home/admin/stream/dump";

    public static Integer getSnapshotIndex(File snapshotFile) {
        String name = snapshotFile.getName();
        int start = name.lastIndexOf("#");
        int end = name.lastIndexOf(".");
        return Integer.parseInt(name.substring(start + 1, end));
    }

    public static Integer getSnapshotVid(File snapshotFile) {
        String name = snapshotFile.getName();
        int end = name.lastIndexOf("#");
        return Integer.parseInt(name.substring(1, end));
    }

    public static File getSourceVideoFile(File snapshotFile) {
        String name = FileUtil.getPrefix(snapshotFile);
        int end = name.lastIndexOf("#");
        String sourcePrefix = name.substring(0, end);
        return new File(new File(snapshotFile.getParent()).getParent(), sourcePrefix + ".mp4");
    }

    public static Integer getVideoIndex(File videoFile) {
        String name = videoFile.getName();
        int end = name.lastIndexOf(".");
        return Integer.parseInt(name.substring(1, end));
    }

    public static String getSnapshotSourceFileName(File snapshotFile) {
        String name = snapshotFile.getName();
        int end = name.lastIndexOf("#");
        return name.substring(0, end);
    }


    public static int genIndex(String segName) {
        int end = segName.lastIndexOf(".");
        return Integer.parseInt(segName.substring(1, end));
    }

    public static byte[] fetchBlock(File targetFile, long start, int blockSize) throws IOException {
        byte[] b = new byte[blockSize];
        try (RandomAccessFile raf = new RandomAccessFile(targetFile, "r")) {
            raf.seek(start);
            raf.read(b, 0, blockSize);
        }
        return b;
    }

    public static Long getCreateTime(File targetFile) {
        if (!targetFile.exists()) {
            return -1L;
        }
        Path path = Paths.get(targetFile.getAbsolutePath());
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            return attributes.creationTime().toMillis();
        } catch (IOException e) {
            return -1L;
        }
    }

    public static long getLastModifiedTime(File targetFile) {
        if (!targetFile.exists()) {
            return -1L;
        }

        return targetFile.lastModified();
    }

    public static String calculateSHA1ByChunk(File file, int chunkSize) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
            try (InputStream fis = Files.newInputStream(file.toPath())) {
                byte[] buffer = new byte[chunkSize];
                int n = 0;
                while ((n = fis.read(buffer)) != -1) {
                    digest.update(buffer, 0, n);
                }
            }
        } catch (Exception e) {
            return null;
        }

        byte[] hashBytes = digest.digest();
        StringBuilder hashString = new StringBuilder();
        for (byte b : hashBytes) {
            hashString.append(String.format("%02X", b));
        }
        return hashString.toString();
    }

    /**
     * 获取临时处理目录
     *
     * @return 临时处理目录
     */
    public static File getAmountedTmpDir() {
        File file = new File(PROCESS_TMP_DIR, DateUtil.covertTimeStampToStr(System.currentTimeMillis()));
        file.mkdirs();
        return file;
    }


    /**
     * 拷贝挂载的临时文件到本地
     *
     * @param amountedFile 挂载的文件
     * @return 本地文件
     */
    public static File copyMountedFileToLocal(File amountedFile) {
        File tmpDir = getAmountedTmpDir();
        File localFile = new File(tmpDir, amountedFile.getName());

        try {
            FileUtils.copyFile(amountedFile, localFile);
        } catch (IOException e) {
            log.error("copy file fail, from: {}, to: {}", amountedFile.getAbsolutePath(), localFile.getAbsolutePath(), e);
            FileUtils.deleteQuietly(tmpDir);
            return null;
        }
        return localFile;
    }
}
