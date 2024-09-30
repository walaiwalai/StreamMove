package com.sh.engine.upload;

import com.google.common.collect.Maps;
import com.sh.engine.UploadPlatformEnum;
import com.sh.engine.model.upload.AliDriverUploader;
import com.sh.engine.model.upload.BiliClientUploader;
import com.sh.engine.model.upload.DouyinUploader;
import com.sh.engine.model.upload.Uploader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * @Author : caiwen
 * @Date: 2024/9/29
 */
public class UploaderFactory {
    /**
     * 上传器
     */
    private static Map<String, Uploader> uploaderMap = Maps.newHashMap();

    /**
     * 元数据文件名称
     */
    private static Map<String, String> uploaderMetaFileName = Maps.newHashMap();

    static  {
        uploaderMap.put(UploadPlatformEnum.DOU_YIN.getType(), new DouyinUploader());
        uploaderMap.put(UploadPlatformEnum.BILI_CLIENT.getType(), new BiliClientUploader());
        uploaderMap.put(UploadPlatformEnum.ALI_DRIVER.getType(), new AliDriverUploader());

        uploaderMetaFileName.put(UploadPlatformEnum.DOU_YIN.getType(), "douyin-metaData.json");
        uploaderMetaFileName.put(UploadPlatformEnum.BILI_CLIENT.getType(), "bili-client-metaData.json");
        uploaderMetaFileName.put(UploadPlatformEnum.ALI_DRIVER.getType(), "ali-driver-metaData.json");
    }

    public static Uploader getUploader(String type) {
        return uploaderMap.get(type);
    }

    public static String getMetaFileName(String type) {
        return uploaderMetaFileName.get(type);
    }
}
