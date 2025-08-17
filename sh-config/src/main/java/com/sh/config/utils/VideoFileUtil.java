package com.sh.config.utils;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author caiWen
 * @date 2023/2/16 23:28
 */
public class VideoFileUtil {
    public static final String SEG_FILE_NAME_V2 = "P%02d.ts";

    public static Integer getSnapshotIndex(File snapshotFile) {
        String name = snapshotFile.getName();
        int start = name.lastIndexOf("#");
        int end = name.lastIndexOf(".");
        return Integer.parseInt(name.substring(start + 1, end));
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

    public static void main(String[] args) {
        List<File> videos = FileUtils.listFiles(new File("G:\\stream_record\\download\\mytest-mac\\2025-08-15-20-59-48"), new String[]{"mp4"}, false)
                .stream()
                .sorted(Comparator.comparingInt(VideoFileUtil::getVideoIndex))
                .collect(Collectors.toList());
        System.out.println(videos);
    }
}
