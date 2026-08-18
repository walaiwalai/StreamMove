package com.sh.engine.processor.checker;

import com.sh.config.utils.FileStoreUtil;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 统一管理 Streamrecorder.io 的登录、Cookie 持久化和失效刷新。
 */
@Component
@Slf4j
public class StreamrecorderIOLoginManager {

    private static final String DOMAIN = "streamrecorder.io";
    private static final String COOKIES_FILE_NAME = "streamrecorder-io-cookies.txt";

    private final Object loginMonitor = new Object();
    private final CustomCookieJar cookieJar = new CustomCookieJar();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    private long cookieGeneration;

    @Value("${streamerrecord.io.name}")
    private String name;
    @Value("${streamerrecord.io.password}")
    private String password;
    @Value("${sh.account-save.path}")
    private String accountSavePath;

    /**
     * 使用当前 Cookie 执行请求。Cookie 失效时只由一个线程重新登录，其他线程等待后重试。
     */
    public <T> T executeWithCookies(Function<String, T> request) {
        AuthenticationSnapshot authentication = ensureCookiesValid();
        try {
            return request.apply(authentication.cookieString);
        } catch (RuntimeException e) {
            log.error("Cookie expired, refreshing and retrying...", e);
            AuthenticationSnapshot refreshed = refreshCookiesAfterFailure(authentication.cookieGeneration);
            return request.apply(refreshed.cookieString);
        }
    }

    private AuthenticationSnapshot ensureCookiesValid() {
        synchronized (loginMonitor) {
            if (!cookieJar.getCookiesByDomain(DOMAIN).isEmpty()) {
                return captureAuthenticationSnapshot();
            }

            if (loadCookiesFromFile()) {
                cookieGeneration++;
                return captureAuthenticationSnapshot();
            }

            log.info("No valid cookies found, performing login...");
            loginAndUpdateGeneration(false);
            return captureAuthenticationSnapshot();
        }
    }

    private AuthenticationSnapshot refreshCookiesAfterFailure(long failedCookieGeneration) {
        synchronized (loginMonitor) {
            if (cookieGeneration != failedCookieGeneration) {
                log.info("Cookies already refreshed by another thread, reusing login result");
                return captureAuthenticationSnapshot();
            }

            log.info("Refreshing expired cookies...");
            loginAndUpdateGeneration(true);
            return captureAuthenticationSnapshot();
        }
    }

    /**
     * 必须在 loginMonitor 内调用。
     */
    private void loginAndUpdateGeneration(boolean clearBeforeLogin) {
        if (clearBeforeLogin) {
            clearAllCookies();
        }

        try {
            doLogin();
        } catch (RuntimeException e) {
            clearAllCookies();
            throw e;
        } finally {
            cookieGeneration++;
        }
    }

    private AuthenticationSnapshot captureAuthenticationSnapshot() {
        return new AuthenticationSnapshot(cookieJar.getCookieString(DOMAIN), cookieGeneration);
    }

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

            cookieJar.loadCookiesFromString(cookieString, DOMAIN);
            log.info("Loaded cookies from file");
            return true;
        } catch (Exception e) {
            log.error("Failed to load cookies from file", e);
            return false;
        }
    }

    private void saveCookiesToFile() {
        try {
            String cookieString = cookieJar.getCookieString(DOMAIN);
            if (StringUtils.isNotBlank(cookieString)) {
                File cookiesFile = new File(accountSavePath, COOKIES_FILE_NAME);
                FileStoreUtil.saveStringToFile(cookiesFile, cookieString);
                log.info("Saved cookies to file");
            }
        } catch (Exception e) {
            log.error("Failed to save cookies to file", e);
        }
    }

    private void clearAllCookies() {
        cookieJar.clearAllCookies();

        File cookiesFile = new File(accountSavePath, COOKIES_FILE_NAME);
        if (cookiesFile.exists() && cookiesFile.delete()) {
            log.info("Deleted cookies file");
        }
    }

    protected void doLogin() {
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

    CustomCookieJar getCookieJar() {
        return cookieJar;
    }

    private static final class AuthenticationSnapshot {
        private final String cookieString;
        private final long cookieGeneration;

        private AuthenticationSnapshot(String cookieString, long cookieGeneration) {
            this.cookieString = cookieString;
            this.cookieGeneration = cookieGeneration;
        }
    }

    static class CustomCookieJar implements CookieJar {
        private final Map<String, List<Cookie>> cookieStore = new HashMap<>();

        @Override
        public synchronized void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
            String host = url.host();
            List<Cookie> existed = getCookiesByDomain(host);
            existed.addAll(cookies);
            cookieStore.put(host, existed);
        }

        @Override
        public synchronized List<Cookie> loadForRequest(HttpUrl url) {
            return getCookiesByDomain(url.host());
        }

        public synchronized List<Cookie> getCookiesByDomain(String domain) {
            return new ArrayList<>(cookieStore.getOrDefault(domain, Collections.emptyList()));
        }

        public synchronized void loadCookiesFromString(String cookieString, String domain) {
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

        public synchronized void clearAllCookies() {
            cookieStore.clear();
        }

        public synchronized String getCookieString(String domain) {
            return getCookiesByDomain(domain).stream()
                    .map(cookie -> cookie.name() + "=" + cookie.value())
                    .collect(Collectors.joining("; "));
        }
    }
}
