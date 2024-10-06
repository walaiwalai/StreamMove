//package com.sh.engine.processor.uploader;
//
//import com.alibaba.fastjson.TypeReference;
//import com.google.common.collect.ImmutableMap;
//import com.microsoft.playwright.*;
//import com.microsoft.playwright.options.AriaRole;
//import com.microsoft.playwright.options.Cookie;
//import com.sh.config.manager.CacheManager;
//import com.sh.config.manager.ConfigFetcher;
//import com.sh.config.model.config.InitConfig;
//import com.sh.config.utils.FileStoreUtil;
//import com.sh.config.utils.PictureFileUtil;
//import com.sh.engine.constant.UploadPlatformEnum;
//import com.sh.engine.processor.uploader.meta.DouyinWorkMetaData;
//import com.sh.engine.processor.uploader.meta.MeituanWorkMetaData;
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
// * @Author caiwen
// * @Date 2024 10 06 20 45
// **/
//@Slf4j
//@Component
//public class MeituanUploader extends Uploader {
//    @Resource
//    private CacheManager cacheManager;
//    @Resource
//    private MsgSendService msgSendService;
//    @Value("${playwright.headless}")
//    private boolean headless;
//
//    private static final long MEITUAN_COOKIES_VALID_SECONDS = 86400L * 7;
//
//    public static final String AUTH_CODE_KEY = "meituan_login_authcode";
//
//
//    @Override
//    public String getType() {
//        return UploadPlatformEnum.MEI_TUAN_VIDEO.getType();
//    }
//
//    @Override
//    public void setUp() {
//        if (!checkAccountValid()) {
//            genCookies();
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
//            page.navigate("https://czz.meituan.com/new/personalHomePage");
//
//            boolean isValid = false;
//            try {
//                Locator publishBtn = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("发布视频"));
//                if (publishBtn.count() > 0) {
//                    log.info("cookies valid for meituan video");
//                    isValid = true;
//                } else {
//                    log.info("cookies invalid for wechat video");
//                    // Cookie 失效，删除缓存
//                    cacheManager.delete(getAccountKey());
//                    isValid = false;
//                }
//                context.close();
//                browser.close();
//                return isValid;
//            } catch (Exception e) {
//                log.error("cookies valid for meituan video", e);
//                context.close();
//                browser.close();
//                return false;
//            }
//        }
//    }
//
//    private void genCookies() {
//        try (Playwright playwright = Playwright.create()) {
//            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(headless));
//            BrowserContext context = browser.newContext();
//            Page page = context.newPage();
//
//            // 登录页面
//            page.navigate("https://czz.meituan.com/new/login");
//
//            // 输入手机号
//            String phoneNumber = ConfigFetcher.getInitConfig().getPhoneNumber();
//            page.getByPlaceholder("请输入手机号").fill(phoneNumber);
//            page.waitForTimeout(1000);
//            page.getByText("发送验证码").click();
//            msgSendService.sendText("美团视频号需要进行验证码验证，验证码已发出，请在60s内在微应用回复验证码, 回复格式”#mt_v_code 验证码“");
//
//
//            int num = 0;
//            while (num ++ < 14) {
//                page.waitForTimeout(3000);
//                // 检查缓存中是否有验证码
//                String authNumber = cacheManager.get(AUTH_CODE_KEY, new TypeReference<String>() {});
//                if (authNumber != null) {
//                    log.info("receive verify code for {}..., code: {}", getType(), authNumber);
//                    page.getByPlaceholder("请输入短信验证码").fill(authNumber);
//
//                    // 点击同意协议
//                    page.locator("i").click();
//                    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("登录 / 注册")).click();
//                    page.waitForTimeout(2000);
//
//                    break;
//                } else {
//                    log.info("waiting for input verify code for {}..., retry: {}/13", getType(), num);
//                }
//            }
//
//            // 等待用户操作并刷新页面
//            page.waitForTimeout(7000);
//
//            Page platformPage = context.newPage();
//            platformPage.navigate("https://czz.meituan.com/new/personalHomePage");
//            platformPage.waitForURL("https://czz.meituan.com/new/personalHomePage");
//
//            List<Cookie> cookies = context.cookies();
//            if (CollectionUtils.isNotEmpty(cookies)) {
//                // 保存cookie到文件
//                String storageState = context.storageState();
//                cacheManager.set(getAccountKey(), storageState, MEITUAN_COOKIES_VALID_SECONDS, TimeUnit.SECONDS);
//                log.info("gen cookies for {} success", getType());
//            }
//
//            // 关闭浏览器
//            context.close();
//            browser.close();
//        } catch (Exception e) {
//            log.error("Failed to genCookies for meituan", e);
//        }
//    }
//
//
//    private boolean doUpload(String recordPath) {
//        File targetFile = new File(recordPath, "highlight.mp4");
//        String workFilePath = targetFile.getAbsolutePath();
//
//        // 加载元数据
//        MeituanWorkMetaData metaData = FileStoreUtil.loadFromFile(
//                new File(recordPath, UploaderFactory.getMetaFileName(getType())),
//                new TypeReference<MeituanWorkMetaData>() {
//                });
//
//        // 开始上传
//        try (Playwright playwright = Playwright.create()) {
//            // 带着cookies创建浏览器
//            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(headless));
//            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
//                    .setStorageStatePath(Paths.get(getAccoutFile().getAbsolutePath())));
//
//            Page page = context.newPage();
//            page.navigate("https://creator.douyin.com/creator-micro/content/upload");
//            log.info("begin uploading..., video: {}", workFilePath);
//
//            // 上传视频
//            page.setInputFiles("div[class^='container'] input", Paths.get(workFilePath));
//
//            // 等待上回完成
//            page.waitForURL("https://creator.douyin.com/creator-micro/content/publish?enter_from=publish_page");
//
//            // 填写标题
//            fillTitle(page, metaData);
//
//            // 填写标签
//            fillTags(page, metaData);
//
//            // 等待视频上传完成
//            waitingVideoUploadFinish(page, workFilePath);
//
//            // 保存封面
//            if (StringUtils.isNotBlank(metaData.getPreViewFilePath()) && new File(metaData.getPreViewFilePath()).exists()) {
//                try {
//                    fillThumbnail(page, metaData);
//                    log.info("upload thumbnail success, path: {}, thumbnail: {}", workFilePath, metaData.getPreViewFilePath());
//                } catch (Exception e) {
//                    log.error("upload thumbnail failed, will skip, path: {}, thumbnail: {}", workFilePath, metaData.getPreViewFilePath());
//                }
//            }
//
//            // 填写位置
//            if (StringUtils.isNotBlank(metaData.getLocation())) {
//                try {
//                    fillLocation(page, metaData);
//                    log.info("upload thumbnail success, path: {}, location: {}", workFilePath, metaData.getLocation());
//                } catch (Exception e) {
//                    log.error("upload thumbnail failed, will skip, path: {}, location: {}", workFilePath, metaData.getLocation());
//                }
//            }
//
//            // 三方平台勾选
//            String thirdPartElement = "[class^='info'] > [class^='first-part'] div div.semi-switch";
//            if (page.locator(thirdPartElement).count() > 0) {
//                String className = page.evalOnSelector(thirdPartElement, "div => div.className").toString();
//                if (!className.contains("semi-switch-checked")) {
//                    page.locator(thirdPartElement).locator("input.semi-switch-native-control").click();
//                }
//            }
//
////            // Set scheduled publishing time (if applicable)
////            if (publishDate != 0) {
////                setScheduleTimeDouyin(page, publishDate); // Placeholder for scheduled publishing
////            }
//
//            // 发布视频
//            publishVideo(page, workFilePath);
//
//            // Save updated cookies
//            context.storageState(new BrowserContext.StorageStateOptions().setPath(Paths.get(getAccoutFile().getAbsolutePath())));
//            log.info("update douyin cookies success, video: {}", workFilePath);
//            page.waitForTimeout(2000);
//
//            // Close the browser
//            context.close();
//            browser.close();
//            return true;
//        } catch (Exception e) {
//            log.error("douyin fuck", e);
//            return false;
//        }
//    }
//}
