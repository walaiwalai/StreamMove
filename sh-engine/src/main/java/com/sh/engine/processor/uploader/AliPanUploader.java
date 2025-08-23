package com.sh.engine.processor.uploader;

import com.sh.engine.constant.UploadPlatformEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * @Author : caiwen
 * @Date: 2025/1/31
 */
@Slf4j
@Component
public class AliPanUploader extends AbstractAlistUploader {
    @Override
    public String getType() {
        return UploadPlatformEnum.ALI_PAN.getType();
    }

    @Override
    public String getRootDirName() {
        return "阿里云盘";
    }
}