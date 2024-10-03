package com.sh.engine.constant;

import com.sh.engine.processor.uploader.DouyinUploader;
import com.sh.engine.processor.uploader.Uploader;

/**
 * 上传平台
 *
 * @Author caiwen
 * @Date 2024 07 07 17 20
 **/
public enum UploadPlatformEnum {
    BILI_CLIENT("BILI_CLIENT", "bilibili客户端", null),
    BILI_WEB("BILI_WEB", "bilibili网页", null),
    ALI_DRIVER("ALI_DRIVER", "阿里云盘", null),
    DOU_YIN("DOU_YIN", "抖音", DouyinUploader.class),
    TENCENT("TENCENT", "腾讯视频号", DouyinUploader.class),
    ;

    String type;
    String desc;
    Class<? extends Uploader> uploader;

    UploadPlatformEnum( String type, String desc, Class<? extends Uploader> uploader ) {
        this.type = type;
        this.desc = desc;
        this.uploader = uploader;
    }

    public String getType() {
        return type;
    }

    public Class<? extends Uploader> getUploader() {
        return uploader;
    }

    public static UploadPlatformEnum of( String type ) {
        for (UploadPlatformEnum value : UploadPlatformEnum.values()) {
            if (value.type.equals(type)) {
                return value;
            }
        }
        return null;
    }
}
