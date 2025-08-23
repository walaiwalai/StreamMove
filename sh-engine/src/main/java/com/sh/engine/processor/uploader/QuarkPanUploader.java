package com.sh.engine.processor.uploader;

import com.sh.engine.constant.UploadPlatformEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * @Author : caiwen
 * @Date: 2025/1/30
 */
@Slf4j
@Component
public class QuarkPanUploader extends AbstractAlistUploader {
    @Override
    public String getType() {
        return UploadPlatformEnum.QUARK_PAN.getType();
    }

    @Override
    public String getRootDirName() {
        return "夸克云盘";
    }
}
