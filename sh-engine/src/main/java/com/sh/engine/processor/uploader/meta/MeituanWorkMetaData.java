package com.sh.engine.processor.uploader.meta;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.commons.lang3.StringUtils;

import java.io.File;

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

    @Override
    protected boolean check() {
        return StringUtils.isNotBlank(preViewFilePath) && new File(preViewFilePath).exists();
    }

}
