package com.sh.engine.processor.uploader;

import com.alibaba.fastjson.TypeReference;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.Cookie;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.manager.CacheManager;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.utils.FileStoreUtil;
import com.sh.engine.constant.UploadPlatformEnum;
import com.sh.engine.processor.uploader.meta.WechatVideoMetaData;
import com.sh.message.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 腾讯视频号上传
 * 三方工具：http://loong.videostui.com
 *
 * @Author : caiwen
 * @Date: 2024/10/2
 */
@Slf4j
@Component
public class WechatVideoV2Uploader extends Uploader {
    @Resource
    private CacheManager cacheManager;
    @Resource
    private MsgSendService msgSendService;
    @Value("${playwright.headless}")
    private boolean headless;

    private static final String IS_SETTING_UP = "wechat_set_up_flag";

    @Override
    public String getType() {
        return UploadPlatformEnum.WECHAT_VIDEO_V2.getType();
    }

    @Override
    public void setUp() {
        if (cacheManager.hasKey(IS_SETTING_UP)) {
            throw new StreamerRecordException(ErrorEnum.UPLOAD_COOKIES_IS_FETCHING);
        }

        cacheManager.set(IS_SETTING_UP, 1, 300, TimeUnit.SECONDS);
        try {
            if (!checkAccountValid()) {
                genCookies();
            }
        } finally {
            cacheManager.delete(IS_SETTING_UP);
        }

    }

    private void genCookies() {
        File accountFile = getAccoutFile();
        String phoneNumber = ConfigFetcher.getInitConfig().getPhoneNumber();
        String password = ConfigFetcher.getInitConfig().getPassword();

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.webkit().launch(buildOptions());

            BrowserContext context = browser.newContext();
            Page page = context.newPage();

            // 加载首页
            page.navigate("http://loong.videostui.com/#/login");
            page.getByPlaceholder("请输入手机号登录").click();
            page.getByPlaceholder("请输入手机号登录").fill(phoneNumber);
            page.getByPlaceholder("请输入密码").click();
            page.getByPlaceholder("请输入密码").fill(password);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("登 录")).click();

            // 等待用户操作并刷新页面
            page.waitForTimeout(7000);

            // 打开平台页面获取用户信息
            Page platformPage = context.newPage();
            platformPage.navigate("http://loong.videostui.com/#/publish");
            platformPage.waitForURL("http://loong.videostui.com/#/publish");

            List<Cookie> cookies = context.cookies();
            Map<String, String> userInfo = null;
            if (CollectionUtils.isNotEmpty(cookies)) {
                // 保存cookie到文件
                context.storageState(new BrowserContext.StorageStateOptions().setPath(Paths.get(accountFile.getAbsolutePath())));
                log.info("gen cookies for {} success", getType());
            }

            // 关闭浏览器
            context.close();
            browser.close();
        } catch (Exception e) {
            log.error("Failed to genCookies for tencent", e);
        }
    }

    @Override
    public boolean upload(String recordPath) throws Exception {
        File targetFile = new File(recordPath, "highlight.mp4");
        if (!targetFile.exists()) {
            // 不存在也当作上传成功
            return true;
        }

        // cookies有效性检测
        setUp();

        // 真正上传
        return doUpload(recordPath);
    }

    private boolean checkAccountValid() {
        File accountFile = getAccoutFile();
        if (!accountFile.exists()) {
            return false;
        }

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.webkit().launch(buildOptions());
            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setStorageStatePath(Paths.get(accountFile.getAbsolutePath())));

            Page page = context.newPage();
            page.navigate("http://loong.videostui.com/#/publish");
            page.waitForTimeout(5000);
            boolean isValid = page.getByText("发布设置").count() > 0;

            context.close();
            browser.close();

            if (isValid) {
                log.info("cookies valid for wechat video");
            } else {
                log.info("cookies invalid for wechat video");
            }

            return isValid;
        } catch (Exception e) {
            log.error("wechat video fuck", e);
            return false;
        }
    }

    private boolean doUpload(String recordPath) {
        File targetFile = new File(recordPath, "highlight.mp4");
        String workFilePath = targetFile.getAbsolutePath();
        String cookiesPath = getAccoutFile().getAbsolutePath();
        WechatVideoMetaData metaData = FileStoreUtil.loadFromFile(
                new File(recordPath, UploaderFactory.getMetaFileName(getType())),
                new TypeReference<WechatVideoMetaData>() {
                });

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.webkit().launch(buildOptions());
            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setStorageStatePath(Paths.get(cookiesPath)));

            Page page = context.newPage();
            page.navigate("http://loong.videostui.com/#/publish");
            log.info("Video begin uploading, type: {}, path: {}", getType(), workFilePath);

            page.waitForURL("http://loong.videostui.com/#/publish");

            // 上传文件
            uploadVideo(page, workFilePath);

            // 添加短标题
            addShortTitle(page, metaData.getTitle());

            // 添加封面
            addThumbnail(page, metaData.getPreViewFilePath());

            // 添加视频标签
            addTitleTags(page, workFilePath, metaData);

            // 检查是否上传视频完成
            detectUploadStatus(page, workFilePath);

            // 选择账号
            chooseAccount(page);

            // 发布视频
            publishVideo(page, workFilePath);

            // Save updated cookies
            context.storageState(new BrowserContext.StorageStateOptions().setPath(Paths.get(cookiesPath)));
            log.info("update wechatVideo cookies success, video: {}", workFilePath);
            page.waitForTimeout(2000);

            context.close();
            browser.close();

            return true;
        } catch (Exception e) {
            log.error("wechat video fuck", e);
            return false;
        }
    }

    private void uploadVideo(Page page, String workFilePath) {
        page.waitForTimeout(5000);
        page.locator(".ant-upload.ant-upload-select.ant-upload-select-text input[type='file']").setInputFiles(Paths.get(workFilePath));
    }

    private void addTitleTags(Page page, String workFilePath, WechatVideoMetaData metaData) {
        for (String tag : metaData.getTags()) {
            page.getByText("+ 添加话题标签").click();
            page.keyboard().type("#" + tag);
            page.keyboard().press("Enter");
        }
        log.info("add tag success, type: {}, path: {}, tags: {}", getType(), workFilePath, metaData.getTags());
    }

    private void detectUploadStatus(Page page, String workFilePath) {
        int errorCnt = 0;
        while (errorCnt < 10) {
            try {
                // 等待上传完成小图标
                Locator finishCircle = page.locator(".anticon.anticon-check-circle");
                if (finishCircle.count() > 0) {
                    log.info("video upload finish for wechat, path: {}", workFilePath);
                    page.waitForTimeout(2000);
                    break;
                } else {
                    String progress = page.locator("span.ant-progress-text").textContent();
                    log.info("video is uploading for wechat, path: {}, progress: {}", workFilePath, progress);
                    page.waitForTimeout(2000);
                }
            } catch (Exception e) {
                log.info("video is uploading for wechat, path: {}", workFilePath, e);
                errorCnt++;
                page.waitForTimeout(2000);
            }
        }
    }

    private void chooseAccount(Page page) {
        page.getByText("+ 添加帐号").click();
        page.waitForTimeout(2000);

        page.locator(".ant-checkbox-input").nth(1).click();
        page.getByText("确 定").click();
    }

//    private void addToCollection(Page page, WechatVideoMetaData metaData) {
//        String streamerName = StreamerInfoHolder.getCurStreamerName();
//        String collName = streamerName + "直播录像";
//        page.locator(".post-album-wrap").click();
//
//        page.locator(".option-item.active .name").all().stream().map(loc -> loc.textContent()).collect(Collectors.toList())
//    }

    private void handleUploadError(Page page, String workFilePath) {
        // 点击删除按钮
        page.locator("div.media-status-content div.tag-inner:has-text(\"删除\")").click();
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("确认").setExact(true)).click();

        // 上传新文件
        page.locator("input[type=\"file\"]").setInputFiles(Paths.get(workFilePath));
    }


    private void addShortTitle(Page page, String title) {
        Locator titleLoc = page.getByPlaceholder("请输入视频标题");
        titleLoc.click();
        titleLoc.fill(formatStrForShortTitle(title));
    }

    private void addThumbnail(Page page, String previewPath) {
        // 定位到上传区域并点击
        page.locator(".ant-upload.ant-upload-select.ant-upload-select-picture-card input[type='file']").setInputFiles(Paths.get(previewPath));
        page.waitForTimeout(2000);
    }

    public void publishVideo(Page page, String workFilePath) {
        page.getByText("一键发布").click();
        for (int i = 0; i < 5; i++) {
            page.waitForTimeout(2000);
            snapshot(page);
        }

        page.waitForURL("http://loong.videostui.com/#/content", new Page.WaitForURLOptions().setTimeout(30000));
        log.info("video upload success, path: {}", workFilePath);
    }

    public String formatStrForShortTitle(String originTitle) {
        // 定义允许的特殊字符
        String allowedSpecialChars = "《》“”:+?%°";
        StringBuilder filteredChars = new StringBuilder();

        // 移除不允许的特殊字符
        for (char c : originTitle.toCharArray()) {
            if (Character.isLetterOrDigit(c) || allowedSpecialChars.indexOf(c) != -1) {
                filteredChars.append(c);
            } else if (c == ',') {
                // 将逗号替换为空格
                filteredChars.append(' ');
            }
        }

        String formattedString = filteredChars.toString();
        // 调整字符串长度
        if (formattedString.length() > 16) {
            // 截断字符串
            formattedString = formattedString.substring(0, 16);
        } else if (formattedString.length() < 6) {
            // 使用空格来填充字符串
            StringBuilder padding = new StringBuilder(formattedString);
            for (int i = formattedString.length(); i < 6; i++) {
                padding.append(' ');
            }
            formattedString = padding.toString();
        }
        return formattedString;
    }

    private BrowserType.LaunchOptions buildOptions() {
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                .setHeadless(headless);
//                .setArgs(Arrays.asList("--no-sandbox", "--disable-setuid-sandbox", "--enable-font-antialiasing"));

        String httpProxy = ConfigFetcher.getInitConfig().getHttpProxy();
        if (StringUtils.isNotBlank(httpProxy)) {
            options.setProxy(httpProxy);
        }
        return options;
    }

}
