package com.sh.engine.processor.uploader.wechat;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import lombok.Value;
import lombok.ToString;
import okhttp3.HttpUrl;
import org.apache.commons.lang3.StringUtils;

import java.io.Closeable;
import java.io.File;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps the WeChat Channels creator page security context alive for small control-plane calls.
 * Video and cover bytes are uploaded by {@link WechatVideoStorageClient}, not by Playwright.
 */
public final class WechatVideoWebSession implements Closeable {
    private static final String ORIGIN = "https://channels.weixin.qq.com";
    private static final String PLATFORM_HOME = ORIGIN + "/platform";
    private static final String CREATE_FRAME_URL = ORIGIN + "/micro/content/post/create";
    private static final String UPLOAD_PARAMS_PATH = "/helper/helper_upload_params";
    private static final String AUTH_DATA_PATH = "/auth/auth_data";
    private static final String CONTROL_TEMPLATE_PATH = "/post/get_finder_post_comm_info";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final File storageStateFile;
    private final Playwright playwright;
    private final Browser browser;
    private final BrowserContext context;
    private final Page page;
    private final AtomicReference<UploadParams> uploadParams = new AtomicReference<>();
    private final AtomicReference<EnvironmentInfo> environmentInfo = new AtomicReference<>();
    private final AtomicReference<ControlTemplate> controlTemplate = new AtomicReference<>();

    public WechatVideoWebSession(File storageStateFile) {
        if (storageStateFile == null || !storageStateFile.isFile()) {
            throw new IllegalStateException("微信视频号登录态文件不存在: "
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
        page.setDefaultTimeout(20_000);
        page.onResponse(this::captureBootstrapResponse);
        page.onRequest(this::captureControlTemplate);

        try {
            page.navigate(PLATFORM_HOME, new Page.NavigateOptions().setTimeout(60_000));
            enterCreatePage();
            waitForBootstrap();
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

    private void enterCreatePage() {
        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1);
        RuntimeException lastError = null;
        while (System.currentTimeMillis() < deadline) {
            if (page.url().contains("/login")) {
                throw new IllegalStateException("微信视频号登录态已失效，请重新扫码生成 wechat-video-cookies.json");
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

    private void waitForBootstrap() {
        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1);
        while (System.currentTimeMillis() < deadline) {
            if (page.url().contains("/login")) {
                throw new IllegalStateException("微信视频号登录态已失效，请重新扫码生成 wechat-video-cookies.json");
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
