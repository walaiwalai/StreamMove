package com.sh.engine.playwright;

import com.google.common.collect.Lists;
import com.microsoft.playwright.*;
import com.sh.config.manager.ConfigFetcher;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * @Author caiwen
 * @Date 2024 05 19 22 06
 **/
@Slf4j
public class DouyinPlaywright {
    public static void genCookies() {
        String accountFile = ConfigFetcher.getInitConfig().getDouyinCookiesPath();
        Path path = Paths.get(accountFile);
        BrowserContext.StorageStateOptions options = new BrowserContext.StorageStateOptions();
        options.setPath(path);

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(false));
            BrowserContext context = browser.newContext();
            Page page = context.newPage();
            page.navigate("https://www.douyin.com/");
            page.pause();
            context.storageState(options);
            browser.close();
        } catch (Exception e) {
            log.error("fuck");
        }
    }

    public static boolean isCkValid() {
        String accountFile = ConfigFetcher.getInitConfig().getDouyinCookiesPath();
        File file = new File(accountFile);
        if (!file.exists()) {
            return false;
        }

        return authCookies();
    }

    /**
     * 验证cookies有效性
     *
     * @return
     */
    private static boolean authCookies() {
        String accountFile = ConfigFetcher.getInitConfig().getDouyinCookiesPath();
        BrowserContext.StorageStateOptions options = new BrowserContext.StorageStateOptions();
        options.setPath(Paths.get(accountFile));
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setStorageStatePath(Paths.get(accountFile)));
            Page page = context.newPage();
            page.navigate("https://creator.douyin.com/creator-micro/content/upload");
            try {
                page.waitForSelector("div.boards-more h3:text('抖音排行榜')",
                        new Page.WaitForSelectorOptions().setTimeout(5000));
                log.info("douyin cookie inValid");
                return false;
            } catch (TimeoutError e) {
                log.error("douyin cookie valid");
                return true;
            }
        }
    }

    private static BrowserContext.StorageStateOptions genStorageOption() {
        String accountFile = ConfigFetcher.getInitConfig().getDouyinCookiesPath();
        BrowserContext.StorageStateOptions options = new BrowserContext.StorageStateOptions();
        options.setPath(Paths.get(accountFile));
        return options;
    }

    public static void main(String[] args) {
        if (!isCkValid()) {
            genCookies();
            return;
        }

        DouyinPlaywright.upload("F:\\video\\seg-2.mp4");
    }

    public static void upload(String videoPath) {
        String accountFile = ConfigFetcher.getInitConfig().getDouyinCookiesPath();
        BrowserContext.StorageStateOptions options = genStorageOption();
        String title = "Your video title";
        List<String> tags = Lists.newArrayList("tag1", "tag2", "tag3"); // 替换为实际的标签数组

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(false));
            BrowserContext context = browser.newContext(
                    new Browser.NewContextOptions().setStorageStatePath(Paths.get(accountFile))
            );
            Page page = context.newPage();
            page.navigate("https://creator.douyin.com/creator-micro/content/upload");
            log.info("begin upload... file: {}", videoPath);

            // 1. 指定文件上传
            page.waitForURL("https://creator.douyin.com/creator-micro/content/upload");
            page.setInputFiles(".upload-btn--9eZLd", Paths.get(videoPath));

            // 2.进入视频发布页
            page.waitForURL("https://creator.douyin.com/creator-micro/content/publish?enter_from=publish_page");

            // 3.填充标题 和 话题
            page.locator("[placeholder=\"好的作品标题可获得更多浏览\"]").fill(title.substring(0, Math.min(title.length(), 30)));
            String cssSelector = ".zone-container";
            page.locator(cssSelector).click();
            for (int index = 0; index < tags.size(); index++) {
                page.locator(cssSelector).type("#" + tags.get(index));
                page.locator(cssSelector).press("Space");
            }

            while (true) {
                // 判断视频是否上传完毕
                if (page.locator("div label+div:has-text('重新上传')").count() > 0) {
                    log.info("douyin video upload success");
                    break;
                } else {
                    System.out.println("video uploading...");
                    Thread.sleep(2000);

                    if (page.locator("div.progress-div > div:has-text('上传失败')").count() > 0) {
                        log.error("video upload failed...");
//                        handleUploadError(page);
                    }
                }
            }

            // 更换可见元素
            page.click("div.semi-select span:has-text('输入地理位置')");
            Thread.sleep(1000);
            page.keyboard().press("Backspace");
            page.keyboard().press("Control+A");
            page.keyboard().press("Delete");
            page.keyboard().type("杭州市");
            page.click("div[role='listbox'] [role='option']:nth-of-type(1)");

            page.pause();

            // 頭條/西瓜
//            if (page.querySelector("[class^='info'] > [class^='first-part'] div div.semi-switch") != null) {
//                String thirdPartElement = "[class^='info'] > [class^='first-part'] div div.semi-switch";
//                if (!page.querySelector(thirdPartElement)..hasClass("semi-switch-checked")) {
//                    page.click(thirdPartElement + " input.semi-switch-native-control");
//                }
//            }

            // 点击保存按钮
            page.locator("#garfish_app_for_content_xbm5xati button:has-text(\"发布\")").click();
            assertThat(page).hasURL("https://creator.douyin.com/creator-micro/content/manage");
            log.info("  [-]视频发布成功");

            // 保存cookie
            page.evaluate("window.localStorage.setItem('account', JSON.stringify(window.localStorage));");
            log.info("  [-]cookie更新完毕！");
            Thread.sleep(2000); // 延迟以便观察
            browser.close();
        } catch (Exception e) {
            log.error("fuck");
        }
    }

}
