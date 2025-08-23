package com.sh.engine.processor.uploader;

import com.sh.engine.constant.UploadPlatformEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * @Author caiwen
 * @Date 2025 08 22 23 40
 **/
@Slf4j
@Component
public class ObjectStorageUploader extends AbstractAlistUploader {
    @Override
    public String getType() {
        return UploadPlatformEnum.OBJECT_STORAGE.getType();
    }

    @Override
    public String getRootDirName() {
        return "对象存储";
    }
}
