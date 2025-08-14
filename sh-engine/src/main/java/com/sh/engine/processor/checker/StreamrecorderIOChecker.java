package com.sh.engine.processor.checker;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.OkHttpClientUtil;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.processor.recorder.Recorder;
import com.sh.engine.processor.recorder.StreamUrlRecorder;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
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
    @Value("${streamerrecord.io.name}")
    private String name;
    @Value("${streamerrecord.io.password}")
    private String password;

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .cookieJar(new CustomCookieJar())
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    @Override
    public Recorder getStreamRecorder(StreamerConfig streamerConfig) {
        String roomUrl = streamerConfig.getRoomUrl();
        String[] split = roomUrl.split("/");
        String targetId = split[split.length - 1];

        // 从client获取对应的cookie
        CustomCookieJar customCookieJar = (CustomCookieJar) client.cookieJar();
        List<Cookie> cookies = customCookieJar.getCookiesByDomain("streamrecorder.io");
        if (cookies.isEmpty()) {
            doLogin();
        }

        Request request = new Request.Builder()
                .url(String.format("https://streamrecorder.io/api/user/recordingsv2?targetid=%s&offset=0&limit=20", targetId))
                .get()
                .addHeader("Content-Type", "application/json")
                .addHeader("Cookie", customCookieJar.getCookieString("streamrecorder.io"))
                .build();

        String resp;
        try {
            resp = OkHttpClientUtil.execute(request);
        } catch (Exception e) {
            if (e.getMessage().contains("authenticated")) {
                log.error("cookie expired, re-login in next term");
                customCookieJar.clearAllCookies();
                return null;
            } else {
                throw e;
            }
        }


        JSONObject respObj = JSON.parseObject(resp);
        JSONObject latestRecord = respObj.getJSONArray("data").getJSONObject(0);
        Date recordedAt = latestRecord.getDate("recorded_at");
        if (!checkVodIsNew(streamerConfig, recordedAt)) {
            return null;
        }
        String downloadLink = latestRecord.getJSONArray("sources").getJSONObject(0).getString("downloadlink");
        return new StreamUrlRecorder(recordedAt, getType().getType(), downloadLink);
    }

    @Override
    public StreamChannelTypeEnum getType() {
        return StreamChannelTypeEnum.STREAM_RECORDER_IO;
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
                    System.out.println("登录成功！重定向到用户面板: " + redirectUrl);
                } else {
                    System.out.println("登录失败，重定向地址异常: " + redirectUrl);
                }
                return;
            }

            // 若未重定向，检查响应内容是否为用户面板
            String responseBody = response.body().string();
            if (responseBody.contains("userdashboard") && !responseBody.contains("Sign in to Streamrecorder")) {
                System.out.println("登录成功，直接返回用户面板");
            } else {
                System.out.println("登录失败，服务器仍返回登录页面");
                System.out.println("响应片段: " + responseBody.substring(0, Math.min(200, responseBody.length())));
            }
        }
    }

    public static void main(String[] args) throws IOException {
        StreamrecorderIOChecker streamrecorderIOChecker = new StreamrecorderIOChecker();
        Recorder streamRecorder = streamrecorderIOChecker.getStreamRecorder(StreamerConfig.builder()
                .roomUrl("streamercord.io/2081784")
                .build());
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
