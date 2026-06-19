package com.sh.engine.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.manager.LocalCacheManager;
import com.sh.config.utils.OkHttpClientUtil;
import com.sh.engine.constant.UploadPlatformEnum;
import com.sh.engine.model.ffmpeg.RcloneMoveCmd;
import com.sh.engine.service.NetDiskCopyService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 通过alist来实现本地存储 -> 各种网盘
 *
 * @Author : caiwen
 * @Date: 2025/1/29
 */
@Slf4j
@Component
public class AlistNetDiskCopyServiceImpl implements NetDiskCopyService {
    @Value("${alist.server.host}")
    private String host;
    @Value("${alist.server.port}")
    private String port;
    @Value("${alist.server.username}")
    private String username;
    @Value("${alist.server.password}")
    private String password;
    @Value("${sh.video-save.path}")
    private String videoSavePath;


    @Resource
    private LocalCacheManager localCacheManager;

    private static final Map<String, String> UPLOAD_PLATFORM_TO_ALIST_PATH_MAP = Maps.newHashMap();
    private static final String ALIST_TOKEN_KEY = "alist_token";
    private static final String ALIST_LOCAL_STORAGE_PATH = "/本地存储";

    static {
        UPLOAD_PLATFORM_TO_ALIST_PATH_MAP.put(UploadPlatformEnum.BAIDU_PAN.getType(), "/百度网盘");
        UPLOAD_PLATFORM_TO_ALIST_PATH_MAP.put(UploadPlatformEnum.ALI_PAN.getType(), "/阿里云盘");
        UPLOAD_PLATFORM_TO_ALIST_PATH_MAP.put(UploadPlatformEnum.QUARK_PAN.getType(), "/夸克云盘");
        UPLOAD_PLATFORM_TO_ALIST_PATH_MAP.put(UploadPlatformEnum.UC_PAN.getType(), "/UC网盘");
        UPLOAD_PLATFORM_TO_ALIST_PATH_MAP.put(UploadPlatformEnum.TIAN_YI_PAN.getType(), "/天翼云盘");
        UPLOAD_PLATFORM_TO_ALIST_PATH_MAP.put(UploadPlatformEnum.ALY_OSS.getType(), "/阿里云OSS");
    }

    @Override
    public boolean checkBasePathExist(UploadPlatformEnum platform) {
        JSONObject info = getDirInfo(UPLOAD_PLATFORM_TO_ALIST_PATH_MAP.get(platform.getType()));
        return info != null;
    }

    /**
     * 从本地存储拷贝到目标网盘
     *
     * @param platform
     * @param targetFile
     * @return 任务id
     */
    @Override
    public void copyFileToNetDisk(UploadPlatformEnum platform, File targetFile) {
        String recordPath = targetFile.getParentFile().getAbsolutePath();

        String fromFilePath = targetFile.getAbsolutePath();
        String toFilePath = createFolder(platform, recordPath);

        RcloneMoveCmd rcloneMoveCmd = new RcloneMoveCmd(fromFilePath, toFilePath);
        rcloneMoveCmd.execute(14400);
    }

    @Override
    public void renameFileSuffix(UploadPlatformEnum platform, File targetFile, String newSuffix) {
        if (StringUtils.isBlank(newSuffix)) {
            return;
        }

        String originalName = targetFile.getName();
        String newName = buildRenamedName(originalName, newSuffix);
        if (StringUtils.equals(originalName, newName)) {
            return;
        }

        // 推算网盘上文件的完整路径：/{平台目录}/{streamerName}/{timeV}/{originalName}
        String recordPath = targetFile.getParentFile().getAbsolutePath();
        String timeV = new File(recordPath).getName();
        String streamerName = new File(recordPath).getParentFile().getName();
        String remoteFilePath = UPLOAD_PLATFORM_TO_ALIST_PATH_MAP.get(platform.getType())
                + "/" + streamerName + "/" + timeV + "/" + originalName;

        Map<String, String> params = ImmutableMap.of(
                "path", remoteFilePath,
                "name", newName
        );
        Request request = new Request.Builder()
                .url(getDomainUrl() + "/api/fs/rename")
                .post(RequestBody.create(MediaType.parse("application/json"), JSON.toJSONString(params)))
                .addHeader("Authorization", getToken())
                .addHeader("Content-Type", "application/json")
                .build();

        String resp = OkHttpClientUtil.execute(request);
        JSONObject respObj = JSON.parseObject(resp);
        if (respObj == null || !Objects.equals(respObj.getString("message"), "success")) {
            log.error("alist rename file error, path: {}, name: {}, resp: {}", remoteFilePath, newName, resp);
            throw new StreamerRecordException(ErrorEnum.INVALID_PARAM);
        }
        log.info("alist rename file success, path: {} -> name: {}", remoteFilePath, newName);
    }

    /**
     * 用新的后缀替换原文件名后缀；原文件名无后缀时直接追加
     */
    private String buildRenamedName(String originalName, String newSuffix) {
        int dotIdx = originalName.lastIndexOf('.');
        String baseName = dotIdx > 0 ? originalName.substring(0, dotIdx) : originalName;
        return baseName + "." + newSuffix;
    }

    private String createFolder(UploadPlatformEnum platform, String recordPath) {
        if (!UPLOAD_PLATFORM_TO_ALIST_PATH_MAP.containsKey(platform.getType())) {
            throw new StreamerRecordException(ErrorEnum.INVALID_PARAM);
        }

        String timeV = new File(recordPath).getName();
        String streamerName = new File(recordPath).getParentFile().getName();
        String tDirPath = UPLOAD_PLATFORM_TO_ALIST_PATH_MAP.get(platform.getType()) + "/" + streamerName + "/" + timeV;
        if (getDirInfo(tDirPath) != null) {
            return tDirPath;
        }

        // 创建文件夹
        Map<String, String> params = ImmutableMap.of(
                "path", tDirPath
        );
        Request request = new Request.Builder()
                .url(getDomainUrl() + "/api/fs/mkdir")
                .post(RequestBody.create(MediaType.parse("application/json"), JSON.toJSONString(params)))
                .addHeader("Authorization", getToken())
                .addHeader("Content-Type", "application/json")
                .build();

        String resp = OkHttpClientUtil.execute(request);
        if (!Objects.equals(JSON.parseObject(resp).getString("message"), "success")) {
            log.error("creat foler error, msg: {}", resp);
            throw new StreamerRecordException(ErrorEnum.INVALID_PARAM);
        }
        return tDirPath;
    }


    private JSONObject getDirInfo(String dirPath) {
        Map<String, String> params = ImmutableMap.of(
                "path", dirPath,
                "password", ""
        );
        Request request = new Request.Builder()
                .url(getDomainUrl() + "/api/fs/get")
                .post(RequestBody.create(MediaType.parse("application/json"), JSON.toJSONString(params)))
                .addHeader("Authorization", getToken())
                .addHeader("Content-Type", "application/json")
                .build();

        String resp = OkHttpClientUtil.execute(request);
        return JSON.parseObject(resp).getJSONObject("data");
    }


    private String getToken() {
        String token = localCacheManager.get(ALIST_TOKEN_KEY);
        if (StringUtils.isNotBlank(token)) {
            return token;
        }

        Map<String, String> params = ImmutableMap.of(
                "username", username,
                "password", password
        );

        Request request = new Request.Builder()
                .url(getDomainUrl() + "/api/auth/login")
                .post(RequestBody.create(MediaType.parse("application/json"), JSON.toJSONString(params)))
                .addHeader("Content-Type", "application/json")
                .build();
        String resp = OkHttpClientUtil.execute(request);
        token = JSON.parseObject(resp).getJSONObject("data").getString("token");

        // 48小时有效
        localCacheManager.set(ALIST_TOKEN_KEY, token, 47, TimeUnit.HOURS);
        return token;
    }

    private String getDomainUrl() {
        return "http://" + host + ":" + port;
    }
}
