package com.sh.engine.processor.uploader.wechat;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.SameSiteAttribute;
import com.sh.message.service.MsgSendService;
import lombok.Value;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import org.apache.commons.lang3.StringUtils;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps the WeChat Channels creator page security context alive for small control-plane calls.
 * Video and cover bytes are uploaded by {@link WechatVideoStorageClient}, not by Playwright.
 */
@Slf4j
public final class WechatVideoWebSession implements Closeable {
    private static final String ORIGIN = "https://channels.weixin.qq.com";
    private static final String PLATFORM_HOME = ORIGIN + "/platform";
    private static final String CREATE_FRAME_URL = ORIGIN + "/micro/content/post/create";
    private static final String UPLOAD_PARAMS_PATH = "/helper/helper_upload_params";
    private static final String AUTH_DATA_PATH = "/auth/auth_data";
    private static final String CONTROL_TEMPLATE_PATH = "/post/get_finder_post_comm_info";
    private static final String LOGIN_QR_FILE_NAME = "wechat-video-login-qr.jpg";
    private static final String LOGIN_QR_FRAME_HOST = "open.weixin.qq.com";
    private static final String LOGIN_QR_FRAME_PATH = "/connect/qrconnect";
    private static final String LOGIN_QR_IMAGE_SELECTOR =
            "img.js_qrcode_img.web_qrcode_img";
    private static final String PROFILE_DIR_NAME = "wechat-video-browser-profile";
    private static final String PROFILE_READY_FILE_NAME = ".streamer-record-profile-ready";
    private static final int LOGIN_WAIT_MINUTES = 10;
    private static final int LOGIN_QR_REFRESH_MINUTES = 4;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final File storageStateFile;
    private final File profileReadyFile;
    private final MsgSendService msgSendService;
    private final Playwright playwright;
    private final BrowserContext context;
    private final Page page;
    private final AtomicReference<UploadParams> uploadParams = new AtomicReference<>();
    private final AtomicReference<EnvironmentInfo> environmentInfo = new AtomicReference<>();
    private final AtomicReference<ControlTemplate> controlTemplate = new AtomicReference<>();

    public WechatVideoWebSession(File storageStateFile) {
        this(storageStateFile, null);
    }

    public WechatVideoWebSession(File storageStateFile, MsgSendService msgSendService) {
        if (storageStateFile == null) {
            throw new IllegalStateException("微信视频号登录态文件路径不能为空");
        }
        File accountDir = storageStateFile.getAbsoluteFile().getParentFile();
        if (accountDir == null || (!accountDir.isDirectory() && !accountDir.mkdirs())) {
            throw new IllegalStateException("无法创建微信视频号账号目录: "
                    + (accountDir == null ? "null" : accountDir.getAbsolutePath()));
        }
        File profileDir = new File(accountDir, PROFILE_DIR_NAME);
        if (!profileDir.isDirectory() && !profileDir.mkdirs()) {
            throw new IllegalStateException("无法创建微信视频号浏览器目录: "
                    + profileDir.getAbsolutePath());
        }
        this.storageStateFile = storageStateFile;
        this.profileReadyFile = new File(profileDir, PROFILE_READY_FILE_NAME);
        this.msgSendService = msgSendService;
        this.playwright = Playwright.create();
        this.context = playwright.chromium().launchPersistentContext(
                Paths.get(profileDir.getAbsolutePath()),
                new BrowserType.LaunchPersistentContextOptions()
                .setHeadless(true)
                .setArgs(Arrays.asList("--no-sandbox", "--disable-setuid-sandbox",
                        "--disable-blink-features=AutomationControlled"))
                .setViewportSize(1440, 900));
        importLegacyStorageStateIfRequired();
        this.page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
        page.setDefaultTimeout(20_000);
        page.onResponse(this::captureBootstrapResponse);
        page.onRequest(this::captureControlTemplate);

        try {
            page.navigate(PLATFORM_HOME, new Page.NavigateOptions().setTimeout(60_000));
            waitForQrLoginIfRequired();
            enterCreatePage();
            waitForBootstrap();
            persistStorageState();
            markProfileReady();
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    public UploadContext getUploadContext() {
        UploadParams upload = uploadParams.get();
        EnvironmentInfo environment = environmentInfo.get();
        if (upload == null || environment == null) {
            throw new IllegalStateException("微信视频号上传参数尚未初始化");
        }
        return new UploadContext(upload.authKey, upload.uin, upload.appType,
                upload.videoFileType, upload.pictureFileType, upload.scene,
                environment.cdnHost, environment.cdnHostList,
                environment.asyncClipPostSwitch, environment.enableAllowAstraThumbCover,
                environment.enablePostShareCoverUrl);
    }

    private void importLegacyStorageStateIfRequired() {
        if (profileReadyFile.isFile() || !storageStateFile.isFile()) {
            return;
        }
        JSONObject storageState;
        try {
            byte[] content = Files.readAllBytes(storageStateFile.toPath());
            storageState = JSON.parseObject(new String(content, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("Existing WeChat Channels storage state cannot be imported; "
                    + "continue with the server browser profile: {}",
                    storageStateFile.getAbsolutePath(), e);
            return;
        }
        if (storageState == null) {
            return;
        }

        List<Cookie> cookies = new ArrayList<>();
        JSONArray cookieArray = storageState.getJSONArray("cookies");
        if (cookieArray != null) {
            for (int index = 0; index < cookieArray.size(); index++) {
                JSONObject source = cookieArray.getJSONObject(index);
                String name = source == null ? null : source.getString("name");
                String value = source == null ? null : source.getString("value");
                if (StringUtils.isAnyBlank(name, value)) {
                    continue;
                }
                Cookie cookie = new Cookie(name, value);
                String domain = source.getString("domain");
                String path = source.getString("path");
                if (StringUtils.isNotBlank(domain)) {
                    cookie.setDomain(domain);
                    cookie.setPath(StringUtils.defaultIfBlank(path, "/"));
                } else if (StringUtils.isNotBlank(source.getString("url"))) {
                    cookie.setUrl(source.getString("url"));
                } else {
                    continue;
                }
                Double expires = source.getDouble("expires");
                if (expires != null && expires >= 0D) {
                    cookie.setExpires(expires);
                }
                Boolean httpOnly = source.getBoolean("httpOnly");
                if (httpOnly != null) {
                    cookie.setHttpOnly(httpOnly);
                }
                Boolean secure = source.getBoolean("secure");
                if (secure != null) {
                    cookie.setSecure(secure);
                }
                String sameSite = source.getString("sameSite");
                if (StringUtils.isNotBlank(sameSite)) {
                    try {
                        cookie.setSameSite(SameSiteAttribute.valueOf(
                                sameSite.toUpperCase(Locale.ROOT)));
                    } catch (IllegalArgumentException e) {
                        log.warn("Ignore unsupported SameSite value while importing WeChat "
                                + "Channels cookie: {}", sameSite);
                    }
                }
                cookies.add(cookie);
            }
        }
        if (!cookies.isEmpty()) {
            context.addCookies(cookies);
        }

        JSONObject localStorageByOrigin = new JSONObject(true);
        JSONArray origins = storageState.getJSONArray("origins");
        if (origins != null) {
            for (int index = 0; index < origins.size(); index++) {
                JSONObject origin = origins.getJSONObject(index);
                if (origin == null || StringUtils.isBlank(origin.getString("origin"))) {
                    continue;
                }
                JSONArray items = origin.getJSONArray("localStorage");
                if (items != null && !items.isEmpty()) {
                    localStorageByOrigin.put(origin.getString("origin"), items);
                }
            }
        }
        if (!localStorageByOrigin.isEmpty()) {
            context.addInitScript("(() => { const states = "
                    + localStorageByOrigin.toJSONString()
                    + "; const items = states[location.origin]; if (!items) return; "
                    + "for (const item of items) { if (localStorage.getItem(item.name) === null) "
                    + "localStorage.setItem(item.name, item.value); } })();");
        }
        log.info("Imported existing WeChat Channels storage state into server browser profile: {}",
                storageStateFile.getAbsolutePath());
    }

    /** Executes one captured creator-center POST request in the authenticated browser frame. */
    public synchronized JSONObject post(String path, JSONObject businessBody, String operation) {
        ControlTemplate template = controlTemplate.get();
        if (template == null) {
            throw new IllegalStateException("微信视频号控制请求模板尚未初始化");
        }
        JSONObject body = new JSONObject(true);
        if (businessBody != null) {
            body.putAll(businessBody);
        }
        body.put("timestamp", String.valueOf(System.currentTimeMillis()));
        copyBaseField(template.baseBody, body, "_log_finder_uin");
        copyBaseField(template.baseBody, body, "_log_finder_id");
        copyBaseField(template.baseBody, body, "rawKeyBuff");
        copyBaseField(template.baseBody, body, "pluginSessionId");
        copyBaseField(template.baseBody, body, "scene");
        copyBaseField(template.baseBody, body, "reqScene");

        HttpUrl.Builder url = HttpUrl.parse(ORIGIN + path).newBuilder();
        if (StringUtils.isNotBlank(template.aid)) {
            url.addQueryParameter("_aid", template.aid);
        }
        url.addQueryParameter("_rid", requestId());
        url.addQueryParameter("_pageUrl", template.pageUrl);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("accept", "*/*");
        headers.put("content-type", "application/json");
        headers.put("x-wechat-uin", template.wechatUin);
        if (StringUtils.isNotBlank(template.fingerprint)) {
            headers.put("finger-print-device-id", template.fingerprint);
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("url", url.build().toString());
        args.put("headers", headers);
        args.put("body", body.toJSONString());

        Frame frame = findCreateFrame();
        if (frame == null) {
            throw new IllegalStateException("微信视频号发布页面已失效，请重新执行上传");
        }
        Object raw = frame.evaluate("async args => {"
                + "const response = await fetch(args.url, {method: 'POST', headers: args.headers, "
                + "credentials: 'include', body: args.body});"
                + "return {status: response.status, body: await response.text()};"
                + "}", args);
        JSONObject httpResult = JSON.parseObject(JSON.toJSONString(raw));
        int status = httpResult.getIntValue("status");
        if (status < 200 || status >= 300) {
            throw new IllegalStateException(operation + "失败: HTTP " + status);
        }
        JSONObject result = JSON.parseObject(httpResult.getString("body"));
        if (result == null) {
            throw new IllegalStateException(operation + "返回了空 JSON");
        }
        return result;
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

    private void enterCreatePage() {
        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1);
        RuntimeException lastError = null;
        while (System.currentTimeMillis() < deadline) {
            if (isLoginPage(page.url())) {
                waitForQrLoginIfRequired();
                deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1);
                lastError = null;
                continue;
            }
            if (findCreateFrame() != null) {
                return;
            }
            try {
                Locator publishButton = page.getByText("发表视频",
                        new Page.GetByTextOptions().setExact(true));
                if (publishButton.count() > 0 && publishButton.first().isVisible()) {
                    publishButton.first().click(new Locator.ClickOptions().setTimeout(10_000));
                }
            } catch (RuntimeException e) {
                lastError = e;
            }
            page.waitForTimeout(1_000);
        }
        throw new IllegalStateException("无法进入微信视频号发表视频页面", lastError);
    }

    private void waitForQrLoginIfRequired() {
        if (!isLoginPage(page.url())) {
            return;
        }
        if (msgSendService == null) {
            throw new IllegalStateException("微信视频号登录态已失效，请重新扫码生成 wechat-video-cookies.json");
        }

        // 延迟跳转到登录页时，URL 会先变化，二维码随后才完成渲染。
        page.waitForTimeout(1_000);
        sendLoginQr(false);

        long deadline = System.currentTimeMillis()
                + TimeUnit.MINUTES.toMillis(LOGIN_WAIT_MINUTES);
        long nextRefresh = System.currentTimeMillis()
                + TimeUnit.MINUTES.toMillis(LOGIN_QR_REFRESH_MINUTES);
        while (System.currentTimeMillis() < deadline) {
            page.waitForTimeout(1_000);
            if (isLoginPage(page.url())) {
                if (System.currentTimeMillis() >= nextRefresh) {
                    page.reload(new Page.ReloadOptions().setTimeout(60_000));
                    page.waitForTimeout(1_000);
                    if (isLoginPage(page.url())) {
                        sendLoginQr(true);
                    }
                    nextRefresh = System.currentTimeMillis()
                            + TimeUnit.MINUTES.toMillis(LOGIN_QR_REFRESH_MINUTES);
                }
                continue;
            }
            page.waitForTimeout(1_000);
            resetBootstrapState();
            page.navigate(PLATFORM_HOME, new Page.NavigateOptions().setTimeout(60_000));
            if (isLoginPage(page.url())) {
                throw new IllegalStateException("微信视频号扫码确认后仍未登录，请重新扫码");
            }
            persistStorageState();
            log.info("WeChat Channels QR login succeeded; storage state refreshed: {}",
                    storageStateFile.getAbsolutePath());
            notifyText("微信视频号扫码登录成功，线上登录态已更新，将继续上传视频。");
            return;
        }
        throw new IllegalStateException("等待微信视频号扫码登录超时，请在下次二维码通知后重试");
    }

    private void sendLoginQr(boolean refreshed) {
        File qrImageFile = new File(storageStateFile.getAbsoluteFile().getParentFile(),
                LOGIN_QR_FILE_NAME);
        Locator qrImage = waitForLoginQrImage();
        try {
            saveOriginalQrImage(qrImage, qrImageFile);
        } catch (RuntimeException e) {
            log.warn("Failed to read original WeChat Channels QR image; "
                    + "fall back to element screenshot", e);
            qrImage.screenshot(new Locator.ScreenshotOptions()
                    .setPath(Paths.get(qrImageFile.getAbsolutePath())));
        }
        if (!qrImageFile.isFile() || qrImageFile.length() == 0L) {
            throw new IllegalStateException("微信视频号登录二维码图片未生成: "
                    + qrImageFile.getAbsolutePath());
        }

        log.warn("WeChat Channels login requires QR confirmation, refreshed: {}, QR image: {}",
                refreshed, qrImageFile.getAbsolutePath());
        String prefix = refreshed ? "微信视频号登录二维码已刷新" : "微信视频号登录态已失效";
        notifyText(prefix + "，请在" + LOGIN_WAIT_MINUTES
                + "分钟等待期内使用手机微信扫描随后发送的二维码。\n二维码所在机器路径: "
                + qrImageFile.getAbsolutePath());
        notifyImage(qrImageFile);
    }

    private Locator waitForLoginQrImage() {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
        while (System.currentTimeMillis() < deadline) {
            for (Frame frame : page.frames()) {
                if (!isLoginQrFrame(frame.url())) {
                    continue;
                }
                try {
                    Locator candidates = frame.locator(LOGIN_QR_IMAGE_SELECTOR);
                    int count = candidates.count();
                    for (int index = 0; index < count; index++) {
                        Locator candidate = candidates.nth(index);
                        if (candidate.isVisible() && Boolean.TRUE.equals(candidate.evaluate(
                                "img => img.complete && img.naturalWidth > 0 "
                                        + "&& img.naturalHeight > 0"))) {
                            return candidate;
                        }
                    }
                } catch (RuntimeException e) {
                    // iframe 刷新时 Frame/Locator 可能短暂失效，下一轮重新查找。
                    log.debug("WeChat Channels QR frame changed while waiting", e);
                }
            }
            page.waitForTimeout(500);
        }
        throw new IllegalStateException("等待微信视频号登录二维码加载超时");
    }

    private static void saveOriginalQrImage(Locator qrImage, File targetFile) {
        Object raw = qrImage.evaluate("async img => {"
                + "const response = await fetch(img.currentSrc || img.src, "
                + "{credentials: 'include'});"
                + "if (!response.ok) throw new Error('HTTP ' + response.status);"
                + "const bytes = new Uint8Array(await response.arrayBuffer());"
                + "let binary = '';"
                + "for (let offset = 0; offset < bytes.length; offset += 8192) {"
                + "binary += String.fromCharCode(...bytes.subarray(offset, offset + 8192));"
                + "}"
                + "return btoa(binary);"
                + "}");
        String base64 = raw == null ? null : String.valueOf(raw);
        if (StringUtils.isBlank(base64)) {
            throw new IllegalStateException("微信视频号登录二维码返回了空图片");
        }
        try {
            Files.write(targetFile.toPath(), Base64.getDecoder().decode(base64));
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("保存微信视频号登录二维码原图失败", e);
        }
    }

    static boolean isLoginQrFrame(String url) {
        if (url == null) {
            return false;
        }
        HttpUrl parsed = HttpUrl.parse(url);
        return parsed != null && LOGIN_QR_FRAME_HOST.equals(parsed.host())
                && LOGIN_QR_FRAME_PATH.equals(parsed.encodedPath());
    }

    private void persistStorageState() {
        context.storageState(new BrowserContext.StorageStateOptions()
                .setPath(Paths.get(storageStateFile.getAbsolutePath())));
    }

    private void resetBootstrapState() {
        uploadParams.set(null);
        environmentInfo.set(null);
        controlTemplate.set(null);
    }

    private void markProfileReady() {
        if (profileReadyFile.isFile()) {
            return;
        }
        try {
            Files.write(profileReadyFile.toPath(),
                    "server-profile-ready".getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("无法标记微信视频号服务器浏览器目录已初始化: "
                    + profileReadyFile.getAbsolutePath(), e);
        }
    }

    private void notifyText(String message) {
        try {
            msgSendService.sendText(message);
        } catch (RuntimeException e) {
            log.error("Failed to send WeChat Channels login notification: {}", message, e);
        }
    }

    private void notifyImage(File imageFile) {
        try {
            msgSendService.sendImage(imageFile);
        } catch (RuntimeException e) {
            log.error("Failed to send WeChat Channels login QR image: {}",
                    imageFile.getAbsolutePath(), e);
        }
    }

    static boolean isLoginPage(String url) {
        return url != null && url.contains("/login");
    }

    private void waitForBootstrap() {
        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1);
        while (System.currentTimeMillis() < deadline) {
            if (isLoginPage(page.url())) {
                waitForQrLoginIfRequired();
                enterCreatePage();
                deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1);
                continue;
            }
            if (uploadParams.get() != null && environmentInfo.get() != null
                    && controlTemplate.get() != null) {
                if (environmentInfo.get().asyncClipPostSwitch != 1) {
                    throw new IllegalStateException("当前账号未启用已抓包验证的异步裁剪发布链路");
                }
                return;
            }
            page.waitForTimeout(500);
        }
        throw new IllegalStateException("等待微信视频号上传参数超时，登录态可能已失效");
    }

    private void captureBootstrapResponse(Response response) {
        try {
            String url = response.url();
            if (!url.contains(UPLOAD_PARAMS_PATH) && !url.contains(AUTH_DATA_PATH)) {
                return;
            }
            JSONObject root = JSON.parseObject(response.text());
            if (root == null || root.getIntValue("errCode") != 0) {
                return;
            }
            JSONObject data = root.getJSONObject("data");
            if (data == null) {
                return;
            }
            if (url.contains(UPLOAD_PARAMS_PATH)) {
                String authKey = data.getString("authKey");
                String uin = data.getString("uin");
                if (StringUtils.isNoneBlank(authKey, uin)) {
                    uploadParams.compareAndSet(null, new UploadParams(authKey, uin,
                            data.getIntValue("appType"), data.getIntValue("videoFileType"),
                            data.getIntValue("pictureFileType"), data.getIntValue("scene")));
                }
                return;
            }
            JSONObject envInfo = data.getJSONObject("envInfo");
            JSONObject switchInfo = data.getJSONObject("switchInfo");
            if (envInfo == null || StringUtils.isBlank(envInfo.getString("cdnHost"))) {
                return;
            }
            List<String> hosts = new ArrayList<>();
            if (envInfo.getJSONArray("cdnHostList") != null) {
                hosts.addAll(envInfo.getJSONArray("cdnHostList").toJavaList(String.class));
            }
            if (!hosts.contains(envInfo.getString("cdnHost"))) {
                hosts.add(0, envInfo.getString("cdnHost"));
            }
            environmentInfo.compareAndSet(null, new EnvironmentInfo(envInfo.getString("cdnHost"),
                    hosts, switchInfo == null ? 0 : switchInfo.getIntValue("asyncClipPostSwitch"),
                    switchInfo == null ? 0 : switchInfo.getIntValue("enableAllowAstraThumbCover"),
                    switchInfo == null ? 0 : switchInfo.getIntValue("enablePostShareCoverUrl")));
        } catch (RuntimeException ignored) {
            // The application may cancel duplicate bootstrap requests while remounting the route.
        }
    }

    private void captureControlTemplate(Request request) {
        try {
            if (!"POST".equalsIgnoreCase(request.method())
                    || !request.url().contains(CONTROL_TEMPLATE_PATH)) {
                return;
            }
            JSONObject body = JSON.parseObject(request.postData());
            Map<String, String> headers = request.allHeaders();
            HttpUrl url = HttpUrl.parse(request.url());
            if (body == null || url == null
                    || StringUtils.isBlank(headers.get("x-wechat-uin"))) {
                return;
            }
            JSONObject base = new JSONObject(true);
            copyBaseField(body, base, "_log_finder_uin");
            copyBaseField(body, base, "_log_finder_id");
            copyBaseField(body, base, "rawKeyBuff");
            copyBaseField(body, base, "pluginSessionId");
            copyBaseField(body, base, "scene");
            copyBaseField(body, base, "reqScene");
            controlTemplate.compareAndSet(null, new ControlTemplate(url.queryParameter("_aid"),
                    StringUtils.defaultIfBlank(url.queryParameter("_pageUrl"), CREATE_FRAME_URL),
                    headers.get("x-wechat-uin"), headers.get("finger-print-device-id"), base));
        } catch (RuntimeException ignored) {
            // Wait for the next natural request if this one was incomplete.
        }
    }

    private Frame findCreateFrame() {
        for (Frame frame : page.frames()) {
            if (frame.url().contains("/micro/content/post/create")) {
                return frame;
            }
        }
        return null;
    }

    private static void copyBaseField(JSONObject source, JSONObject target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private static String requestId() {
        StringBuilder suffix = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            suffix.append(Integer.toHexString(RANDOM.nextInt(16)));
        }
        return Long.toHexString(System.currentTimeMillis() / 1_000L) + "-" + suffix;
    }

    @Value
    private static class UploadParams {
        String authKey;
        String uin;
        int appType;
        int videoFileType;
        int pictureFileType;
        int scene;
    }

    @Value
    private static class EnvironmentInfo {
        String cdnHost;
        List<String> cdnHostList;
        int asyncClipPostSwitch;
        int enableAllowAstraThumbCover;
        int enablePostShareCoverUrl;
    }

    @Value
    private static class ControlTemplate {
        String aid;
        String pageUrl;
        String wechatUin;
        String fingerprint;
        JSONObject baseBody;
    }

    @Value
    public static class UploadContext {
        @ToString.Exclude
        String authKey;
        @ToString.Exclude
        String uin;
        int appType;
        int videoFileType;
        int pictureFileType;
        int scene;
        String cdnHost;
        List<String> cdnHostList;
        int asyncClipPostSwitch;
        int enableAllowAstraThumbCover;
        int enablePostShareCoverUrl;
    }
}
