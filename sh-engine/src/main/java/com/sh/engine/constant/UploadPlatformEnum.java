package com.sh.engine.constant;

/**
 * 上传平台
 *
 * @Author caiwen
 * @Date 2024 07 07 17 20
 **/
public enum UploadPlatformEnum {
    BILI_CLIENT("BILI_CLIENT", "bilibili客户端"),
    BILI_WEB("BILI_WEB", "bilibili网页"),
    ALI_DRIVER("ALI_DRIVER", "阿里云盘"),
    DOU_YIN("DOU_YIN", "抖音"),
    WECHAT_VIDEO("WECHAT_VIDEO", "腾讯视频号"),
    MEI_TUAN_VIDEO("MEI_TUAN_VIDEO", "美团视频号"),
    MINIO("MINIO", "MINIO存储"),
    WECHAT_VIDEO_V2("WECHAT_VIDEO_V2", "腾讯视频号三方"),

    ;

    String type;
    String desc;

    UploadPlatformEnum(String type, String desc) {
        this.type = type;
        this.desc = desc;
    }

    public String getType() {
        return type;
    }

    public static UploadPlatformEnum of(String type) {
        for (UploadPlatformEnum value : UploadPlatformEnum.values()) {
            if (value.type.equals(type)) {
                return value;
            }
        }
        return null;
    }
}
