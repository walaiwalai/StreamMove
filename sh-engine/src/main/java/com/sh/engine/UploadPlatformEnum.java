package com.sh.engine;

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
}
