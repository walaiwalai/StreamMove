package com.sh.config.utils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sh.config.model.config.StreamerConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * @Author : caiwen
 * @Date: 2024/9/30
 */
@Slf4j
public class FileStoreUtil {
    public static void saveToFile(File targetFile, Object content) {
        createFileIfNotExisted(targetFile);

        try {
            IOUtils.write(JSON.toJSONString(content), Files.newOutputStream(targetFile.toPath()), "utf-8");
        } catch (IOException e) {
            log.error("write to file fail, savePath: {}", targetFile.getAbsolutePath(), e);
        }
    }

    public static <T> T loadFromFile(File targetFile, TypeReference<T> typeReference) {
        String contentStr = null;
        try {
            contentStr = IOUtils.toString(Files.newInputStream(targetFile.toPath()), "utf-8");
        } catch (IOException e) {
            log.error("open file fail, savePath: {}", targetFile.getAbsolutePath(), e);
        }
        return JSON.parseObject(contentStr, typeReference);
    }

    private static void createFileIfNotExisted(File targetFile) {
        if (targetFile.exists()) {
            return;
        }

        try {
            targetFile.createNewFile();
        } catch (IOException e) {
            log.error("create file fail, savePath: {}", targetFile.getAbsolutePath(), e);
        }
    }
}
