package com.sh.engine.service.upload;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.manager.CacheManager;
import com.sh.config.utils.OkHttpClientUtil;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.constant.UploadPlatformEnum;
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
public class AlistNetDiskCopyService implements NetDiskCopyService {
    @Value("${alist.server.host}")
    private String host;
    @Value("${alist.server.port}")
    private String port;
    @Value("${alist.server.username}")
    private String username;
    @Value("${alist.server.password}")
    private String password;

    @Resource
    private CacheManager cacheManager;

    private static final Map<String, String> UPLOAD_PLATFORM_TO_ALIST_PATH_MAP = Maps.newHashMap();
    private static final String ALIST_TOKEN_KEY = "alist_token";
    private static final String ALIST_LOCAL_STORAGE_PATH = "/本地存储";

    static {
        UPLOAD_PLATFORM_TO_ALIST_PATH_MAP.put(UploadPlatformEnum.BAIDU_PAN.getType(), "/百度网盘");
        UPLOAD_PLATFORM_TO_ALIST_PATH_MAP.put(UploadPlatformEnum.ALI_DRIVER.getType(), "/阿里云盘");
        UPLOAD_PLATFORM_TO_ALIST_PATH_MAP.put(UploadPlatformEnum.QUARK_PAN.getType(), "/夸克云盘");
    }

    @Override
    public boolean checkBasePathExist(UploadPlatformEnum platform) {
        JSONObject info = getDirInfo(UPLOAD_PLATFORM_TO_ALIST_PATH_MAP.get(platform.getType()));
        return info != null;
    }

    /**
     * 从本地存储拷贝到目标网盘
     * @param platform
     * @param targetFile
     * @return 任务id
     */
    @Override
    public String copyFileToNetDisk(UploadPlatformEnum platform, File targetFile) {
        String recordPath = targetFile.getParentFile().getAbsolutePath();
        String streamerName = StreamerInfoHolder.getCurStreamerName();
        String timeV = targetFile.getParentFile().getName();

        // 创建目标文件夹
        String dstDir = createFolder(platform, recordPath);

        // 本地拷贝到目标网盘
        Map<String, Object> params = ImmutableMap.of(
                "src_dir", ALIST_LOCAL_STORAGE_PATH + "/" + streamerName + "/" + timeV,
                "dst_dir", dstDir,
                "names", Lists.newArrayList(targetFile.getName())
        );
        Request request = new Request.Builder()
                .url(getDomainUrl() + "/api/fs/copy")
                .post(RequestBody.create(MediaType.parse("application/json"), JSON.toJSONString(params)))
                .addHeader("Authorization", getToken())
                .addHeader("Content-Type", "application/json")
                .build();

        String resp = OkHttpClientUtil.execute(request);
        log.info("copy to target netDisk resp: {}", resp);

        JSONObject respObj = JSON.parseObject(resp);
        return respObj.getJSONObject("data").getJSONArray("tasks").getJSONObject(0).getString("id");
    }

    @Override
    public boolean checkCopyTaskFinish(String taskId) {
        Request request = new Request.Builder()
                .url(getDomainUrl() + "/api/task/copy/info?tid=" + taskId)
                .post(RequestBody.create(MediaType.parse("application/json"), "{}"))
                .addHeader("Authorization", getToken())
                .addHeader("Content-Type", "application/json")
                .build();

        String resp = OkHttpClientUtil.execute(request);
        JSONObject respObj = JSON.parseObject(resp);
        JSONObject dataObj = respObj.getJSONObject("data");
        if (dataObj == null) {
            log.info("taskId: {} not found", taskId);
            return false;
        } else {
            log.info("progress for {} is {}/100", taskId, dataObj.getFloat("progress"));
            return dataObj.getInteger("state") == 2 && StringUtils.isNotBlank(dataObj.getString("end_time"));
        }
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
        String token = cacheManager.get(ALIST_TOKEN_KEY);
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
        cacheManager.localSet(ALIST_TOKEN_KEY, token, 47, TimeUnit.HOURS);
        return token;
    }

    private String getDomainUrl() {
        return "http://" + host + ":" + port;
    }
}
