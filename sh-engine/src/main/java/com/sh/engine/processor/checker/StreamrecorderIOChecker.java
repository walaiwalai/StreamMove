package com.sh.engine.processor.checker;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.OkHttpClientUtil;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.config.utils.FileStoreUtil;
import com.sh.engine.event.StreamRecordEndEvent;
import com.sh.engine.event.StreamRecordStartEvent;
import com.sh.engine.manager.CacheBizManager;
import com.sh.engine.processor.recorder.stream.StreamRecorder;
import com.sh.engine.processor.recorder.stream.StreamUrlStreamRecorder;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Streamrecorder.io 平台检查器
 * @Author caiwen
 * @Date 2025 08 14 00 06
 **/
@Component
@Slf4j
public class StreamrecorderIOChecker extends AbstractRoomChecker {
    private static final String STREAMER_RECORDER_DOMAIN = "streamrecorder.io";
    private static final String COOKIES_FILE_NAME = "streamrecorder-io-cookies.txt";

    /**
     * 长视频阈值：14小时（秒），超过此时长使用720p下载
     */
    private static final int LONG_VIDEO_THRESHOLD_SECONDS = 20 * 60 * 60;

    @Value("${streamerrecord.io.name}")
    private String name;
    @Value("${streamerrecord.io.password}")
    private String password;
    @Value("${sh.account-save.path}")
    private String accountSavePath;

    @Resource
    private CacheBizManager cacheBizManager;
    @Autowired
    private ApplicationEventPublisher eventPublisher;

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .cookieJar(new CustomCookieJar())
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    @Override
    public StreamRecorder getStreamRecorder(StreamerConfig streamerConfig) {
        String roomUrl = streamerConfig.getRoomUrl();
        String[] split = roomUrl.split("/");
        String targetId = split[split.length - 1];

        // 确保 cookies 有效（加载或登录）
        ensureCookiesValid();

        // 发送请求获取录制信息
        String resp = fetchRecordingsWithRetry(targetId, streamerConfig);

        JSONObject respObj = JSON.parseObject(resp);
        boolean isCertainVod = CollectionUtils.isNotEmpty(streamerConfig.getCertainVodUrls());
        if (isCertainVod) {
            return fetchCertainRecords(streamerConfig, respObj);
        } else {
            return fetchLatestRecord(streamerConfig, respObj);
        }
    }

    /**
     * 确保 cookies 有效：优先从文件加载，否则登录
     */
    private void ensureCookiesValid() {
        CustomCookieJar cookieJar = (CustomCookieJar) client.cookieJar();

        // 1. 尝试从内存获取
        if (!cookieJar.getCookiesByDomain(STREAMER_RECORDER_DOMAIN).isEmpty()) {
            return;
        }

        // 2. 尝试从文件加载
        if (loadCookiesFromFile()) {
            return;
        }

        // 3. 执行登录
        log.info("No valid cookies found, performing login...");
        doLogin();
    }

    /**
     * 从文件加载 cookies
     * @return 是否成功加载
     */
    private boolean loadCookiesFromFile() {
        try {
            File cookiesFile = new File(accountSavePath, COOKIES_FILE_NAME);
            if (!cookiesFile.exists()) {
                return false;
            }

            String cookieString = FileStoreUtil.loadStringFromFile(cookiesFile);
            if (StringUtils.isBlank(cookieString)) {
                return false;
            }

            CustomCookieJar cookieJar = (CustomCookieJar) client.cookieJar();
            cookieJar.loadCookiesFromString(cookieString, STREAMER_RECORDER_DOMAIN);
            log.info("Loaded cookies from file");
            return true;
        } catch (Exception e) {
            log.error("Failed to load cookies from file", e);
            return false;
        }
    }

    /**
     * 保存 cookies 到文件
     */
    private void saveCookiesToFile() {
        try {
            CustomCookieJar cookieJar = (CustomCookieJar) client.cookieJar();
            String cookieString = cookieJar.getCookieString(STREAMER_RECORDER_DOMAIN);

            if (StringUtils.isNotBlank(cookieString)) {
                File cookiesFile = new File(accountSavePath, COOKIES_FILE_NAME);
                FileStoreUtil.saveStringToFile(cookiesFile, cookieString);
                log.info("Saved cookies to file");
            }
        } catch (Exception e) {
            log.error("Failed to save cookies to file", e);
        }
    }

    /**
     * 清除所有 cookies（内存+文件）
     */
    private void clearAllCookies() {
        CustomCookieJar cookieJar = (CustomCookieJar) client.cookieJar();
        cookieJar.clearAllCookies();

        File cookiesFile = new File(accountSavePath, COOKIES_FILE_NAME);
        if (cookiesFile.exists()) {
            cookiesFile.delete();
            log.info("Deleted cookies file");
        }
    }

    /**
     * 获取录制列表，支持自动重试（cookie 失效时重新登录）
     */
    private String fetchRecordingsWithRetry(String targetId, StreamerConfig streamerConfig) {
        int limit = CollectionUtils.isNotEmpty(streamerConfig.getCertainVodUrls()) ? 100 : 2;
        String url = String.format("https://streamrecorder.io/api/user/recordingsv2?targetid=%s&offset=0&limit=%d", targetId, limit);

        Request request = new Request.Builder()
                .url(url)
                .get()
                .addHeader("Content-Type", "application/json")
                .addHeader("Cookie", ((CustomCookieJar) client.cookieJar()).getCookieString(STREAMER_RECORDER_DOMAIN))
                .build();

        try {
            return OkHttpClientUtil.execute(request);
        } catch (Exception e) {
            log.error("Cookie expired, clearing and retrying...", e);
            clearAllCookies();

            // 重新登录并重试
            doLogin();

            // 重试请求
            Request retryRequest = new Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Cookie", ((CustomCookieJar) client.cookieJar()).getCookieString(STREAMER_RECORDER_DOMAIN))
                    .build();
            return OkHttpClientUtil.execute(retryRequest);
        }
    }

    private StreamRecorder fetchCertainRecords(StreamerConfig streamerConfig, JSONObject respObj) {
        String videoId = null;
        for (String vid : streamerConfig.getCertainVodUrls()) {
            boolean isFinished = cacheBizManager.isCertainVideoFinished(streamerConfig.getName(), vid);
            if (!isFinished) {
                videoId = vid;
                break;
            }
        }
        if (videoId == null) {
            return null;
        }

        String downloadLink = null;
        Date recordedAt = null;
        for (Object data : respObj.getJSONArray("data")) {
            JSONObject dataObj = (JSONObject) data;
            String id = String.valueOf(dataObj.getLong("id"));
            if (StringUtils.equals(id, videoId)) {
                recordedAt = parseGMT8Date(dataObj.getString("recorded_at"));
                int duration = dataObj.getIntValue("duration");
                if (duration > LONG_VIDEO_THRESHOLD_SECONDS) {
                    downloadLink = getSourceLink(dataObj, 720);
                    log.info("Certain vod long video detected ({}s > {}s), using 720p: {}", duration, LONG_VIDEO_THRESHOLD_SECONDS, videoId);
                } else {
                    downloadLink = getSourceLink(dataObj, 1080);
                    if (downloadLink == null) {
                        downloadLink = dataObj.getJSONArray("sources").getJSONObject(0).getString("downloadlink");
                    }
                }
                break;
            }
        }
        Map<String, String> extra = new HashMap<>();
        extra.put("finishField", videoId);

        return new StreamUrlStreamRecorder(recordedAt, streamerConfig.getRoomUrl(), getType().getType(), downloadLink, extra);
    }

    /**
     * 等待 1080p 的超时时间（30分钟）
     */
    private static final long WAIT_FOR_1080_TIMEOUT_MS = 30 * 60 * 1000;

    private StreamRecorder fetchLatestRecord(StreamerConfig streamerConfig, JSONObject respObj) {
        JSONArray dataArr = respObj.getJSONArray("data");
        if (CollectionUtils.isEmpty(dataArr)) {
            return null;
        }
        String name = streamerConfig.getName();
        JSONObject latestRecord = dataArr.getJSONObject(0);
        String status = latestRecord.getString("status");
        String streamTitle = latestRecord.getString("streamtitle");
        Date recordedAt = parseGMT8Date(latestRecord.getString("recorded_at"));
        log.info("streamer io check, status: {}, lastRecordAt: {}", status, recordedAt);

        if (StringUtils.equals(status, "running")) {
            StreamRecordStartEvent event = new StreamRecordStartEvent(this, name, recordedAt);
            eventPublisher.publishEvent(event);
            return null;
        } else if (StringUtils.equals(status, "finished")) {
            if (!checkVodIsNew(streamerConfig, recordedAt)) {
                return null;
            }
            // 防止同一场直播被平台切分为多条记录而重复录制：若最新记录的 recorded_at 落在前一条记录
            // [recorded_at, recorded_at + duration + buffer] 区间内，则视为同一场直播
            if (isDuplicateOfPreviousRecord(dataArr)) {
                log.info("Duplicate live session detected, skip recording. streamer: {}, recordedAt: {}", name, recordedAt);
                return null;
            }
            StreamRecordEndEvent event = new StreamRecordEndEvent(this, name);
            eventPublisher.publishEvent(event);

            // 解析 sources，获取最佳下载链接（优先 1080p）
            Map<String, String> extra = new HashMap<>();
            extra.put("streamTitle", streamTitle);

            String downloadLink = resolveDownloadLink(streamerConfig, latestRecord, recordedAt);
            return downloadLink == null ? null : new StreamUrlStreamRecorder(recordedAt, streamerConfig.getRoomUrl(), getType().getType(), downloadLink, extra);
        }
        return null;
    }

    /**
     * 同一场直播判定的 buffer 时间（秒）：考虑到平台分段、断流重连等情况，留 5 分钟容差
     */
    private static final int DUPLICATE_BUFFER_SECONDS = 5 * 60;

    /**
     * 判断最新一条记录是否和上一条为同一场直播（仅对比最近两段）
     * 同场判定：最新片段开始时间 小于等于 上一条结束时间+5分钟缓冲
     */
    private boolean isDuplicateOfPreviousRecord(JSONArray dataArr) {
        // 不足两条直接不用对比
        if (dataArr.size() < 2) {
            return false;
        }
        // 最新片段 data[0]
        JSONObject latestRecord = dataArr.getJSONObject(0);
        Date latestStart = parseGMT8Date(latestRecord.getString("recorded_at"));
        if (latestStart == null) {
            return false;
        }
        long latestStartMs = latestStart.getTime();

        // 上一条片段 data[1]
        JSONObject prevRecord = dataArr.getJSONObject(1);
        Date prevStart = parseGMT8Date(prevRecord.getString("recorded_at"));
        int prevDuration = prevRecord.getIntValue("duration");
        if (prevStart == null || prevDuration <= 0) {
            return false;
        }

        // 上一条结束时间 + 缓冲
        long prevEndWithBufferMs = prevStart.getTime() + (prevDuration + DUPLICATE_BUFFER_SECONDS) * 1000L;
        // 核心判断：最新片段开播时间落在上一条结束缓冲区间内 = 同一场分段
        return latestStartMs <= prevEndWithBufferMs;
    }



    /**
     * 解析下载链接，实现 1080p 等待策略
     * 优先级：长视频直接720p > 1080p > 等待30分钟 > 720p
     */
    private String resolveDownloadLink(StreamerConfig streamerConfig, JSONObject latestRecord, Date recordedAt) {
        long detectFinishedTime = System.currentTimeMillis();

        int duration = latestRecord.getIntValue("duration");
        boolean isLongVideo = duration > LONG_VIDEO_THRESHOLD_SECONDS;

        String link1080 = getSourceLink(latestRecord, 1080);
        String link720 = getSourceLink(latestRecord, 720);

        // 长视频直接使用720p，跳过1080p等待
        if (isLongVideo) {
            cacheBizManager.clearWaitingFor1080(streamerConfig.getName(), String.valueOf(recordedAt.getTime()));
            log.info("Long video detected ({}s > {}s), using 720p: {}", duration, LONG_VIDEO_THRESHOLD_SECONDS, streamerConfig.getName());
            return link720;
        }
        String videoId = String.valueOf(recordedAt.getTime());
        String streamerName = streamerConfig.getName();

        // 1. 有 1080p，直接下载
        if (StringUtils.isNotBlank(link1080)) {
            cacheBizManager.clearWaitingFor1080(streamerName, videoId);
            log.info("Found 1080p source, downloading: {}", streamerName);
            return link1080;
        }

        // 2. 检查是否在等待 1080p 中
        Long waitStartTime = cacheBizManager.getWaitingFor1080StartTime(streamerName, videoId);

        if (waitStartTime == null) {
            // 首次发现无 1080p，开始等待
            cacheBizManager.setWaitingFor1080(streamerName, videoId, detectFinishedTime);
            log.info("No 1080p source yet, starting 30min wait: {}", streamerName);
            return null;
        }

        // 3. 等待中，检查是否超时
        long elapsed = System.currentTimeMillis() - waitStartTime;
        if (elapsed < WAIT_FOR_1080_TIMEOUT_MS) {
            long remaining = (WAIT_FOR_1080_TIMEOUT_MS - elapsed) / 1000 / 60;
            log.info("Still waiting for 1080p, {} min remaining: {}", remaining, streamerName);
            return null;
        }

        // 4. 等待超时，使用 720p
        cacheBizManager.clearWaitingFor1080(streamerName, videoId);
        log.info("Wait timeout, using 720p source: {}", streamerName);
        return link720;
    }

    /**
     * 从 sources 中获取指定分辨率的下载链接
     */
    private String getSourceLink(JSONObject latestRecord, int resolution) {
        JSONArray sources = latestRecord.getJSONArray("sources");
        if (CollectionUtils.isEmpty(sources)) {
            return null;
        }
        for (int i = 0; i < sources.size(); i++) {
            JSONObject source = sources.getJSONObject(i);
            if (source.getIntValue("resolution") == resolution) {
                return source.getString("downloadlink");
            }
        }
        return null;
    }

    @Override
    public StreamChannelTypeEnum getType() {
        return StreamChannelTypeEnum.STREAM_RECORDER_IO;
    }

    /**
     * 解析 GMT+8 时间
     */
    private Date parseGMT8Date(String dateStr) {
        Date recordedAt = null;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        try {
            recordedAt = DateUtils.addHours(sdf.parse(dateStr), 8);
        } catch (Exception e) {
            log.error("parse date failed, dateStr: {}", dateStr, e);
        }
        return recordedAt;
    }

    /**
     * 执行登录流程
     */
    private void doLogin() {
        try {
            getLoginPage();
            postLoginRequest();
        } catch (IOException e) {
            log.error("Login failed", e);
            throw new RuntimeException("Failed to login to streamrecorder.io", e);
        }
    }

    private void getLoginPage() throws IOException {
        Request getRequest = new Request.Builder()
                .url("https://streamrecorder.io/login")
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36")
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .addHeader("Referer", "https://streamrecorder.io/login")
                .addHeader("Priority", "u=0, i")
                .build();

        try (Response response = client.newCall(getRequest).execute()) {
            if (!response.isSuccessful() && response.code() != 304) {
                throw new IOException("Failed to get login page: " + response);
            }
        }
    }

    private void postLoginRequest() throws IOException {
        RequestBody formBody = new FormBody.Builder()
                .add("username", name)
                .add("password", password)
                .add("remember", "on")
                .add("target", "/login")
                .build();

        Request loginRequest = new Request.Builder()
                .url("https://streamrecorder.io/login")
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                .addHeader("Accept-Language", "zh-CN,zh;q=0.9")
                .addHeader("Cache-Control", "max-age=0")
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .addHeader("Origin", "https://streamrecorder.io")
                .addHeader("Priority", "u=0, i")
                .addHeader("Referer", "https://streamrecorder.io/login")
                .addHeader("Sec-Ch-Ua", "\"Not)A;Brand\";v=\"8\", \"Chromium\";v=\"138\", \"Google Chrome\";v=\"138\"")
                .addHeader("Sec-Ch-Ua-Mobile", "?0")
                .addHeader("Sec-Ch-Ua-Platform", "\"Windows\"")
                .addHeader("Sec-Fetch-Dest", "document")
                .addHeader("Sec-Fetch-Mode", "navigate")
                .addHeader("Sec-Fetch-Site", "same-origin")
                .addHeader("Sec-Fetch-User", "?1")
                .addHeader("Upgrade-Insecure-Requests", "1")
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36")
                .post(formBody)
                .build();

        try (Response response = client.newCall(loginRequest).execute()) {
            if (response.code() == 302) {
                String redirectUrl = response.header("Location");
                if (redirectUrl != null && redirectUrl.contains("/userdashboard")) {
                    saveCookiesToFile();
                    return;
                }
            }

            String responseBody = response.body().string();
            if (responseBody.contains("userdashboard") && !responseBody.contains("Sign in to Streamrecorder")) {
                saveCookiesToFile();
            } else {
                throw new IOException("Login failed: unexpected response");
            }
        }
    }

    /**
     * 自定义 CookieJar，管理内存中的 cookies
     */
    static class CustomCookieJar implements CookieJar {
        private final Map<String, List<Cookie>> cookieStore = new HashMap<>();

        @Override
        public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
            String host = url.host();
            List<Cookie> existed = getCookiesByDomain(host);
            existed.addAll(cookies);
            cookieStore.put(host, existed);
        }

        @Override
        public List<Cookie> loadForRequest(HttpUrl url) {
            return cookieStore.getOrDefault(url.host(), new ArrayList<>());
        }

        public List<Cookie> getCookiesByDomain(String domain) {
            return cookieStore.getOrDefault(domain, new ArrayList<>());
        }

        public void loadCookiesFromString(String cookieString, String domain) {
            List<Cookie> cookies = new ArrayList<>();
            String[] pairs = cookieString.split("; ");
            for (String pair : pairs) {
                int idx = pair.indexOf('=');
                if (idx > 0) {
                    String name = pair.substring(0, idx);
                    String value = pair.substring(idx + 1);
                    Calendar cal = Calendar.getInstance();
                    cal.add(Calendar.YEAR, 1);
                    Cookie cookie = new Cookie.Builder()
                            .name(name)
                            .value(value)
                            .domain(domain)
                            .path("/")
                            .expiresAt(cal.getTimeInMillis())
                            .build();
                    cookies.add(cookie);
                }
            }
            cookieStore.put(domain, cookies);
        }

        public void clearAllCookies() {
            cookieStore.clear();
        }

        public String getCookieString(String domain) {
            return getCookiesByDomain(domain).stream()
                    .map(cookie -> cookie.name() + "=" + cookie.value())
                    .collect(Collectors.joining("; "));
        }
    }
}
