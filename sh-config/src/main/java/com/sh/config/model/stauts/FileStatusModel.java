package com.sh.config.model.stauts;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sh.config.model.video.UploadVideoPair;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * @author caiWen
 * @date 2023/1/25 22:51
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Slf4j
public class FileStatusModel {
    private String path;
    private String recorderName;
    private String timeV;
    private String updateTime;

    /**
     * 各平台上传情况
     */
    private List<String> platforms;
    private UploadVideoPair bili;
    private UploadVideoPair aliDriver;

    private Boolean biliPost;
    private Boolean aliDriverPost;

    /**
     * 写到fileStatus.json，没有值不覆盖
     *
     * @param dirName
     */
    public void writeSelfToFile(String dirName) {
        createFileIfNotExisted(dirName);

        // 把自身写到文件
        File file = new File(dirName, "fileStatus.json");
        String statusStr = JSON.toJSONString(this);
        try {
            IOUtils.write(statusStr, new FileOutputStream(file), "utf-8");
            log.info("Create fileStatus.json: {}", statusStr);
        } catch (IOException e) {
            log.error("writeSelfToFile to file fail, savePath: {}", dirName, e);
        }
    }

    public UploadVideoPair fetchVideoPartByPlatform(String name) {
        if (StringUtils.equals(name, "BILI_CLIENT") || StringUtils.equals(name, "BILI_WEB")) {
            return bili;
        } else if (StringUtils.equals(name, "ALI_DRIVER")) {
            return aliDriver;
        }
        return null;
    }

    public boolean fetchPostByPlatform(String name) {
        if (StringUtils.equals(name, "BILI_CLIENT") || StringUtils.equals(name, "BILI_WEB")) {
            return biliPost;
        } else if (StringUtils.equals(name, "ALI_DRIVER")) {
            return aliDriverPost;
        }
        return false;
    }

    public void updateVideoPartByPlatform(String name, UploadVideoPair updated) {
        if (StringUtils.equals(name, "BILI_CLIENT") || StringUtils.equals(name, "BILI_WEB")) {
            this.bili = updated;
        } else if (StringUtils.equals(name, "ALI_DRIVER")) {
            this.aliDriver = updated;
        }
    }

    public void updatePostByPlatform(String name, boolean updated) {
        if (StringUtils.equals(name, "BILI_CLIENT") || StringUtils.equals(name, "BILI_WEB")) {
            this.biliPost = updated;
        } else if (StringUtils.equals(name, "ALI_DRIVER")) {
            this.aliDriverPost = updated;
        }
    }

    public boolean allPost() {
        boolean allPost = true;
        for (String platform : platforms) {
            allPost = allPost && fetchPostByPlatform(platform);
        }
        return allPost;
    }

    /**
     * 只进行覆盖操作
     *
     * @param dirName
     * @param updated
     */
    public static void updateToFile(String dirName, FileStatusModel updated) {
        createFileIfNotExisted(dirName);

        File file = new File(dirName, "fileStatus.json");
        try {
            String oldStatusStr = IOUtils.toString(new FileInputStream(file), "utf-8");
            JSONObject statusObj = JSON.parseObject(oldStatusStr);
            statusObj.putAll(JSONObject.parseObject(JSON.toJSONString(updated)));
            String finalStatus = statusObj.toJSONString();
            IOUtils.write(finalStatus, new FileOutputStream(file), "utf-8");
            log.info("fileStatus.json updated success, content: {}", finalStatus);
        } catch (Exception e) {
            log.error("update file fail, savePath: {}", dirName, e);
        }
    }

    public static FileStatusModel loadFromFile(String dirName) {
        File statusFile = new File(dirName, "fileStatus.json");
        String statusStr = null;
        try {
            statusStr = IOUtils.toString(new FileInputStream(statusFile), "utf-8");
        } catch (IOException e) {
            log.error("open fileStatus.json fail, maybe file not exited, dirName: {}", dirName);
        }
        return JSON.parseObject(statusStr, FileStatusModel.class);
    }

    private static void createFileIfNotExisted(String dirName) {
        File curFile = new File(dirName);
        if (!curFile.exists()) {
            curFile.mkdirs();
        }

        File file = new File(dirName, "fileStatus.json");
        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException e) {
                log.error("create file fail, savePath: {}", dirName, e);
            }
        }
    }
}
