package com.sh.engine.processor.uploader.wechat;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sh.engine.processor.uploader.meta.WechatVideoMetaData;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class WechatVideoWebUploadClientTest {

    @Test
    public void buildsCapturedTitleDescriptionTopicsAndFinderXml() {
        WechatVideoMetaData metadata = new WechatVideoMetaData();
        metadata.setTitle(" 直播 精彩\u200b ");
        metadata.setDesc("高光片段");
        metadata.setTags(Arrays.asList("#游戏", "精彩剪辑", "#游戏"));

        WechatVideoWebUploadClient.PostText text =
                WechatVideoWebUploadClient.buildPostText(metadata);

        assertEquals("直播精彩", text.getTitle());
        assertEquals("高光片段 #游戏 #精彩剪辑", text.getDescription());
        assertEquals(Arrays.asList("游戏", "精彩剪辑"), text.getTopics());
        assertEquals("<finder><version>1</version><valuecount>6</valuecount>"
                        + "<style><at></at></style>"
                        + "<value0><![CDATA[直播精彩\n]]></value0>"
                        + "<value1><![CDATA[高光片段]]></value1>"
                        + "<value2><![CDATA[ ]]></value2>"
                        + "<value3><topic><![CDATA[#游戏#]]></topic></value3>"
                        + "<value4><![CDATA[ ]]></value4>"
                        + "<value5><topic><![CDATA[#精彩剪辑#]]></topic></value5>"
                        + "</finder>",
                text.getFinderTopicInfo());
    }

    @Test
    public void truncatesMpTitleWithCapturedDoubleByteWeight() {
        WechatVideoMetaData metadata = new WechatVideoMetaData();
        metadata.setTitle("一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一");

        WechatVideoWebUploadClient.PostText text =
                WechatVideoWebUploadClient.buildPostText(metadata);

        assertEquals(30, text.getTitle().length());
    }

    @Test
    public void computesCapturedClipBounds() {
        assertEquals(Arrays.toString(new int[]{1920, 1080}),
                Arrays.toString(WechatVideoWebUploadClient.targetSize(3840, 2160)));
        assertEquals(Arrays.toString(new int[]{1080, 1920}),
                Arrays.toString(WechatVideoWebUploadClient.targetSize(2160, 3840)));
        assertEquals(Arrays.toString(new int[]{1280, 720}),
                Arrays.toString(WechatVideoWebUploadClient.targetSize(1280, 720)));
    }

    @Test
    public void reusesFirstFrameUrlForAllCapturedCoverFields() {
        WechatVideoWebSession.UploadContext context = uploadContext();
        WechatVideoStorageClient.StorageUploadResult video =
                new WechatVideoStorageClient.StorageUploadResult("https://video", "video-task",
                        10, 20, 9000);
        WechatVideoStorageClient.StorageUploadResult cover =
                new WechatVideoStorageClient.StorageUploadResult("https://first-frame", "cover-task",
                        20, 21, 1000);
        WechatVideoWebUploadClient.VideoInfo info =
                new WechatVideoWebUploadClient.VideoInfo(1920, 1080, 12.5, 1234);
        WechatVideoWebUploadClient.ClipTicket clip =
                new WechatVideoWebUploadClient.ClipTicket("clip", "draft");
        WechatVideoWebUploadClient.TraceInfo trace =
                new WechatVideoWebUploadClient.TraceInfo("trace", 10, 20);
        WechatVideoWebUploadClient.PostText text =
                new WechatVideoWebUploadClient.PostText("标题", "描述 #话题",
                        Collections.singletonList("话题"), "<finder></finder>");

        JSONObject body = WechatVideoWebUploadClient.buildPublishBody(new File("highlight.mp4"),
                info, video, cover, clip, trace, text, new JSONObject(true), context);
        JSONObject media = body.getJSONObject("objectDesc").getJSONArray("media")
                .getJSONObject(0);

        assertEquals("https://first-frame", media.getString("thumbUrl"));
        assertEquals("https://first-frame", media.getString("fullThumbUrl"));
        assertEquals("https://first-frame", media.getString("coverUrl"));
        assertEquals("https://first-frame", media.getString("fullCoverUrl"));
        assertEquals("https://first-frame", media.getString("shareCoverUrl"));
        assertEquals(2, media.getIntValue("cardShowStyle"));
        assertFalse(body.containsKey("visibility"));
        JSONArray topics = body.getJSONArray("topics");
        assertEquals(Collections.singletonList("话题"), topics.toJavaList(String.class));
    }

    @Test
    public void mapsCapturedDefaultLocationShape() {
        JSONObject address = new JSONObject(true);
        address.put("latitude", 30.1);
        address.put("longitude", 120.2);
        address.put("city", "杭州");
        address.put("uid", "poi-id");
        address.put("name", "地点");
        address.put("fullAddress", "地址");

        JSONObject location = WechatVideoWebUploadClient.buildLocation(address);

        assertEquals(30.1, location.getDoubleValue("latitude"), 0.0001);
        assertEquals(120.2, location.getDoubleValue("longitude"), 0.0001);
        assertEquals("杭州", location.getString("city"));
        assertEquals("poi-id", location.getString("poiClassifyId"));
        assertEquals("地点", location.getString("poiName"));
        assertEquals("地址", location.getString("address"));
    }

    private static WechatVideoWebSession.UploadContext uploadContext() {
        return new WechatVideoWebSession.UploadContext("auth", "uin", 251,
                20302, 20304, 2, "upload.example.com",
                Collections.singletonList("upload.example.com"), 1, 1, 1);
    }
}
