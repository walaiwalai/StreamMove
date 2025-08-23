package com.sh.engine.processor.uploader;

import com.sh.engine.constant.UploadPlatformEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * @Author caiwen
 * @Date 2025 05 29 23 23
 **/
@Slf4j
@Component
public class TianyiPanUploader extends AbstractAlistUploader {
    @Override
    public String getType() {
        return UploadPlatformEnum.TIAN_YI_PAN.getType();
    }

    @Override
    protected String getRootDirName() {
        return "天翼云盘";
    }
}
