package com.sh.engine.processor.checker;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.OkHttpClientUtil;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.config.utils.FileStoreUtil;
import com.sh.engine.event.StreamRecordEndEvent;
import com.sh.engine.event.StreamRecordStartEvent;
import com.sh.engine.manager.CacheBizManager;
import com.sh.engine.processor.recorder.danmu.DanmakuRecorder;
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
 * @Author caiwen
 * @Date 2025 08 14 00 06
 **/
@Component
@Slf4j
public class StreamrecorderIOChecker extends AbstractRoomChecker {
    private static final String STREAMER_RECORDER_DOMAIN = "streamrecorder.io";
    private static final String COOKIES_FILE_NAME = "streamrecorder-io-cookies.json";
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

        // 从client获取对应的cookie
        CustomCookieJar customCookieJar = (CustomCookieJar) client.cookieJar();
        List<Cookie> cookies = customCookieJar.getCookiesByDomain(STREAMER_RECORDER_DOMAIN);
        if (cookies.isEmpty()) {
            // 尝试从文件加载cookies
            log.info("cookie not found in memory, trying to load from file");
            loadCookiesFromFile();

            // 再次检查cookies是否存在
            cookies = customCookieJar.getCookiesByDomain(STREAMER_RECORDER_DOMAIN);
            if (cookies.isEmpty()) {
                log.info("cookie not found in file, do login");
                doLogin();
            }
        }

        boolean isCertainVod = CollectionUtils.isNotEmpty(streamerConfig.getCertainVodUrls());
        int limit = isCertainVod ? 100 : 1;
        Request request = new Request.Builder()
                .url(String.format("https://streamrecorder.io/api/user/recordingsv2?targetid=%s&offset=0&limit=" + limit, targetId))
                .get()
                .addHeader("Content-Type", "application/json")
                .addHeader("Cookie", customCookieJar.getCookieString(STREAMER_RECORDER_DOMAIN))
                .build();
        String resp;
        try {
            resp = OkHttpClientUtil.execute(request);
        } catch (Exception e) {
            if (e.getMessage().contains("authenticated")) {
                log.error("cookie expired, re-login in next term");
                customCookieJar.clearAllCookies();
                // 删除本地cookies文件
                File cookiesFile = new File(accountSavePath, COOKIES_FILE_NAME);
                if (cookiesFile.exists()) {
                    cookiesFile.delete();
                    log.info("Deleted expired cookies file: {}", cookiesFile.getAbsolutePath());
                }
                return null;
            } else {
                throw e;
            }
        }

        JSONObject respObj = JSON.parseObject(resp);
        if (isCertainVod) {
            return fetchCertainRecords(streamerConfig, respObj);
        } else {
            return fetchLatestRecord(streamerConfig, respObj);
        }
    }

    private void loadCookiesFromFile() {
        try {
            File cookiesFile = new File(accountSavePath, COOKIES_FILE_NAME);
            if (!cookiesFile.exists()) {
                log.info("Cookies file does not exist: {}", cookiesFile.getAbsolutePath());
                return;
            }

            // 读取cookies文件
            List<Cookie> cookies = FileStoreUtil.loadFromFile(cookiesFile, new com.alibaba.fastjson.TypeReference<List<Cookie>>() {});
            if (cookies != null && !cookies.isEmpty()) {
                CustomCookieJar customCookieJar = (CustomCookieJar) client.cookieJar();
                customCookieJar.loadCookiesFromFile(cookies, STREAMER_RECORDER_DOMAIN);
                log.info("Loaded {} cookies from file: {}", cookies.size(), cookiesFile.getAbsolutePath());
            }
        } catch (Exception e) {
            log.error("Failed to load cookies from file", e);
        }
    }

    private void saveCookiesToFile() {
        try {
            CustomCookieJar customCookieJar = (CustomCookieJar) client.cookieJar();
            List<Cookie> cookies = customCookieJar.getCookiesByDomain(STREAMER_RECORDER_DOMAIN);

            if (cookies != null && !cookies.isEmpty()) {
                File cookiesFile = new File(accountSavePath, COOKIES_FILE_NAME);
                FileStoreUtil.saveToFile(cookiesFile, cookies);
                log.info("Saved {} cookies to file: {}", cookies.size(), cookiesFile.getAbsolutePath());
            }
        } catch (Exception e) {
            log.error("Failed to save cookies to file", e);
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
                downloadLink = dataObj.getJSONArray("sources").getJSONObject(0).getString("downloadlink");
                break;
            }
        }
        Map<String, String> extra = new HashMap<>();
        extra.put("finishField", videoId);

        return new StreamUrlStreamRecorder(recordedAt, streamerConfig.getRoomUrl(), getType().getType(), downloadLink, extra);
    }

    private StreamRecorder fetchLatestRecord(StreamerConfig streamerConfig, JSONObject respObj) {
        if (CollectionUtils.isEmpty(respObj.getJSONArray("data"))) {
            return null;
        }
        String name = streamerConfig.getName();
        JSONObject latestRecord = respObj.getJSONArray("data").getJSONObject(0);
        String status = latestRecord.getString("status");
        Date recordedAt = parseGMT8Date(latestRecord.getString("recorded_at"));
        log.info("streamer io check, status: {}, lastRecordAt: {}", status, recordedAt);

        if (StringUtils.equals(status, "running")) {
            // 如果状态为running，说明正在录制，触发事件发布
            StreamRecordStartEvent event = new StreamRecordStartEvent(this, name, recordedAt);
            eventPublisher.publishEvent(event);
            return null;
        } else if (StringUtils.equals(status, "finished")) {
            if (!checkVodIsNew(streamerConfig, recordedAt)) {
                return null;
            }
            // 如果状态为running，说明录制结束
            StreamRecordEndEvent event = new StreamRecordEndEvent(this, name);
            eventPublisher.publishEvent(event);
            String downloadLink = latestRecord.getJSONArray("sources").getJSONObject(0).getString("downloadlink");
            return new StreamUrlStreamRecorder(recordedAt, streamerConfig.getRoomUrl(), getType().getType(), downloadLink);
        } else {
            return null;
        }
    }

    @Override
    public StreamChannelTypeEnum getType() {
        return StreamChannelTypeEnum.STREAM_RECORDER_IO;
    }


    /**
     * 解析GMT+8时间
     * 直接加上8小时
     * @param dateStr
     * @return
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

    private void doLogin() {
        try {
            // 步骤1：GET访问登录页面，获取动态Cookie
            getLoginPage();
            // 步骤2：POST提交登录请求（修正target参数）
            postLoginRequest();
        } catch (IOException e) {
            log.error("log failed", e);
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
                throw new IOException("获取登录页面失败: " + response);
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

        // 构建登录请求（完整请求头）
        Request loginRequest = new Request.Builder()
                .url("https://streamrecorder.io/login")
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                .addHeader("Accept-Language", "zh-CN,zh;q=0.9")
                .addHeader("Cache-Control", "max-age=0")
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .addHeader("Origin", "https://streamrecorder.io")
                .addHeader("Priority", "u=0, i") // 补充priority头
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

        // 执行登录请求
        try (Response response = client.newCall(loginRequest).execute()) {
            if (response.code() == 302) {
                String redirectUrl = response.header("Location");
                if (redirectUrl != null && redirectUrl.contains("/userdashboard")) {
                    saveCookiesToFile();
                }
                return;
            }

            // 若未重定向，检查响应内容是否为用户面板
            String responseBody = response.body().string();
            if (responseBody.contains("userdashboard") && !responseBody.contains("Sign in to Streamrecorder")) {
                saveCookiesToFile();
            }
        }
    }

    static class CustomCookieJar implements CookieJar {
        private final Map<String, List<Cookie>> cookieStore = new HashMap<>();

        @Override
        public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
            String host = url.host();
            List<Cookie> existed = getCookiesByDomain(host);
            existed.addAll(cookies);
            System.out.println("保存URL: " + host);
            System.out.println("保存Cookie: " + cookies);
            cookieStore.put(host, existed);
        }

        @Override
        public List<Cookie> loadForRequest(HttpUrl url) {
            // 加载指定域名的Cookie
            return cookieStore.getOrDefault(url.host(), new ArrayList<>());
        }

        // 新增：获取指定域名的所有Cookie
        public List<Cookie> getCookiesByDomain(String domain) {
            return cookieStore.getOrDefault(domain, new ArrayList<>());
        }

        // 新增：从文件加载cookies
        public void loadCookiesFromFile(List<Cookie> cookies, String domain) {
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
