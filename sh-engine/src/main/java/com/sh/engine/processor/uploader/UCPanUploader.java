package com.sh.engine.processor.uploader;

import com.sh.engine.constant.UploadPlatformEnum;
import org.springframework.stereotype.Component;


/**
 * @Author caiwen
 * @Date 2025 03 16 16 41
 **/
@Component
public class UCPanUploader extends AbstractNetDiskUploader {
    @Override
    public String getType() {
        return UploadPlatformEnum.UC_PAN.getType();
    }
}
