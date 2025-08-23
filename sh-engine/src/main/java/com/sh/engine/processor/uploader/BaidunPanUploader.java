package com.sh.engine.processor.uploader;

import com.sh.engine.constant.UploadPlatformEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 百度云盘上传
 *
 * @Author : caiwen
 * @Date: 2025/1/29
 */
@Slf4j
@Component
public class BaidunPanUploader extends AbstractAlistUploader {
    @Override
    public String getType() {
        return UploadPlatformEnum.BAIDU_PAN.getType();
    }

    @Override
    public String getRootDirName() {
        return "百度网盘";
    }
}
