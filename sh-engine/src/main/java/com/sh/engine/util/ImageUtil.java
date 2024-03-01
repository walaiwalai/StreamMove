package com.sh.engine.util;

import cn.hutool.core.codec.Base64Encoder;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;

/**
 * @Author caiwen
 * @Date 2024 02 29 19 58
 **/
@Slf4j
public class ImageUtil {
    /**
     * 图片转base64字符串
     *
     * @param imgFile 图片路径
     * @return
     */
    public static String imageToBase64Str(File imgFile) {
        InputStream inputStream = null;
        byte[] data = null;
        try {
            inputStream = Files.newInputStream(imgFile.toPath());
            data = new byte[inputStream.available()];
            inputStream.read(data);
            inputStream.close();
        } catch (Exception e) {
            log.error("convert base64 error, file: {}", imgFile.getAbsolutePath(), e);
            return null;
        }
        Base64Encoder encoder = new Base64Encoder();
        return encoder.encode(data);
    }

}
