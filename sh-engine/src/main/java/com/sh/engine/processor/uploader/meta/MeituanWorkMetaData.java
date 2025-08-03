package com.sh.engine.processor.uploader.meta;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @Author caiwen
 * @Date 2024 10 06 22 37
 **/
@EqualsAndHashCode(callSuper = true)
@Data
public class MeituanWorkMetaData extends WorkMetaData {
    /**
     * 预览图片本地文件地址
     */
    private String preViewFilePath;
}
