package com.sh.config.utils;

import org.springframework.util.CollectionUtils;
import sun.applet.Main;

import java.io.*;
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

}
