package com.sh.engine.processor.uploader;

import com.sh.engine.constant.UploadPlatformEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * @Author : caiwen
 * @Date: 2025/10/15
 */
@Slf4j
@Component
public class AlyOssUploader extends AbstractNetDiskUploader{
    @Override
    public String getType() {
        return UploadPlatformEnum.ALY_OSS.getType();
    }
}
