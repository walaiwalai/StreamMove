package com.sh.engine.processor.uploader.wechat;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WechatVideoWebSessionTest {
    @Test
    public void identifiesWechatLoginUrls() {
        assertTrue(WechatVideoWebSession.isLoginPage(
                "https://channels.weixin.qq.com/login.html?redirect=/platform"));
        assertTrue(WechatVideoWebSession.isLoginPage(
                "https://channels.weixin.qq.com/platform/login"));
        assertFalse(WechatVideoWebSession.isLoginPage(
                "https://channels.weixin.qq.com/platform"));
        assertFalse(WechatVideoWebSession.isLoginPage(null));
    }

    @Test
    public void identifiesWechatQrFrames() {
        assertTrue(WechatVideoWebSession.isLoginQrFrame(
                "https://open.weixin.qq.com/connect/qrconnect?appid=test"));
        assertFalse(WechatVideoWebSession.isLoginQrFrame(
                "https://channels.weixin.qq.com/login.html"));
        assertFalse(WechatVideoWebSession.isLoginQrFrame(null));
    }
}
