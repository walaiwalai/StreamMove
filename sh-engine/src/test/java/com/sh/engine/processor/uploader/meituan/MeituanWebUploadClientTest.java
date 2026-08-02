package com.sh.engine.processor.uploader.meituan;

import com.alibaba.fastjson.JSONObject;
import com.sh.engine.processor.uploader.meta.MeituanWorkMetaData;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MeituanWebUploadClientTest {

    @Test
    public void buildsCapturedImmediatePublicPublishBodyWithFirstFrameCover() {
        MeituanWorkMetaData metadata = new MeituanWorkMetaData();
        metadata.setTitle("精彩回放");
        metadata.setDesc("高光片段");
        metadata.setTags(Arrays.asList("#游戏", "精彩集锦", "游戏"));
        MeituanWebSession.CreatorProfile profile = new MeituanWebSession.CreatorProfile(
                "author", "creator", "主播", "https://avatar");
        MeituanWebUploadClient.VideoInfo video =
                new MeituanWebUploadClient.VideoInfo(1920, 1080);

        JSONObject body = MeituanWebUploadClient.buildPublishBody(metadata, profile, video,
                "video-key", "https://video-link", "cover-key");

        assertEquals("author", body.getString("authorId"));
        assertEquals("creator", body.getString("creatorId"));
        assertEquals("精彩回放 #游戏 #精彩集锦", body.getString("title"));
        assertEquals("高光片段", body.getString("description"));
        assertEquals("https://video-link", body.getString("videoLink"));
        assertEquals("video-key", body.getString("key"));
        assertEquals("cover-key", body.getString("coverImageKey"));
        assertNull(body.get("coverImage"));
        assertNull(body.get("sunriseTime"));
        assertTrue(body.getBooleanValue("isTemp"));
        assertFalse(body.getBooleanValue("activitySign"));
        assertEquals(0, body.getJSONArray("topicIds").size());
        assertEquals("无需添加自主声明",
                body.getJSONObject("authorDeclarations").getString("6"));
        assertEquals("1.3.1", body.getString("pageVersion"));
        assertEquals(1, body.getIntValue("publishScene"));
        assertEquals(10, body.getIntValue("contentTagType"));
    }

    @Test
    public void buildsCapturedS3MultipartCompletionXml() {
        String xml = MeituanWebUploadClient.buildCompleteMultipartXml(Arrays.asList(
                new MeituanWebUploadClient.UploadedPart(1, "\"etag-one\""),
                new MeituanWebUploadClient.UploadedPart(2, "\"etag-two\"")));

        assertEquals("<CompleteMultipartUpload>"
                        + "<Part><PartNumber>1</PartNumber><ETag>\"etag-one\"</ETag></Part>"
                        + "<Part><PartNumber>2</PartNumber><ETag>\"etag-two\"</ETag></Part>"
                        + "</CompleteMultipartUpload>", xml);
    }

    @Test
    public void truncatesCapturedTitleLimitAfterAddingHashtags() {
        char[] chars = new char[199];
        Arrays.fill(chars, 'a');
        MeituanWorkMetaData metadata = new MeituanWorkMetaData();
        metadata.setTitle(new String(chars));
        metadata.setTags(Arrays.asList("tag"));

        assertEquals(200, MeituanWebUploadClient.buildPublicTitle(metadata).length());
    }
}
