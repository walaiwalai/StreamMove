package com.sh.engine.processor.uploader;

import com.google.common.collect.Maps;
import com.sh.engine.constant.UploadPlatformEnum;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * @Author : caiwen
 * @Date: 2024/9/29
 */
@Component
public class UploaderFactory {
    /**
     * 上传器
     */
    private static Map<String, Uploader> uploaderMap = Maps.newHashMap();

    /**
     * 登录二维码名称
     */
    private static Map<String, String> qrCodemap = Maps.newHashMap();

    /**
     * 存储个人信息的key
     */
    private static Map<String, String> accountKeymap = Maps.newHashMap();

    /**
     * 元数据文件名称
     */
    private static Map<String, String> uploaderMetaFileName = Maps.newHashMap();

    @Resource
    private List<Uploader> uploaders;

    @PostConstruct
    private void init() {
        for (Uploader uploader : uploaders) {
            uploaderMap.put(uploader.getType(), uploader);
        }

        qrCodemap.put(UploadPlatformEnum.DOU_YIN.getType(), "douyin_login_qrcode.png");
        qrCodemap.put(UploadPlatformEnum.WECHAT_VIDEO.getType(), "wechat_login_qrcode.png");

        accountKeymap.put(UploadPlatformEnum.DOU_YIN.getType(), "douyin-cookies.json");
        accountKeymap.put(UploadPlatformEnum.WECHAT_VIDEO.getType(), "wechat-video-cookies.json");
        accountKeymap.put(UploadPlatformEnum.MEI_TUAN_VIDEO.getType(), "meituan-video-cookies.json");

        uploaderMetaFileName.put(UploadPlatformEnum.DOU_YIN.getType(), "douyin-metaData.json");
        uploaderMetaFileName.put(UploadPlatformEnum.BILI_CLIENT.getType(), "bili-client-metaData.json");
        uploaderMetaFileName.put(UploadPlatformEnum.ALI_DRIVER.getType(), "ali-driver-metaData.json");
        uploaderMetaFileName.put(UploadPlatformEnum.WECHAT_VIDEO.getType(), "wechat-video-metaData.json");
        uploaderMetaFileName.put(UploadPlatformEnum.MEI_TUAN_VIDEO.getType(), "meituan-video-metaData.json");
    }

    public static Uploader getUploader(String type) {
        return uploaderMap.get(type);
    }

    public static String getMetaFileName(String type) {
        return uploaderMetaFileName.get(type);
    }

    public static String getAccountFileName(String type) {
        return accountKeymap.get(type);
    }

    public static String getQrCodeFileName(String type) {
        return qrCodemap.get(type);
    }
}
