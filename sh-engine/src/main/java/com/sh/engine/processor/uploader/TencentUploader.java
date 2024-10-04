//package com.sh.engine.processor.uploader;
//
//import cn.hutool.extra.spring.SpringUtil;
//import com.alibaba.fastjson.TypeReference;
//import com.google.common.collect.ImmutableMap;
//import com.microsoft.playwright.*;
//import com.microsoft.playwright.options.Cookie;
//import com.sh.config.manager.CacheManager;
//import com.sh.config.manager.ConfigFetcher;
//import com.sh.config.utils.FileStoreUtil;
//import com.sh.config.utils.PictureFileUtil;
//import com.sh.engine.constant.UploadPlatformEnum;
//import com.sh.engine.processor.uploader.meta.DouyinWorkMetaData;
//import com.sh.message.service.MsgSendService;
//import lombok.extern.slf4j.Slf4j;
//import org.apache.commons.collections4.CollectionUtils;
//import org.springframework.core.env.Environment;
//
//import java.io.File;
//import java.nio.file.Paths;
//import java.util.List;
//import java.util.Map;
//
///**
// * 腾讯视频号上传
// * @Author : caiwen
// * @Date: 2024/10/2
// */
//@Slf4j
//public class TencentUploader implements Uploader{
//    private CacheManager cacheManager;
//    private MsgSendService msgSendService;
//    private boolean headless;
//
//    @Override
//    public String getType() {
//        return UploadPlatformEnum.TENCENT.getType();
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
//        String accountSavePath = ConfigFetcher.getInitConfig().getAccountSavePath();
//        File accountFile = new File(accountSavePath, "tencent_accout.json");
//
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
//            File qrCodeFile = PictureFileUtil.saveBase64Image(imgElement);
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
//                context.storageState(new BrowserContext.StorageStateOptions().setPath(Paths.get(accountFile.getAbsolutePath())));
//            }
//
//            // 关闭浏览器
//            context.close();
//            browser.close();
//        } catch (Exception e) {
//            log.error("Failed to genCookies for tencent, path: {}", accountFile.getAbsolutePath(), e);
//        }
//    }
//
//    @Override
//    public boolean upload( String recordPath ) throws Exception {
//        return false;
//    }
//
//    private boolean checkAccountValid() {
//        File accoutFile = getAccoutFile();
//        if (!accoutFile.exists()) {
//            return false;
//        }
//
//        try (Playwright playwright = Playwright.create()) {
//            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(headless));
//            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
//                    .setStorageStatePath(Paths.get(accoutFile.getAbsolutePath())));
//
//            Page page = context.newPage();
//            page.navigate("https://channels.weixin.qq.com/platform/post/create");
//
//            try {
//                page.waitForSelector("div.title-name:has-text('视频号小店')", new Page.WaitForSelectorOptions().setTimeout(5000));
//                log.info("cookies invalid for tencent");
//
//                // Cookie 失效，删除 JSON 文件
//                accoutFile.deleteOnExit();
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
//    /**
//     * 获取账号保存文件
//     * @return  账号文件
//     */
//    private File getAccoutFile() {
//        String accountSavePath = ConfigFetcher.getInitConfig().getAccountSavePath();
//        return new File(accountSavePath, "tencent_accout.json");
//    }
//}
