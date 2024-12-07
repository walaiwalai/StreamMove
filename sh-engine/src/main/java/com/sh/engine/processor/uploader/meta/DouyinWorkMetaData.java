package com.sh.engine.processor.uploader.meta;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @Author : caiwen
 * @Date: 2024/9/30
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class DouyinWorkMetaData extends WorkMetaData {
    /**
     * 预览图片本地文件地址
     */
    private String preViewFilePath;

    /**
     * 定位：如杭州
     */
    private String location;
}
