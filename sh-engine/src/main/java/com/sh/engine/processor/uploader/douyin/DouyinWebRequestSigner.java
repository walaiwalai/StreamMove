package com.sh.engine.processor.uploader.douyin;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import lombok.Value;
import okhttp3.Headers;
import okhttp3.HttpUrl;

import java.io.Closeable;
import java.io.File;
import java.net.URI;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Uses the creator-center JavaScript security runtime to sign a request. The signed request is
 * intercepted and aborted; its URL and headers are then replayed by Java HTTP code.
 */
public final class DouyinWebRequestSigner implements Closeable {
    private static final String CREATOR_ORIGIN = "https://creator.douyin.com";
    private static final String UPLOAD_PAGE = CREATOR_ORIGIN + "/creator-micro/content/upload";

    private final File storageStateFile;
    private final Playwright playwright;
    private final Browser browser;
    private final BrowserContext context;
    private final Page page;
    private final String userId;

    public DouyinWebRequestSigner(File storageStateFile) {
        if (storageStateFile == null || !storageStateFile.isFile()) {
            throw new IllegalStateException("抖音登录态文件不存在: "
                    + (storageStateFile == null ? "null" : storageStateFile.getAbsolutePath()));
        }
        this.storageStateFile = storageStateFile;
        this.playwright = Playwright.create();
        this.browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setArgs(Arrays.asList("--no-sandbox", "--disable-setuid-sandbox",
                        "--disable-blink-features=AutomationControlled")));
        this.context = browser.newContext(new Browser.NewContextOptions()
                .setStorageStatePath(Paths.get(storageStateFile.getAbsolutePath()))
                .setViewportSize(1440, 900));
        this.page = context.newPage();
        page.navigate(UPLOAD_PAGE, new Page.NavigateOptions().setTimeout(60_000));
        if (page.url().contains("login")) {
            close();
            throw new IllegalStateException("抖音登录态已失效，请重新扫码生成 douyin-cookies.json");
        }
        Object tokenJson = page.evaluate("() => localStorage.getItem('__tea_cache_tokens_2906')");
        JSONObject token = tokenJson == null ? null : JSON.parseObject(String.valueOf(tokenJson));
        this.userId = token == null ? null : token.getString("user_unique_id");
        if (userId == null || userId.trim().isEmpty()) {
            close();
            throw new IllegalStateException("无法从抖音创作者中心登录态读取 user_unique_id，请重新扫码登录");
        }
    }

    public String getUserId() {
        return userId;
    }

    public synchronized SignedRequest sign(String method, String url, String body, String contentType) {
        String absoluteUrl = url.startsWith("http://") || url.startsWith("https://")
                ? url : CREATOR_ORIGIN + url;
        String path = URI.create(absoluteUrl).getPath();
        String pattern = "**" + path + "**";
        AtomicReference<SignedRequest> captured = new AtomicReference<>();

        Consumer<Route> handler = route -> {
            if (captured.get() != null || !route.request().method().equalsIgnoreCase(method)) {
                route.resume();
                return;
            }
            captured.set(new SignedRequest(route.request().method(), route.request().url(),
                    new LinkedHashMap<>(route.request().allHeaders())));
            route.abort();
        };

        page.route(pattern, handler);
        try {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("method", method.toUpperCase());
            args.put("url", absoluteUrl);
            args.put("body", body);
            args.put("contentType", contentType);
            page.evaluate("args => {"
                    + "const headers = {};"
                    + "if (args.contentType) headers['content-type'] = args.contentType;"
                    + "const options = {method: args.method, headers, credentials: 'include'};"
                    + "if (args.body !== null) options.body = args.body;"
                    + "return fetch(args.url, options).catch(() => undefined);"
                    + "}", args);
            if (captured.get() == null) {
                throw new IllegalStateException("未捕获到抖音签名请求: " + path);
            }
            return captured.get();
        } finally {
            page.unroute(pattern, handler);
        }
    }

    /**
     * Executes a small, already AWS4-signed control-plane request in the creator page network
     * context. VOD/ImageX require their allocation and commit calls to share this context; media
     * bytes never pass through the browser.
     */
    public synchronized BrowserHttpResponse executeSignedControlRequest(String method,
                                                                         String url,
                                                                         Map<String, String> headers,
                                                                         String body) {
        Map<String, String> allowedHeaders = new LinkedHashMap<>();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            String name = header.getKey().toLowerCase();
            if (!Arrays.asList("host", "content-length", "connection", "user-agent",
                    "referer", "origin", "sec-ch-ua", "sec-ch-ua-mobile",
                    "sec-ch-ua-platform").contains(name)) {
                allowedHeaders.put(header.getKey(), header.getValue());
            }
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("method", method.toUpperCase());
        args.put("url", url);
        args.put("headers", allowedHeaders);
        args.put("body", body);
        Object raw = page.evaluate("async args => {"
                + "const options = {method: args.method, headers: args.headers, credentials: 'omit', "
                + "referrer: 'https://creator.douyin.com/', referrerPolicy: 'strict-origin-when-cross-origin'};"
                + "if (args.body !== null) options.body = args.body;"
                + "const response = await fetch(args.url, options);"
                + "return {status: response.status, body: await response.text()};"
                + "}", args);
        JSONObject result = JSON.parseObject(JSON.toJSONString(raw));
        return new BrowserHttpResponse(result.getIntValue("status"), result.getString("body"));
    }

    /** Sends a creator-center request through its own security runtime and returns the response. */
    public synchronized BrowserHttpResponse executeCreatorRequest(String method,
                                                                   String url,
                                                                   String body,
                                                                   String contentType) {
        String absoluteUrl = url.startsWith("http://") || url.startsWith("https://")
                ? url : CREATOR_ORIGIN + url;
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("method", method.toUpperCase());
        args.put("url", absoluteUrl);
        args.put("body", body);
        args.put("contentType", contentType);
        Object raw = page.evaluate("async args => {"
                + "const headers = {};"
                + "if (args.contentType) headers['content-type'] = args.contentType;"
                + "const options = {method: args.method, headers, credentials: 'include'};"
                + "if (args.body !== null) options.body = args.body;"
                + "const response = await fetch(args.url, options);"
                + "return {status: response.status, body: await response.text()};"
                + "}", args);
        JSONObject result = JSON.parseObject(JSON.toJSONString(raw));
        return new BrowserHttpResponse(result.getIntValue("status"), result.getString("body"));
    }

    /** Keep browser storage state in sync with cookies refreshed by the Java HTTP responses. */
    public synchronized void acceptResponseCookies(HttpUrl requestUrl, Headers headers) {
        List<okhttp3.Cookie> responseCookies = okhttp3.Cookie.parseAll(requestUrl, headers);
        if (responseCookies.isEmpty()) {
            return;
        }
        List<com.microsoft.playwright.options.Cookie> playwrightCookies = new ArrayList<>();
        for (okhttp3.Cookie cookie : responseCookies) {
            com.microsoft.playwright.options.Cookie target =
                    new com.microsoft.playwright.options.Cookie(cookie.name(), cookie.value())
                            .setDomain(cookie.domain())
                            .setPath(cookie.path())
                            .setHttpOnly(cookie.httpOnly())
                            .setSecure(cookie.secure());
            if (cookie.persistent()) {
                target.setExpires(cookie.expiresAt() / 1000.0);
            }
            playwrightCookies.add(target);
        }
        context.addCookies(playwrightCookies);
    }

    @Override
    public void close() {
        try {
            if (context != null) {
                context.storageState(new BrowserContext.StorageStateOptions()
                        .setPath(Paths.get(storageStateFile.getAbsolutePath())));
                context.close();
            }
        } finally {
            try {
                if (browser != null) {
                    browser.close();
                }
            } finally {
                if (playwright != null) {
                    playwright.close();
                }
            }
        }
    }

    @Value
    public static class SignedRequest {
        String method;
        String url;
        Map<String, String> headers;
    }

    @Value
    public static class BrowserHttpResponse {
        int status;
        String body;
    }
}
