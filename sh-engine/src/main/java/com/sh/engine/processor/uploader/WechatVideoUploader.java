//package com.sh.engine.processor.uploader;
//
//import cn.hutool.extra.spring.SpringUtil;
//import com.alibaba.fastjson.TypeReference;
//import com.google.common.collect.ImmutableMap;
//import com.microsoft.playwright.*;
//import com.microsoft.playwright.options.AriaRole;
//import com.microsoft.playwright.options.Cookie;
//import com.sh.config.manager.CacheManager;
//import com.sh.config.utils.FileStoreUtil;
//import com.sh.config.utils.PictureFileUtil;
//import com.sh.engine.constant.UploadPlatformEnum;
//import com.sh.engine.processor.uploader.meta.DouyinWorkMetaData;
//import com.sh.engine.processor.uploader.meta.WechatVideoMetaData;
//import com.sh.message.service.MsgSendService;
//import lombok.extern.slf4j.Slf4j;
//import org.apache.commons.collections4.CollectionUtils;
//import org.apache.commons.lang3.StringUtils;
//import org.springframework.core.env.Environment;
//
//import java.io.File;
//import java.nio.file.Paths;
//import java.util.List;
//import java.util.Map;
//import java.util.concurrent.TimeUnit;
//
///**
// * 腾讯视频号上传
// * @Author : caiwen
// * @Date: 2024/10/2
// */
//@Slf4j
//@Component
//public class WechatVideoUploader extends Uploader{
//    private CacheManager cacheManager;
//    private MsgSendService msgSendService;
//    private boolean headless;
//
//    @Override
//    public String getType() {
//        return UploadPlatformEnum.WECHAT_VIDEO.getType();
//    }
//
//    @Override
//    public void init() {
//        cacheManager = SpringUtil.getBean(CacheManager.class);
//        msgSendService = SpringUtil.getBean(MsgSendService.class);
//        headless = SpringUtil.getBean(Environment.class).getProperty("playwright.headless", Boolean.class);
//    }
//
//    @Override
//    public void setUp() {
//        if (!checkAccountValid()) {
//            genCookies();
//        }
//    }
//
//    private void genCookies() {
//        try (Playwright playwright = Playwright.create()) {
//            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(headless));
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
//            msgSendService.sendText("需要扫码验证，扫描下方二维码");
//            msgSendService.sendImage(qrCodeFile);
//
//            // 检查扫码状态
//            Locator successImgDiv = page.locator(".mask").first();
//            int num = 0;
//            while (num ++ < 13) {
//                Thread.sleep(3000);
//                String successShowClass = successImgDiv.getAttribute("class");
//                if (successShowClass != null && successShowClass.contains("show")) {
//                    break;
//                }
//            }
//
//            // 等待用户操作并刷新页面
//            Thread.sleep(6000);
//            List<Cookie> cookies = context.cookies();
//            Map<String, String> userInfo = null;
//
//            if (CollectionUtils.isNotEmpty(cookies)) {
//                // 打开平台页面获取用户信息
//                Page platformPage = context.newPage();
//                platformPage.navigate("https://channels.weixin.qq.com/platform");
//                platformPage.waitForURL("https://channels.weixin.qq.com/platform");
//
//                // 获取用户信息
//                String thirdId = platformPage.locator("span.finder-uniq-id").nth(0).innerText();
//                userInfo = ImmutableMap.of(
//                        "account_id", thirdId,
//                        "username", platformPage.locator("h2.finder-nickname").nth(0).innerText(),
//                        "avatar", platformPage.locator("img.avatar").nth(0).getAttribute("src")
//                );
//
//                // 保存cookie到文件
//                String storageState = context.storageState();
//                cacheManager.set(getAccountKey(), storageState, 86400L, TimeUnit.SECONDS);
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
//    public boolean upload( String recordPath ) throws Exception {
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
//        doUpload(recordPath);
//        return true;
//    }
//
//    private boolean checkAccountValid() {
//        String storageInfo = cacheManager.get(getAccountKey());
//        if (StringUtils.isBlank(storageInfo)) {
//            return false;
//        }
//
//        try (Playwright playwright = Playwright.create()) {
//            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(headless));
//            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
//                    .setStorageState(storageInfo));
//
//            Page page = context.newPage();
//            page.navigate("https://channels.weixin.qq.com/platform/post/create");
//
//            try {
//                page.waitForSelector("div.title-name:has-text('视频号小店')", new Page.WaitForSelectorOptions().setTimeout(5000));
//                log.info("cookies invalid for tencent");
//
//                // Cookie 失效，删除缓存
//                cacheManager.delete(getAccountKey());
//                context.close();
//                browser.close();
//                return false;
//            } catch (PlaywrightException e) {
//                log.info("cookies valid for tencent");
//                context.close();
//                browser.close();
//                return true;
//            }
//        }
//    }
//
//    private void doUpload( String recordPath ) {
//        File targetFile = new File(recordPath, "highlight.mp4");
//        String workFilePath = targetFile.getAbsolutePath();
//        WechatVideoMetaData metaData = FileStoreUtil.loadFromFile(
//                new File(recordPath, UploaderFactory.getMetaFileName(getType())),
//                new TypeReference<WechatVideoMetaData>() {
//                });
//
//        String storageState = cacheManager.get(getAccountKey());
//        try (Playwright playwright = Playwright.create()) {
//            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(headless).setChannel("chrome"));
//            BrowserContext context = browser.newContext(new Browser.NewContextOptions().setStorageState(storageState));
//
//            Page page = context.newPage();
//            page.navigate("https://channels.weixin.qq.com/platform/post/create");
//            log.info("Video begin uploading, type: {}, path: {}", getType(), workFilePath);
//
//            page.waitForURL("https://channels.weixin.qq.com/platform/post/create");
//            // 上传文件
//            uploadVideo(page, workFilePath);
//
//            // 添加视频标签
//            addTitleTags(page, workFilePath, metaData);
//
//            // 增加原创
//            addOriginal(page, metaData);
//
//            // 检查是否上传视频完成
//
//            detectUploadStatus(page);
//
//            // 其他步骤...
//        } catch (Exception e) {
//            log.error("wechat video fuck", e);
//        }
//    }
//
//    private void uploadVideo(Page page, String workFilePath) {
//        Locator uploadDiv = page.locator("div.upload-content");
//        uploadDiv.click();
//        page.onFileChooser(fileChooser -> {
//            fileChooser.setFiles(Paths.get(workFilePath));
//        });
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
//    private void addOriginal( Page page, WechatVideoMetaData metaData) throws InterruptedException {
//        // 检查是否有 "声明后，作品将展示原创标记，有机会获得广告收入。" 标签
//        if (page.getByLabel("声明后，作品将展示原创标记，有机会获得广告收入。").count() > 0) {
//            page.getByLabel("声明后，作品将展示原创标记，有机会获得广告收入。").check();
//        }
//
//        // 检查 "我已阅读并同意《原创声明须知》和《使用条款》。如滥用声明，平台将驳回并予以相关处置。" 元素是否存在
//        boolean labelVisible = page.getByText("我已阅读并同意《原创声明须知》和《使用条款》。如滥用声明，平台将驳回并予以相关处置。").first().isVisible();
//        if (labelVisible) {
//            page.locator("div.declare-original-dialog input.ant-checkbox-input").first().check();
//        }
//
//        // 如果提供了原创分类，则选择原创分类
//        if (StringUtils.isBlank(metaData.getCategory())) {
//            if (page.locator("div.original-type-form > div.form-label:has-text(\"原创类型\"):visible").count() > 0) {
//                page.locator("div.form-content:visible").click();
//                page.locator(String.format("div.form-content:visible ul.weui-desktop-dropdown__list li.weui-desktop-dropdown__list-ele:has-text(\"%s\")",
//                        metaData.getCategory())).first().click();
//                Thread.sleep(1000);
//            }
//        }
//
//        // 检查 "声明原创" 按钮是否可用，如果可用则点击
//        String submitButtonClass = page.locator("role=button['声明原创']").getAttribute("class");
//        if (!submitButtonClass.contains("weui-desktop-btn_disabled")) {
//            page.locator("role=button['声明原创']").click();
//        }
//    }
//
//    private void detectUploadStatus(Page page, String workFilePath, WechatVideoMetaData metaData) throws InterruptedException {
//        while (true) {
//            try {
//                // 匹配删除按钮，代表视频上传完毕
//                String buttonInfo = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("发表")).getAttribute("class");
//                if (!buttonInfo.contains("weui-desktop-btn_disabled")) {
//                    log.info("video upload finish for wechat, path: {}", workFilePath);
//                    Thread.sleep(2000);
//
//                    String previewButtonInfo = page.locator("div.finder-tag-wrap.btn:has-text(\"更换封面\")").getAttribute("class");
//                    if (!previewButtonInfo.contains("disabled")) {
//                        page.locator("div.finder-tag-wrap.btn:has-text(\"更换封面\")").click();
//                        page.locator("div.single-cover-uploader-wrap > div.wrap").hover();
//                        if (page.locator(".del-wrap > .svg-icon").count() > 0) {
//                            page.locator(".del-wrap > .svg-icon").click();
//                        }
//
//                        // 定位上传封面图的div， 并删除个哈UN
//                        Locator previewUploadDivLoc = page.locator("div.single-cover-uploader-wrap > div.wrap");
//                        FileChooser fileChooser = page.waitForFileChooser(() -> {
//                            previewUploadDivLoc.click();
//                        });
//                        fileChooser.setFiles(Paths.get(metaData.getPreViewFilePath()));
//                        Thread.sleep(2000);
//
//                        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("确定")).click();
//                        Thread.sleep(1000);
//                        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("确认")).click();
//                        break;
//                    } else {
//                        log.info("video is uploading for wechat, path: {}", workFilePath);
//                        Thread.sleep(2000);
//                        // 出错了视频出错
//                        if (page.locator("div.status-msg.error").count() > 0 && page.locator("div.media-status-content div.tag-inner:has-text(\"删除\")").count() > 0) {
//                            System.out.println("  [-] 发现上传出错了...");
//                            handleUploadError(page);
//                        }
//                    }
//                } else {
//                    System.out.println("  [-] 正在上传视频中...");
//                    java.util.concurrent.TimeUnit.SECONDS.sleep(2);
//                }
//            } catch (Exception e) {
//                System.out.println("  [-] 正在上传视频中...");
//                try {
//                    java.util.concurrent.TimeUnit.SECONDS.sleep(2);
//                } catch (InterruptedException ex) {
//                    Thread.currentThread().interrupt();
//                }
//            }
//        }
//    }
//}
