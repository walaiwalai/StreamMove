package com.sh.engine.processor.uploader;

import com.sh.engine.constant.UploadPlatformEnum;
import org.springframework.stereotype.Component;


/**
 * @Author caiwen
 * @Date 2025 03 16 16 41
 **/
@Component
public class UCPanUploader extends AbstractAlistUploader {
    @Override
    public String getType() {
        return UploadPlatformEnum.UC_PAN.getType();
    }

    @Override
    public String getRootDirName() {
        return "UC网盘";
    }
}
