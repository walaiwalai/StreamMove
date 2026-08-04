package com.sh.engine.processor.uploader.meituan;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import com.sh.engine.processor.uploader.browser.PersistentBrowserProfile;
import com.sh.message.service.MsgSendService;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import org.apache.commons.lang3.StringUtils;

import java.io.Closeable;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Keeps the Meituan creator page security runtime alive. Browser-fingerprint-bound control APIs
 * run through the site's official Axios instance; Java OkHttp transfers cover and video bytes.
 */
@Slf4j
public final class MeituanWebSession implements Closeable {
    private static final String CONTENTS_ORIGIN = "https://contents.meituan.com";
    private static final String PUBLISH_PAGE = "https://czz.meituan.com/new/publishVideo";
    private static final String LOGIN_AUTH_PATH = "/api/author/creator/loginAuth";
    private static final String VIDEO_INPUT = "input[type='file'][accept*='video']";
    private static final String PROFILE_DIR_NAME = "meituan-browser-profile";

    private final PersistentBrowserProfile browserProfile;
    private final MsgSendService msgSendService;
    private final Playwright playwright;
    private final BrowserContext context;
    private final Page page;
    private final AtomicReference<RequestTemplate> requestTemplate = new AtomicReference<>();
    private final AtomicReference<CreatorProfile> creatorProfile = new AtomicReference<>();

    public MeituanWebSession(File storageStateFile) {
        this(storageStateFile, null);
    }

    public MeituanWebSession(File storageStateFile, MsgSendService msgSendService) {
        this.browserProfile = new PersistentBrowserProfile(storageStateFile, PROFILE_DIR_NAME,
                "美团");
        this.msgSendService = msgSendService;
        this.playwright = Playwright.create();
        this.context = playwright.chromium().launchPersistentContext(browserProfile.getProfilePath(),
                new BrowserType.LaunchPersistentContextOptions()
                .setHeadless(true)
                .setArgs(Arrays.asList("--no-sandbox", "--disable-setuid-sandbox",
                        "--disable-blink-features=AutomationControlled"))
                .setViewportSize(1440, 900));
        browserProfile.importLegacyStorageStateIfRequired(context);
        this.page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
        page.setDefaultTimeout(20_000);
        page.onRequest(this::captureRequestTemplate);
        page.onResponse(this::captureCreatorProfile);

        try {
            page.navigate(PUBLISH_PAGE, new Page.NavigateOptions().setTimeout(60_000));
            waitForBootstrap();
            initializeOfficialHttpClient();
            persistStorageState();
            browserProfile.markReady();
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    public CreatorProfile getCreatorProfile() {
        CreatorProfile profile = creatorProfile.get();
        if (profile == null) {
            throw new IllegalStateException("美团创作者账号信息尚未初始化");
        }
        return profile;
    }

    public Map<String, String> getBrowserIdentityHeaders() {
        RequestTemplate template = requireRequestTemplate();
        Map<String, String> headers = new LinkedHashMap<>();
        copyIfNotBlank(headers, "User-Agent", template.userAgent);
        copyIfNotBlank(headers, "sec-ch-ua", template.secChUa);
        copyIfNotBlank(headers, "sec-ch-ua-mobile", template.secChUaMobile);
        copyIfNotBlank(headers, "sec-ch-ua-platform", template.secChUaPlatform);
        headers.put("Origin", "https://czz.meituan.com");
        headers.put("Referer", "https://czz.meituan.com/");
        return headers;
    }

    /**
     * Executes a creator control API through Meituan's own Axios instance. Its mtgsig is bound to
     * the browser network fingerprint, so replaying that signed URL through another HTTP stack is
     * rejected by the platform. Video and cover bytes are still transferred by Java OkHttp.
     */
    public synchronized JSONObject executeOfficialApiJson(String method,
                                                          String url,
                                                          String body,
                                                          String contentType,
                                                          String operation) {
        String absoluteUrl = url.startsWith("http://") || url.startsWith("https://")
                ? url : CONTENTS_ORIGIN + url;
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("method", method.toUpperCase());
        args.put("url", absoluteUrl);
        args.put("body", body);
        args.put("contentType", contentType);
        Object serialized = page.evaluate("async args => {"
                + "const config = {method: args.method, url: args.url, withCredentials: true};"
                + "if (args.contentType) config.headers = {'content-type': args.contentType};"
                + "if (args.body !== null) {"
                + "  if (String(args.contentType || '').includes('application/json')) {"
                + "    try { config.data = JSON.parse(args.body); }"
                + "    catch (ignored) { config.data = args.body; }"
                + "  } else { config.data = args.body; }"
                + "}"
                + "try {"
                + "  const data = await window.__streamerRecordMeituanHttp.request(config);"
                + "  return JSON.stringify({ok: true, data: data === undefined ? null : data});"
                + "} catch (error) {"
                + "  const response = error && error.response ? error.response.data : null;"
                + "  return JSON.stringify({ok: false, message: String(error && error.message "
                + "    ? error.message : error), response});"
                + "}"
                + "}", args);
        JSONObject wrapper = JSON.parseObject(String.valueOf(serialized));
        if (wrapper == null || !wrapper.getBooleanValue("ok")) {
            throw new IllegalStateException(operation + "失败: "
                    + (wrapper == null ? "官网请求返回为空" : wrapper.getString("message"))
                    + (wrapper == null || wrapper.get("response") == null ? ""
                    : ", response=" + wrapper.get("response")));
        }
        JSONObject result = new JSONObject(true);
        result.put("code", 0);
        result.put("message", null);
        result.put("data", wrapper.get("data"));
        result.put("success", true);
        return result;
    }

    public synchronized SignedRequest sign(String method,
                                           String url,
                                           String body,
                                           String contentType,
                                           Map<String, String> extraHeaders) {
        String absoluteUrl = url.startsWith("http://") || url.startsWith("https://")
                ? url : CONTENTS_ORIGIN + url;
        URI uri = URI.create(absoluteUrl);
        String pattern = "**" + uri.getPath() + "**";
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
            Map<String, String> headers = new LinkedHashMap<>();
            if (CONTENTS_ORIGIN.equalsIgnoreCase(uri.getScheme() + "://" + uri.getHost())) {
                RequestTemplate template = requireRequestTemplate();
                // Match the creator site's Axios defaults captured from its official request.
                headers.put("accept", "application/json, text/plain, */*");
                headers.put("accept-language", "zh-CN,zh;q=0.9");
                copyIfNotBlank(headers, "token", template.token);
                copyIfNotBlank(headers, "mtuserid", template.mtUserId);
                copyIfNotBlank(headers, "authorsource", template.authorSource);
            }
            if (extraHeaders != null) {
                headers.putAll(extraHeaders);
            }
            if (StringUtils.isNotBlank(contentType)) {
                headers.put("content-type", contentType);
            }

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("method", method.toUpperCase());
            args.put("url", absoluteUrl);
            args.put("headers", headers);
            args.put("body", body);
            args.put("credentials", CONTENTS_ORIGIN.equalsIgnoreCase(
                    uri.getScheme() + "://" + uri.getHost()) ? "include" : "omit");
            if (CONTENTS_ORIGIN.equalsIgnoreCase(uri.getScheme() + "://" + uri.getHost())) {
                page.evaluate("args => {"
                        + "const config = {method: args.method, url: args.url, "
                        + "headers: args.headers, withCredentials: true};"
                        + "if (args.body !== null) {"
                        + "  const contentType = String(args.headers['content-type'] || '');"
                        + "  if (contentType.includes('application/json')) {"
                        + "    try { config.data = JSON.parse(args.body); }"
                        + "    catch (ignored) { config.data = args.body; }"
                        + "  } else { config.data = args.body; }"
                        + "}"
                        + "return window.__streamerRecordMeituanHttp.request(config)"
                        + ".catch(() => undefined);"
                        + "}", args);
            } else {
                page.evaluate("args => {"
                        + "const options = {method: args.method, headers: args.headers, "
                        + "credentials: args.credentials};"
                        + "if (args.body !== null) options.body = args.body;"
                        + "return fetch(args.url, options).catch(() => undefined);"
                        + "}", args);
            }
            if (captured.get() == null) {
                throw new IllegalStateException("未捕获到美团签名请求: " + uri.getPath());
            }
            return captured.get();
        } finally {
            page.unroute(pattern, handler);
        }
    }

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
                persistStorageState();
                context.close();
            }
        } finally {
            if (playwright != null) {
                playwright.close();
            }
        }
    }

    private void waitForBootstrap() {
        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1);
        Locator videoInput = page.locator(VIDEO_INPUT).first();
        while (System.currentTimeMillis() < deadline) {
            if (page.url().contains("/login")) {
                notifyLoginExpired();
                throw new IllegalStateException("美团登录态已失效；当前官网只提供手机号短信验证码登录，"
                        + "请使用桌面 Cookies 维护工具重新登录并更新 meituan-cookies.json");
            }
            if (requestTemplate.get() != null && creatorProfile.get() != null
                    && videoInput.count() > 0) {
                return;
            }
            page.waitForTimeout(500);
        }
        throw new IllegalStateException("等待美团创作者页面初始化超时，登录态可能已失效");
    }

    private void persistStorageState() {
        browserProfile.persistStorageState(context);
    }

    private void notifyLoginExpired() {
        if (msgSendService == null) {
            return;
        }
        String message = "美团创作者登录态已失效。服务器实际打开的美团官方登录页当前只提供"
                + "手机号 + 短信验证码，没有可发送的二维码。请使用桌面的“视频平台 Cookies 维护工具”"
                + "重新登录美团，并将 meituan-cookies.json 更新到服务器账号目录。";
        try {
            msgSendService.sendText(message);
        } catch (RuntimeException e) {
            log.error("Failed to send Meituan login-state notification: {}", message, e);
        }
    }

    private void initializeOfficialHttpClient() {
        Object moduleUrl = page.evaluate("async () => {"
                + "const urls = performance.getEntriesByType('resource')"
                + ".map(entry => entry.name)"
                + ".filter(url => /\\/assets\\/index\\.[a-f0-9]+\\.js(?:\\?|$)/i.test(url));"
                + "for (const url of urls) {"
                + "  try {"
                + "    const module = await import(url);"
                + "    if (module.J && typeof module.J.request === 'function') {"
                + "      window.__streamerRecordMeituanHttp = module.J;"
                + "      return url;"
                + "    }"
                + "  } catch (ignored) {}"
                + "}"
                + "return null;"
                + "}");
        if (moduleUrl == null) {
            throw new IllegalStateException("无法加载美团创作者官网 HTTP 模块，页面资源版本可能已变更");
        }
    }

    private void captureRequestTemplate(Request request) {
        try {
            URI uri = URI.create(request.url());
            if (!"contents.meituan.com".equalsIgnoreCase(uri.getHost())
                    || !uri.getPath().startsWith("/api/author/")) {
                return;
            }
            Map<String, String> headers = request.allHeaders();
            String token = findHeader(headers, "token");
            String mtUserId = findHeader(headers, "mtuserid");
            if (StringUtils.isAnyBlank(token, mtUserId)) {
                return;
            }
            requestTemplate.compareAndSet(null, new RequestTemplate(token, mtUserId,
                    StringUtils.defaultIfBlank(findHeader(headers, "authorsource"), "mt"),
                    findHeader(headers, "user-agent"), findHeader(headers, "sec-ch-ua"),
                    findHeader(headers, "sec-ch-ua-mobile"),
                    findHeader(headers, "sec-ch-ua-platform")));
        } catch (RuntimeException ignored) {
            // A later creator API request can provide the same stable header template.
        }
    }

    private void captureCreatorProfile(Response response) {
        try {
            if (!response.url().contains(LOGIN_AUTH_PATH)) {
                return;
            }
            JSONObject root = JSON.parseObject(response.text());
            if (root == null || root.getIntValue("code") != 0 || !root.getBooleanValue("success")) {
                return;
            }
            JSONObject data = root.getJSONObject("data");
            if (data == null || StringUtils.isBlank(data.getString("creatorId"))) {
                return;
            }
            String creatorId = data.getString("creatorId");
            creatorProfile.compareAndSet(null, new CreatorProfile(creatorId, creatorId,
                    data.getString("creatorName"), data.getString("creatorIcon")));
        } catch (RuntimeException ignored) {
            // Wait for the next loginAuth response if the page cancelled this one.
        }
    }

    private RequestTemplate requireRequestTemplate() {
        RequestTemplate template = requestTemplate.get();
        if (template == null) {
            throw new IllegalStateException("美团请求签名上下文尚未初始化");
        }
        return template;
    }

    private static String findHeader(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static void copyIfNotBlank(Map<String, String> target, String name, String value) {
        if (StringUtils.isNotBlank(value)) {
            target.put(name, value);
        }
    }

    @Value
    private static class RequestTemplate {
        String token;
        String mtUserId;
        String authorSource;
        String userAgent;
        String secChUa;
        String secChUaMobile;
        String secChUaPlatform;
    }

    @Value
    public static class CreatorProfile {
        String authorId;
        String creatorId;
        String authorName;
        String avatarUrl;
    }

    @Value
    public static class SignedRequest {
        String method;
        String url;
        Map<String, String> headers;
    }
}
