package com.sh.engine.service.upload;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.manager.LocalCacheManager;
import com.sh.config.utils.OkHttpClientUtil;
import com.sh.engine.base.StreamerInfoHolder;
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
    private LocalCacheManager localCacheManager;

    private static final String ALIST_TOKEN_KEY = "alist_token";
    private static final String ALIST_LOCAL_STORAGE_PATH = "/本地存储";

    @Override
    public boolean checkPathExist(String path) {
        return getDirInfo(path) != null;
    }

    /**
     * 从本地存储拷贝到目标网盘
     *
     * @param rootDirName
     * @param targetFile
     * @return 任务id
     */
    @Override
    public String copyFileToNetDisk(String rootDirName, File targetFile) {
        String recordPath = targetFile.getParentFile().getAbsolutePath();
        String streamerName = StreamerInfoHolder.getCurStreamerName();
        String timeV = targetFile.getParentFile().getName();

        // 创建目标文件夹
        String dstDir = createFolder(rootDirName, recordPath);

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
    public Integer getCopyTaskStatus(String taskId) {
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
            return null;
        }
        Integer state = dataObj.getInteger("state");
        if (state == 1) {
            // 正在上传
            log.info("progress for {} is {}/100", taskId, dataObj.getFloat("progress"));
        } else if (state == 2) {
            // 上传完成
            log.info("copy task finished, taskId: {}", taskId);
        } else if (state == 7) {
            // 上传失败
            log.info("copy task failed, taskId: {}, errorMsg: {}", taskId, dataObj.getString("error"));
        }
        return state;
    }

    @Override
    public boolean retryCopyTask(String taskId) {
        Request request = new Request.Builder()
                .url(getDomainUrl() + "/api/task/copy/retry?tid=" + taskId)
                .post(RequestBody.create(MediaType.parse("application/json"), "{}"))
                .addHeader("Authorization", getToken())
                .addHeader("Content-Type", "application/json")
                .build();

        String resp = OkHttpClientUtil.execute(request);
        JSONObject respObj = JSON.parseObject(resp);
        return respObj.getInteger("code") == 200;
    }

    private String createFolder(String rootDirName, String recordPath) {
        String timeV = new File(recordPath).getName();
        String streamerName = new File(recordPath).getParentFile().getName();
        String tDirPath = "/" + rootDirName + "/" + streamerName + "/" + timeV;
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
