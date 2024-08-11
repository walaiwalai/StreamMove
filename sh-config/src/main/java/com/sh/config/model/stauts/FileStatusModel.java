package com.sh.config.model.stauts;

import cn.hutool.core.lang.Assert;
import com.alibaba.fastjson.JSON;
import com.google.common.collect.Maps;
import com.sh.config.model.video.UploadVideoPair;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.BooleanUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

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

    /**
     * 各平台上传情况
     */
    private List<String> platforms;

    /**
     * 各平台上传情况
     */
    private Map<String, Boolean> postMap = Maps.newHashMap();
    private Map<String, UploadVideoPair> uploadVidoPairMap = Maps.newHashMap();

    /**
     * 写到fileStatus.json
     */
    public void writeSelfToFile() {
        Assert.notBlank(path, "path is blank");
        createFileIfNotExisted();

        // 把自身写到文件
        File file = new File(path, "fileStatus.json");
        String statusStr = JSON.toJSONString(this);
        try {
            IOUtils.write(statusStr, new FileOutputStream(file), "utf-8");
            log.info("Create fileStatus.json: {}", statusStr);
        } catch (IOException e) {
            log.error("writeSelfToFile to file fail, savePath: {}", path, e);
        }
    }

    public UploadVideoPair fetchVideoPartByPlatform(String name) {
        return uploadVidoPairMap.get(name);
    }

    public boolean fetchPostByPlatform(String name) {
        return BooleanUtils.isTrue(postMap.get(name));
    }

    public void updatePostSuccessByPlatform(String name) {
        postMap.put(name, true);
    }

    public void updateVideoPartByPlatform(String name, UploadVideoPair updated) {
        uploadVidoPairMap.put(name, updated);
    }

    public boolean allPost() {
        boolean allPost = true;
        for (String platform : platforms) {
            allPost = allPost && fetchPostByPlatform(platform);
        }
        return allPost;
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

    private void createFileIfNotExisted() {
        File curFile = new File(path);
        if (!curFile.exists()) {
            curFile.mkdirs();
        }

        File file = new File(path, "fileStatus.json");
        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException e) {
                log.error("create file fail, savePath: {}", path, e);
            }
        }
    }
}
