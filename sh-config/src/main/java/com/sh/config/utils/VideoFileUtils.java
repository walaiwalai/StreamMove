package com.sh.config.utils;

import org.springframework.util.CollectionUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author caiWen
 * @date 2023/2/16 23:28
 */
public class VideoFileUtils {
    /**
     * 获取目录下所有文件(按时间排序)
     *
     * @param files
     * @return
     */
    public static List<File> getFileSort(List<File> files) {
        if (CollectionUtils.isEmpty(files)) {
            return new ArrayList<>();
        }
        Collections.sort(files, (file, newFile) -> {
            if (file.lastModified() < newFile.lastModified()) {
                return -1;
            } else if (file.lastModified() == newFile.lastModified()) {
                return 0;
            } else {
                return 1;
            }
        });

        return files;
    }

    public static Integer getIndexOnLivingVideo(File file) {
        String segFileName = file.getName();
        int tail = segFileName.lastIndexOf(".");
        int head = segFileName.lastIndexOf("-");
        return Integer.valueOf(segFileName.substring(head + 1, tail));
    }

    public static String genSegName(int i) {
        return "seg-" + String.format("%04d", i) + ".ts";
    }

    public static byte[] fetchBlock(File targetFile, long start, int blockSize) throws IOException {
        byte[] b = new byte[blockSize];
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(targetFile, "r");
            raf.seek(start);
            raf.read(b, 0, blockSize);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (raf != null) {
                raf.close();
            }
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
        System.out.println(getIndexOnLivingVideo(new File("TheShy-part-001.mp4")));
    }
}
