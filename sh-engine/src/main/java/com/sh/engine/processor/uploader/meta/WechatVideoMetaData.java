package com.sh.engine.processor.uploader.meta;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @Author : caiwen
 * @Date: 2024/10/4
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class WechatVideoMetaData extends WorkMetaData {
    /**
     * 预览图片本地文件地址
     */
    private String preViewFilePath;

    private String category;
}
