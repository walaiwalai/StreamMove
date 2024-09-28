//package com.sh.engine.playwright;
//
//import com.google.common.collect.Lists;
//import com.microsoft.playwright.*;
//import com.microsoft.playwright.options.AriaRole;
//import com.microsoft.playwright.options.WaitUntilState;
//import com.sh.config.manager.ConfigFetcher;
//import lombok.extern.slf4j.Slf4j;
//
//import java.io.File;
//import java.nio.file.Path;
//import java.nio.file.Paths;
//import java.util.List;
//
///**
// * @Author caiwen
// * @Date 2024 05 19 22 06
// **/
//@Slf4j
//public class DouyinPlaywright {
//    public static final int MAX_RETRY_COUNT = 5;
//
//
//    /**
//     * 生成抖音的cookies，并保存到本地
//     */
//    public static void genCookies() {
//        String accountFile = ConfigFetcher.getInitConfig().getDouyinCookiesPath();
//        Path path = Paths.get(accountFile);
//        BrowserContext.StorageStateOptions options = new BrowserContext.StorageStateOptions();
//        options.setPath(path);
//
//        try (Playwright playwright = Playwright.create()) {
//            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(false));
//            BrowserContext context = browser.newContext();
//            Page page = context.newPage();
//            page.navigate("https://www.douyin.com/");
//            page.pause();
//            context.storageState(options);
//            browser.close();
//        } catch (Exception e) {
//            log.error("fuck");
//        }
//    }
//
//    /**
//     * 检查抖音的cookies是否合理
//     *
//     * @return 合理/不合理
//     */
//    public static boolean isCkValid() {
//        String accountFile = ConfigFetcher.getInitConfig().getDouyinCookiesPath();
//        File file = new File(accountFile);
//        if (!file.exists()) {
//            return false;
//        }
//
//        return authCookies();
//    }
//
//    /**
//     * 验证cookies有效性
//     *
//     * @return
//     */
//    private static boolean authCookies() {
//        String accountFile = ConfigFetcher.getInitConfig().getDouyinCookiesPath();
//        BrowserContext.StorageStateOptions options = new BrowserContext.StorageStateOptions();
//        options.setPath(Paths.get(accountFile));
//        try (Playwright playwright = Playwright.create()) {
//            Browser browser = playwright.chromium()
//                    .launch(new BrowserType.LaunchOptions().setHeadless(true));
//            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
//                    .setStorageStatePath(Paths.get(accountFile)));
//            Page page = context.newPage();
//            // 等待页面加载完成
//            page.navigate("https://creator.douyin.com/content/upload",
//                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD));
//
//            if (page.getByText("手机号登录").count() > 0) {
//                log.info("Cookies invalid!");
//                return false;
//            } else {
//                log.info("Cookies valid!");
//                return true;
//            }
//        }
//    }
//
//    public static void main(String[] args) {
//        if (!isCkValid()) {
//            genCookies();
//            return;
//        }
//
//        DouyinPlaywright.upload("F:\\video\\download\\TheShy\\2024-01-31-03-31-43\\seg-2.ts", "test", Lists.newArrayList("tag1", "tag2"));
//    }
//
//    public static boolean upload(String videoPath, String title, List<String> tags) {
//        String accountFile = ConfigFetcher.getInitConfig().getDouyinCookiesPath();
//        try (Playwright playwright = Playwright.create()) {
//            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(false));
//            BrowserContext context = browser.newContext(
//                    new Browser.NewContextOptions().setStorageStatePath(Paths.get(accountFile))
//            );
//            Page page = context.newPage();
//            page.navigate("https://creator.douyin.com/creator-micro/content/upload");
//            log.info("begin upload... file: {}", videoPath);
//
//            // 1. 指定文件上传
//            page.waitForURL("https://creator.douyin.com/creator-micro/content/upload");
//            page.locator(".upload-btn--9eZLd").setInputFiles(Paths.get(videoPath));
//
//            // 2.进入视频发布页
//            int i = 0;
//            while (i++ < MAX_RETRY_COUNT) {
//                try {
//                    page.waitForURL("https://creator.douyin.com/creator-micro/content/publish?enter_from=publish_page");
//                    break;
//                } catch (Exception e) {
//                    log.info("try into video upload page...");
//                    Thread.sleep(1000);
//                }
//            }
//
//            // 3.填充标题 和 话题
//            Thread.sleep(1000);
//            log.info("filling title and topic...");
//            Locator titleContaner = page.locator("text=作品标题").locator("..").locator("xpath=following-sibling::div[1]").locator("input");
//            if (titleContaner.count() > 0) {
//                titleContaner.fill(title.substring(0, Math.min(title.length(), 30)));
//            } else {
//                titleContaner = page.locator(".notranslate");
//                titleContaner.click();
//                page.keyboard().press("Backspace");
//                page.keyboard().press("Control+KeyA");
//                page.keyboard().press("Delete");
//                page.keyboard().type(title);
//                page.keyboard().press("Enter");
//            }
//
//            String cssSelector = ".zone-container";
//            for (String tag : tags) {
//                page.type(cssSelector, "#" + tag);
//                page.keyboard().press("Space");
//            }
//            log.info("total fill {} tags", tags.size());
//
//
//            // 4.上传视频
//            doUploadVideo(page);
//
//            // 5.頭條/西瓜
//            String thirdPartElement = "[class^=\"info\"] > [class^=\"first-part\"] div div.semi-switch";
//            if (page.locator(thirdPartElement).count() > 0) {
//                String className = (String) page.evalOnSelector(thirdPartElement, "div => div.className");
//                if (!className.contains("semi-switch-checked")) {
//                    page.locator(thirdPartElement).locator("input.semi-switch-native-control").first().click();
//                }
//            }
//
//            // 6.点击保存按钮
//            doClickPublishBtn(page);
//
//            // 7. 保存cookie
//            page.evaluate("window.localStorage.setItem('account', JSON.stringify(window.localStorage));");
//            log.info("  [-]cookie更新完毕！");
//            Thread.sleep(2000); // 延迟以便观察
//            browser.close();
//            return true;
//        } catch (Exception e) {
//            log.error("fuck", e);
//            return false;
//        }
//    }
//
//    /**
//     * 上传视频（长时间）
//     */
//    private static void doUploadVideo(Page page) throws InterruptedException {
//        while (true) {
//            // 判断视频是否上传完毕
//            try {
//                if (page.locator("div label+div:has-text(\"重新上传\")").count() > 0) {
//                    log.info("douyin video upload success");
//                    break;
//                } else {
//                    System.out.println("video uploading...");
//                    Thread.sleep(2000);
//                    if (page.locator("div.progress-div > div:has-text(\"上传失败\")").count() > 0) {
//                        log.error("video upload failed...");
//                    }
//                }
//            } catch (Exception e) {
//                log.info("video is uploading...");
//                Thread.sleep(2000);
//            }
//        }
//    }
//
//
//    private static void doClickPublishBtn(Page page) {
//        int i = 0;
//        while (i++ < MAX_RETRY_COUNT) {
//            try {
//                Locator publishButton = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("发布").setExact(true));
//                if (publishButton.count() > 0) {
//                    publishButton.first().click();
//                    page.waitForURL("https://creator.douyin.com/creator-micro/content/manage", new Page.WaitForURLOptions().setTimeout(1500));
//                    log.info("publish video success!");
//                    break;
//                }
//            } catch (Exception e) {
//                log.info("video is publishing...");
//                page.screenshot(new Page.ScreenshotOptions().setFullPage(true));
//                page.waitForTimeout(500);
//            }
//        }
//    }
//}
