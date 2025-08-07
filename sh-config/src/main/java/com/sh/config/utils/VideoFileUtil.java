package com.sh.config.utils;

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
public class VideoFileUtil {
    public static final String SEG_FILE_NAME = "seg-%05d.ts";
    public static final String SEG_SNAPSHOT_FILE_NAME = "seg-%05d.jpg";

    public static String genSegName(int i) {
        return String.format(SEG_FILE_NAME, i);
    }

    public static String genSnapshotName(int i) {
        return String.format(SEG_SNAPSHOT_FILE_NAME, i);
    }

    public static int genIndex(String segName) {
        int start = segName.lastIndexOf("-");
        int end = segName.lastIndexOf(".");
        return Integer.parseInt(segName.substring(start + 1, end));
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
}
