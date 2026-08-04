package com.sh.engine.processor.uploader.douyin;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import com.sh.engine.processor.uploader.browser.BrowserQrImageSaver;
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
 * Uses the creator-center JavaScript security runtime to sign a request. The signed request is
 * intercepted and aborted; its URL and headers are then replayed by Java HTTP code.
 */
@Slf4j
public final class DouyinWebRequestSigner implements Closeable {
    private static final String CREATOR_ORIGIN = "https://creator.douyin.com";
    private static final String UPLOAD_PAGE = CREATOR_ORIGIN + "/creator-micro/content/upload";
    private static final String PROFILE_DIR_NAME = "douyin-browser-profile";
    private static final String LOGIN_QR_FILE_NAME = "douyin-login-qr.png";
    private static final int LOGIN_WAIT_MINUTES = 10;
    private static final int LOGIN_QR_REFRESH_MINUTES = 4;

    private final File storageStateFile;
    private final PersistentBrowserProfile browserProfile;
    private final MsgSendService msgSendService;
    private final Playwright playwright;
    private final BrowserContext context;
    private final Page page;
    private final String userId;

    public DouyinWebRequestSigner(File storageStateFile) {
        this(storageStateFile, null);
    }

    public DouyinWebRequestSigner(File storageStateFile, MsgSendService msgSendService) {
        this.storageStateFile = storageStateFile;
        this.browserProfile = new PersistentBrowserProfile(storageStateFile, PROFILE_DIR_NAME,
                "抖音");
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
        try {
            page.navigate(UPLOAD_PAGE, new Page.NavigateOptions().setTimeout(60_000));
            this.userId = waitForAuthenticatedUserId();
            persistStorageState();
            browserProfile.markReady();
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    public String getUserId() {
        return userId;
    }

    private String waitForAuthenticatedUserId() {
        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1);
        while (System.currentTimeMillis() < deadline) {
            Locator qrImage = findVisibleLoginQrImage();
            if (qrImage != null) {
                waitForQrLogin(qrImage);
                deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1);
                continue;
            }
            String currentUserId = readUserId();
            if (currentUserId != null && !currentUserId.trim().isEmpty()
                    && isUploadPageReady()) {
                return currentUserId;
            }
            page.waitForTimeout(500);
        }
        throw new IllegalStateException("无法从抖音创作者中心登录态读取 user_unique_id，请重新扫码登录");
    }

    private void waitForQrLogin(Locator currentQrImage) {
        if (msgSendService == null) {
            throw new IllegalStateException("抖音登录态已失效，请重新扫码生成 douyin-cookies.json");
        }
        sendLoginQr(currentQrImage, false);
        long deadline = System.currentTimeMillis()
                + TimeUnit.MINUTES.toMillis(LOGIN_WAIT_MINUTES);
        long nextRefresh = System.currentTimeMillis()
                + TimeUnit.MINUTES.toMillis(LOGIN_QR_REFRESH_MINUTES);
        while (System.currentTimeMillis() < deadline) {
            page.waitForTimeout(1_000);
            Locator qrImage = findVisibleLoginQrImage();
            String currentUserId = readUserId();
            if (qrImage == null && currentUserId != null && !currentUserId.trim().isEmpty()
                    && isUploadPageReady()) {
                page.navigate(UPLOAD_PAGE, new Page.NavigateOptions().setTimeout(60_000));
                page.waitForTimeout(1_000);
                if (findVisibleLoginQrImage() != null || !isUploadPageReady()) {
                    throw new IllegalStateException("抖音扫码确认后仍未登录，请重新扫码");
                }
                persistStorageState();
                log.info("Douyin QR login succeeded; storage state refreshed: {}",
                        storageStateFile.getAbsolutePath());
                notifyText("抖音扫码登录成功，线上登录态已更新，将继续上传视频。");
                return;
            }
            if (System.currentTimeMillis() >= nextRefresh) {
                page.reload(new Page.ReloadOptions().setTimeout(60_000));
                page.waitForTimeout(1_000);
                if (findVisibleLoginQrImage() == null && isUploadPageReady()
                        && StringUtils.isNotBlank(readUserId())) {
                    persistStorageState();
                    notifyText("抖音扫码登录成功，线上登录态已更新，将继续上传视频。");
                    return;
                }
                Locator refreshedQr = waitForLoginQrImage();
                sendLoginQr(refreshedQr, true);
                nextRefresh = System.currentTimeMillis()
                        + TimeUnit.MINUTES.toMillis(LOGIN_QR_REFRESH_MINUTES);
            }
        }
        throw new IllegalStateException("等待抖音扫码登录超时，请在下次二维码通知后重试");
    }

    private void sendLoginQr(Locator qrImage, boolean refreshed) {
        File qrImageFile = browserProfile.resolveAccountFile(LOGIN_QR_FILE_NAME);
        BrowserQrImageSaver.save(qrImage, qrImageFile);
        log.warn("Douyin login requires QR confirmation, refreshed: {}, QR image: {}",
                refreshed, qrImageFile.getAbsolutePath());
        String prefix = refreshed ? "抖音登录二维码已刷新" : "抖音登录态已失效";
        notifyText(prefix + "，请在" + LOGIN_WAIT_MINUTES
                + "分钟等待期内使用抖音 App 扫描随后发送的二维码。\n二维码所在机器路径: "
                + qrImageFile.getAbsolutePath());
        notifyImage(qrImageFile);
    }

    private Locator waitForLoginQrImage() {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
        while (System.currentTimeMillis() < deadline) {
            Locator qrImage = findVisibleLoginQrImage();
            if (qrImage != null) {
                return qrImage;
            }
            page.waitForTimeout(500);
        }
        throw new IllegalStateException("等待抖音登录二维码加载超时");
    }

    private Locator findVisibleLoginQrImage() {
        Locator candidates = page.locator("img");
        int count = candidates.count();
        for (int index = 0; index < count; index++) {
            Locator candidate = candidates.nth(index);
            try {
                if (candidate.isVisible() && Boolean.TRUE.equals(candidate.evaluate(
                        "img => {"
                                + "if (!img.complete || img.naturalWidth < 200 "
                                + "|| img.naturalWidth !== img.naturalHeight) return false;"
                                + "const rect = img.getBoundingClientRect();"
                                + "if (rect.width < 140 || rect.width > 260 "
                                + "|| Math.abs(rect.width - rect.height) > 4) return false;"
                                + "let node = img;"
                                + "for (let level = 0; node && level < 8; level++, "
                                + "node = node.parentElement) {"
                                + "if (String(node.innerText || '').includes('扫码登录')) return true;"
                                + "}"
                                + "return false;"
                                + "}"))) {
                    return candidate;
                }
            } catch (RuntimeException e) {
                log.debug("Douyin login page changed while locating QR image", e);
            }
        }
        return null;
    }

    private String readUserId() {
        try {
            Object tokenJson = page.evaluate(
                    "() => localStorage.getItem('__tea_cache_tokens_2906')");
            JSONObject token = tokenJson == null
                    ? null : JSON.parseObject(String.valueOf(tokenJson));
            return token == null ? null : token.getString("user_unique_id");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean isUploadPageReady() {
        try {
            return page.locator("input[type='file']").count() > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void persistStorageState() {
        browserProfile.persistStorageState(context);
    }

    private void notifyText(String message) {
        try {
            msgSendService.sendText(message);
        } catch (RuntimeException e) {
            log.error("Failed to send Douyin login notification: {}", message, e);
        }
    }

    private void notifyImage(File imageFile) {
        try {
            msgSendService.sendImage(imageFile);
        } catch (RuntimeException e) {
            log.error("Failed to send Douyin login QR image: {}",
                    imageFile.getAbsolutePath(), e);
        }
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
                persistStorageState();
                context.close();
            }
        } finally {
            if (playwright != null) {
                playwright.close();
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
