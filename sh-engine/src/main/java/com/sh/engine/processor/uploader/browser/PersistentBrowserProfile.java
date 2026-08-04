package com.sh.engine.processor.uploader.browser;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.SameSiteAttribute;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Owns the persistent Chromium profile used by a web uploader. It migrates the old Playwright
 * storageState JSON once, and imports it again only when an externally replaced file is detected.
 */
@Slf4j
public final class PersistentBrowserProfile {
    private static final String PROFILE_READY_FILE_NAME = ".streamer-record-profile-ready";

    private final File storageStateFile;
    private final File profileDirectory;
    private final File profileReadyFile;
    private final String platformName;

    public PersistentBrowserProfile(File storageStateFile,
                                    String profileDirectoryName,
                                    String platformName) {
        if (storageStateFile == null) {
            throw new IllegalStateException(platformName + "登录态文件路径不能为空");
        }
        File accountDirectory = storageStateFile.getAbsoluteFile().getParentFile();
        if (accountDirectory == null
                || (!accountDirectory.isDirectory() && !accountDirectory.mkdirs())) {
            throw new IllegalStateException("无法创建" + platformName + "账号目录: "
                    + (accountDirectory == null ? "null" : accountDirectory.getAbsolutePath()));
        }
        this.profileDirectory = new File(accountDirectory, profileDirectoryName);
        if (!profileDirectory.isDirectory() && !profileDirectory.mkdirs()) {
            throw new IllegalStateException("无法创建" + platformName + "浏览器目录: "
                    + profileDirectory.getAbsolutePath());
        }
        this.storageStateFile = storageStateFile;
        this.profileReadyFile = new File(profileDirectory, PROFILE_READY_FILE_NAME);
        this.platformName = platformName;
    }

    public Path getProfilePath() {
        return Paths.get(profileDirectory.getAbsolutePath());
    }

    public File resolveAccountFile(String fileName) {
        return new File(storageStateFile.getAbsoluteFile().getParentFile(), fileName);
    }

    public void importLegacyStorageStateIfRequired(BrowserContext context) {
        if (!storageStateFile.isFile() || markerMatchesStorageState()) {
            return;
        }
        JSONObject storageState;
        try {
            byte[] content = Files.readAllBytes(storageStateFile.toPath());
            storageState = JSON.parseObject(new String(content, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("Existing {} storage state cannot be imported; continue with the server "
                    + "browser profile: {}", platformName, storageStateFile.getAbsolutePath(), e);
            return;
        }
        if (storageState == null) {
            return;
        }

        List<Cookie> cookies = readCookies(storageState);
        if (!cookies.isEmpty()) {
            context.addCookies(cookies);
        }
        installLegacyLocalStorage(context, storageState);
        log.info("Imported existing {} storage state into server browser profile: {}",
                platformName, storageStateFile.getAbsolutePath());
    }

    public void persistStorageState(BrowserContext context) {
        context.storageState(new BrowserContext.StorageStateOptions()
                .setPath(Paths.get(storageStateFile.getAbsolutePath())));
        if (profileReadyFile.isFile()) {
            writeProfileMarker();
        }
    }

    public void markReady() {
        writeProfileMarker();
    }

    private boolean markerMatchesStorageState() {
        try {
            String marker = new String(Files.readAllBytes(profileReadyFile.toPath()),
                    StandardCharsets.UTF_8).trim();
            return StringUtils.isNotBlank(marker) && marker.equals(storageStateHash());
        } catch (IOException e) {
            return false;
        }
    }

    private void writeProfileMarker() {
        try {
            Files.write(profileReadyFile.toPath(), storageStateHash().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("无法标记" + platformName + "服务器浏览器目录已初始化: "
                    + profileReadyFile.getAbsolutePath(), e);
        }
    }

    private String storageStateHash() throws IOException {
        byte[] content = Files.readAllBytes(storageStateFile.toPath());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 Java 环境不支持 SHA-256", e);
        }
    }

    private List<Cookie> readCookies(JSONObject storageState) {
        List<Cookie> cookies = new ArrayList<>();
        JSONArray cookieArray = storageState.getJSONArray("cookies");
        if (cookieArray == null) {
            return cookies;
        }
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
            applySameSite(cookie, source.getString("sameSite"));
            cookies.add(cookie);
        }
        return cookies;
    }

    private void applySameSite(Cookie cookie, String sameSite) {
        if (StringUtils.isBlank(sameSite)) {
            return;
        }
        try {
            cookie.setSameSite(SameSiteAttribute.valueOf(sameSite.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            log.warn("Ignore unsupported SameSite value while importing {} cookie: {}",
                    platformName, sameSite);
        }
    }

    private static void installLegacyLocalStorage(BrowserContext context, JSONObject storageState) {
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
                    + "const importKey = '__streamer_record_storage_imported__'; "
                    + "if (sessionStorage.getItem(importKey) === '1') return; "
                    + "for (const item of items) localStorage.setItem(item.name, item.value); "
                    + "sessionStorage.setItem(importKey, '1'); })();");
        }
    }
}
