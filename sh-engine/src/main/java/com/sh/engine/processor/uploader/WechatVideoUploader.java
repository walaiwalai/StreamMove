//package com.sh.engine.processor.uploader;
//
//import com.alibaba.fastjson.TypeReference;
//import com.google.common.collect.ImmutableMap;
//import com.microsoft.playwright.*;
//import com.microsoft.playwright.options.AriaRole;
//import com.microsoft.playwright.options.Cookie;
//import com.sh.config.exception.ErrorEnum;
//import com.sh.config.exception.StreamerRecordException;
//import com.sh.config.manager.CacheManager;
//import com.sh.config.manager.ConfigFetcher;
//import com.sh.config.utils.FileStoreUtil;
//import com.sh.config.utils.PictureFileUtil;
//import com.sh.engine.constant.UploadPlatformEnum;
//import com.sh.engine.processor.uploader.meta.WechatVideoMetaData;
//import com.sh.message.service.MsgSendService;
//import lombok.extern.slf4j.Slf4j;
//import org.apache.commons.collections4.CollectionUtils;
//import org.apache.commons.lang3.StringUtils;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.stereotype.Component;
//
//import javax.annotation.Resource;
//import java.io.File;
//import java.nio.file.Paths;
//import java.util.List;
//import java.util.Map;
//import java.util.concurrent.TimeUnit;
//
///**
// * 腾讯视频号上传
// *
// * @Author : caiwen
// * @Date: 2024/10/2
// */
//@Slf4j
//@Component
//public class WechatVideoUploader extends Uploader {
//    @Resource
//    private CacheManager cacheManager;
//    @Resource
//    private MsgSendService msgSendService;
//    @Value("${playwright.headless}")
//    private boolean headless;
//    @Value("${playwright.execute.path}")
//    private String executePath;
//
//    private static final String IS_SETTING_UP = "wechat_set_up_flag";
//
//    @Override
//    public String getType() {
//        return UploadPlatformEnum.WECHAT_VIDEO.getType();
//    }
//
//    @Override
//    public void setUp() {
//        if (cacheManager.hasKey(IS_SETTING_UP)) {
//            throw new StreamerRecordException(ErrorEnum.UPLOAD_COOKIES_IS_FETCHING);
//        }
//
//        cacheManager.set(IS_SETTING_UP, 1, 300, TimeUnit.SECONDS);
//        try {
//            if (!checkAccountValid()) {
//                genCookies();
//            }
//        } finally {
//            cacheManager.delete(IS_SETTING_UP);
//        }
//
//    }
//
//    private void genCookies() {
//        File accountFile = getAccoutFile();
//
//        try (Playwright playwright = Playwright.create()) {
//            Browser browser = playwright.chromium().launch(buildOptions());
//
//            BrowserContext context = browser.newContext();
//            Page page = context.newPage();
//
//            // 加载首页
//            page.navigate("https://channels.weixin.qq.com/platform/login-for-iframe?dark_mode=true&host_type=1");
//            page.locator(".qrcode").click();
//
//            // 获取二维码图片的 src 属性
//            String imgElement = page.locator("img.qrcode").getAttribute("src");
//            File qrCodeFile = PictureFileUtil.saveBase64Image(imgElement, UploaderFactory.getQrCodeFileName(getType()));
//            msgSendService.sendText("微信视频号需要扫码验证，扫描下方二维码");
//            msgSendService.sendImage(qrCodeFile);
//
//            // 检查扫码状态
//            Locator successImgDiv = page.locator(".mask").first();
//            int num = 0;
//            while (num++ < 13) {
//                page.waitForTimeout(3000);
//                String successShowClass = successImgDiv.getAttribute("class");
//                if (successShowClass != null && successShowClass.contains("show")) {
//                    log.info("scan qrCode for {} success", getType());
//                    break;
//                } else {
//                    log.info("waiting for scanning qrCode for {}..., retry: {}/13", getType(), num);
//                }
//            }
//
//            // 等待用户操作并刷新页面
//            page.waitForTimeout(7000);
//
//            // 打开平台页面获取用户信息
//            Page platformPage = context.newPage();
//            platformPage.navigate("https://channels.weixin.qq.com/platform");
//            platformPage.waitForURL("https://channels.weixin.qq.com/platform");
//
//            List<Cookie> cookies = context.cookies();
//            Map<String, String> userInfo = null;
//            if (CollectionUtils.isNotEmpty(cookies)) {
//                // 获取用户信息
//                String thirdId = platformPage.locator("span.finder-uniq-id").nth(0).innerText();
//                userInfo = ImmutableMap.of(
//                        "account_id", thirdId,
//                        "username", platformPage.locator("h2.finder-nickname").nth(0).innerText(),
//                        "avatar", platformPage.locator("img.avatar").nth(0).getAttribute("src")
//                );
//
//                // 保存cookie到文件
//                context.storageState(new BrowserContext.StorageStateOptions().setPath(Paths.get(accountFile.getAbsolutePath())));
//                log.info("gen cookies for {} success", getType());
//            }
//
//            // 关闭浏览器
//            context.close();
//            browser.close();
//        } catch (Exception e) {
//            log.error("Failed to genCookies for tencent", e);
//        }
//    }
//
//    @Override
//    public boolean upload(String recordPath) throws Exception {
//        File targetFile = new File(recordPath, "highlight.mp4");
//        if (!targetFile.exists()) {
//            // 不存在也当作上传成功
//            return true;
//        }
//
//        // cookies有效性检测
//        setUp();
//
//        // 真正上传
//        return doUpload(recordPath);
//    }
//
//    private boolean checkAccountValid() {
//        File accountFile = getAccoutFile();
//        if (!accountFile.exists()) {
//            return false;
//        }
//
//        try (Playwright playwright = Playwright.create()) {
//            Browser browser = playwright.chromium().launch(buildOptions());
//            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
//                    .setStorageStatePath(Paths.get(accountFile.getAbsolutePath())));
//
//            Page page = context.newPage();
//            page.navigate("https://channels.weixin.qq.com/platform/post/create");
//            page.waitForTimeout(5000);
//            boolean isValid = page.getByText("通知中心").count() > 0;
//
//            context.close();
//            browser.close();
//
//            if (isValid) {
//                log.info("cookies valid for wechat video");
//            } else {
//                log.info("cookies invalid for wechat video");
//            }
//
//            return isValid;
//        } catch (Exception e) {
//            log.error("wechat video fuck", e);
//            return false;
//        }
//    }
//
//    private boolean doUpload(String recordPath) {
//        File targetFile = new File(recordPath, "highlight.mp4");
//        String workFilePath = targetFile.getAbsolutePath();
//        String cookiesPath = getAccoutFile().getAbsolutePath();
//        WechatVideoMetaData metaData = FileStoreUtil.loadFromFile(
//                new File(recordPath, UploaderFactory.getMetaFileName(getType())),
//                new TypeReference<WechatVideoMetaData>() {
//                });
//
//        try (Playwright playwright = Playwright.create()) {
//            Browser browser = playwright.chromium().launch(buildOptions());
//            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
//                    .setStorageStatePath(Paths.get(cookiesPath)));
//
//            Page page = context.newPage();
//            page.navigate("https://channels.weixin.qq.com/platform/post/create");
//            log.info("Video begin uploading, type: {}, path: {}", getType(), workFilePath);
//            page.pause();
//
//            page.waitForURL("https://channels.weixin.qq.com/platform/post/create");
//            snapshot(page);
//            // 上传文件
//            uploadVideo(page, workFilePath);
//            snapshot(page);
//
//            // 添加视频标签
//            addTitleTags(page, workFilePath, metaData);
//            snapshot(page);
//
//            // 增加原创
//            addOriginal(page, metaData);
//
//            // 检查是否上传视频完成
//            detectUploadStatus(page, workFilePath, metaData);
//
//            // 定时发布先不搞
//
//            // 选择发布集合
//
//            // 添加短标题
//            addShortTitle(page, metaData.getTitle());
//            // 发布视频
//            publishVideo(page, workFilePath);
//
//            // Save updated cookies
//            context.storageState(new BrowserContext.StorageStateOptions().setPath(Paths.get(cookiesPath)));
//            log.info("update wechatVideo cookies success, video: {}", workFilePath);
//            page.waitForTimeout(2000);
//
//            context.close();
//            browser.close();
//
//            return true;
//        } catch (Exception e) {
//            log.error("wechat video fuck", e);
//            return false;
//        }
//    }
//
//    private void uploadVideo(Page page, String workFilePath) {
//        page.waitForTimeout(5000);
//        page.locator("input[type='file'][accept*='video']").setInputFiles(Paths.get(workFilePath));
//    }
//
//    private void addTitleTags(Page page, String workFilePath, WechatVideoMetaData metaData) {
//        page.locator("div.input-editor").click();
//        page.keyboard().type(metaData.getTitle());
//        for (String tag : metaData.getTags()) {
//            page.keyboard().press("Enter");
//            page.keyboard().type("#" + tag);
//            page.keyboard().press("Space");
//        }
//        log.info("add tag success, type: {}, path: {}, tags: {}", getType(), workFilePath, metaData.getTags());
//    }
//
//    private void addOriginal(Page page, WechatVideoMetaData metaData) {
//        // 检查是否存在 "视频为原创" 复选框
//        if (page.getByLabel("视频为原创").count() > 0) {
//            page.getByLabel("视频为原创").check();
//        }
//
//        // 检查 "我已阅读并同意 《视频号原创声明使用条款》" 是否存在并可见
//        boolean labelLocator = page.locator("label:has-text(\"我已阅读并同意 《视频号原创声明使用条款》\")").isVisible();
//        if (labelLocator) {
//            page.getByLabel("我已阅读并同意 《视频号原创声明使用条款》").check();
//            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("声明原创")).click();
//        }
//
//        // 微信更新：可能出现新的选择页面
//        if (page.locator("div.label span:has-text(\"声明原创\")").count() > 0 && metaData.getCategory() != null) {
//            // 检查是否可用并点击复选框
//            if (!page.locator("div.declare-original-checkbox input.ant-checkbox-input").isDisabled()) {
//                page.locator("div.declare-original-checkbox input.ant-checkbox-input").click();
//
//                // 再次检查是否已选中
//                if (page.locator("div.declare-original-dialog label.ant-checkbox-wrapper.ant-checkbox-wrapper-checked:visible").count() == 0) {
//                    page.locator("div.declare-original-dialog input.ant-checkbox-input:visible").click();
//                }
//            }
//
//            // 选择原创类型
//            if (page.locator("div.original-type-form > div.form-label:has-text(\"原创类型\"):visible").count() > 0) {
//                page.locator("div.form-content:visible").click();
//                page.locator(String.format("div.form-content:visible ul.weui-desktop-dropdown__list li.weui-desktop-dropdown__list-ele:has-text(\"%s\")", metaData.getCategory())).first().click();
//                page.waitForTimeout(1000);
//            }
//
//            // 再次点击 "声明原创" 按钮
//            if (page.locator("button:has-text(\"声明原创\"):visible").count() > 0) {
//                page.locator("button:has-text(\"声明原创\"):visible").click();
//            }
//        }
//    }
//
//    private void detectUploadStatus(Page page, String workFilePath, WechatVideoMetaData metaData) {
//        int errorCnt = 0;
//        while (errorCnt < 10) {
//            try {
//                // 匹配删除按钮，代表视频上传完毕
//                snapshot(page);
//                String buttonInfo = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("发表")).getAttribute("class");
//                if (!buttonInfo.contains("weui-desktop-btn_disabled")) {
//                    log.info("video upload finish for wechat, path: {}", workFilePath);
//                    page.waitForTimeout(2000);
//
//                    String previewButtonInfo = page.locator("div.finder-tag-wrap.btn:has-text(\"更换封面\")").getAttribute("class");
//                    if (!previewButtonInfo.contains("disabled")) {
//                        page.locator("div.finder-tag-wrap.btn:has-text(\"更换封面\")").click();
//                        page.locator("input[type='file'][accept*='image']").setInputFiles(Paths.get(metaData.getPreViewFilePath()));
//
//                        page.waitForTimeout(2000);
//                        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("确定")).click();
//                        page.waitForTimeout(1000);
//                        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("确认")).click();
//                        break;
//                    }
//                } else {
//                    String progress = page.locator("span.ant-progress-text").textContent();
//                    log.info("video is uploading for wechat, path: {}, progress: {}", workFilePath, progress);
//                    page.waitForTimeout(2000);
//                    // 出错了视频出错
//                    if (page.locator("div.status-msg.error").count() > 0 && page.locator("div.media-status-content div.tag-inner:has-text(\"删除\")").count() > 0) {
//                        log.info("video upload fail for wechat, path: {}", workFilePath);
//                        errorCnt++;
//                        handleUploadError(page, workFilePath);
//                    }
//                }
//            } catch (Exception e) {
//                log.info("video is uploading for wechat, path: {}", workFilePath, e);
//                errorCnt++;
//                page.waitForTimeout(2000);
//            }
//        }
//    }
//
////    private void addToCollection(Page page, WechatVideoMetaData metaData) {
////        String streamerName = StreamerInfoHolder.getCurStreamerName();
////        String collName = streamerName + "直播录像";
////        page.locator(".post-album-wrap").click();
////
////        page.locator(".option-item.active .name").all().stream().map(loc -> loc.textContent()).collect(Collectors.toList())
////    }
//
//    private void handleUploadError(Page page, String workFilePath) {
//        // 点击删除按钮
//        page.locator("div.media-status-content div.tag-inner:has-text(\"删除\")").click();
//        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("确认").setExact(true)).click();
//
//        // 上传新文件
//        page.locator("input[type=\"file\"]").setInputFiles(Paths.get(workFilePath));
//    }
//
//
//    private void addShortTitle(Page page, String title) {
//        Locator shortTitleElement = page.getByText("短标题", new Page.GetByTextOptions().setExact(true))
//                .locator("..")
//                .locator("xpath=following-sibling::div")
//                .locator("span input[type=\"text\"]");
//        if (shortTitleElement.count() > 0) {
//            String shortTitle = formatStrForShortTitle(title);
//            shortTitleElement.fill(shortTitle);
//        }
//    }
//
//    public void publishVideo(Page page, String workFilePath) {
//        int waitCnt = 0;
//        while (waitCnt < 10) {
//            try {
//                Locator publishButton = page.locator("div.form-btns button:has-text(\"发表\")");
//                if (publishButton.count() > 0) {
//                    publishButton.click();
//                }
//
//                // 等待URL跳转
//                page.waitForURL("https://channels.weixin.qq.com/platform/post/list", new Page.WaitForURLOptions().setTimeout(10000));
//                log.info("video upload success, path: {}", workFilePath);
//                break;
//            } catch (Exception e) {
//                String currentUrl = page.url();
//                if (currentUrl.contains("https://channels.weixin.qq.com/platform/post/list")) {
//                    log.info("video upload success, path: {}", workFilePath);
//                    break;
//                } else {
//                    waitCnt++;
//                    log.info("video is publishing for wechat, path: {}", workFilePath);
//                    page.screenshot(new Page.ScreenshotOptions().setFullPage(true));
//                    page.waitForTimeout(500);
//                }
//            }
//        }
//    }
//
//    public String formatStrForShortTitle(String originTitle) {
//        // 定义允许的特殊字符
//        String allowedSpecialChars = "《》“”:+?%°";
//        StringBuilder filteredChars = new StringBuilder();
//
//        // 移除不允许的特殊字符
//        for (char c : originTitle.toCharArray()) {
//            if (Character.isLetterOrDigit(c) || allowedSpecialChars.indexOf(c) != -1) {
//                filteredChars.append(c);
//            } else if (c == ',') {
//                // 将逗号替换为空格
//                filteredChars.append(' ');
//            }
//        }
//
//        String formattedString = filteredChars.toString();
//        // 调整字符串长度
//        if (formattedString.length() > 16) {
//            // 截断字符串
//            formattedString = formattedString.substring(0, 16);
//        } else if (formattedString.length() < 6) {
//            // 使用空格来填充字符串
//            StringBuilder padding = new StringBuilder(formattedString);
//            for (int i = formattedString.length(); i < 6; i++) {
//                padding.append(' ');
//            }
//            formattedString = padding.toString();
//        }
//        return formattedString;
//    }
//
//    private BrowserType.LaunchOptions buildOptions() {
//        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
//                .setHeadless(headless);
////                .setArgs(Arrays.asList("--no-sandbox", "--disable-setuid-sandbox", "--enable-font-antialiasing"));
//
//        String httpProxy = ConfigFetcher.getInitConfig().getHttpProxy();
//        if (StringUtils.isNotBlank(httpProxy)) {
//            options.setProxy(httpProxy);
//        }
//
//        if (StringUtils.isNotBlank(executePath)) {
//            options.setExecutablePath(Paths.get(executePath));
//        }
//        return options;
//    }
//
//}
