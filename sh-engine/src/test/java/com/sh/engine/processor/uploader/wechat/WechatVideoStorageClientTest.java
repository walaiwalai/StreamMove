package com.sh.engine.processor.uploader.wechat;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class WechatVideoStorageClientTest {

    @Test
    public void buildsCapturedEightMibInitializeBody() {
        long size = 2L * WechatVideoStorageClient.CHUNK_SIZE + 123;

        JSONObject body = WechatVideoStorageClient.buildInitializeBody(size, 3);
        JSONArray lengths = body.getJSONArray("BlockPartLength");

        assertEquals(3, body.getIntValue("BlockSum"));
        assertEquals(WechatVideoStorageClient.CHUNK_SIZE, lengths.getLongValue(0));
        assertEquals(2L * WechatVideoStorageClient.CHUNK_SIZE, lengths.getLongValue(1));
        assertEquals(size, lengths.getLongValue(2));
        assertEquals(3, WechatVideoStorageClient.partCount(size));
    }

    @Test
    public void buildsCapturedXArgumentsInExactOrder() throws Exception {
        WechatVideoWebSession.UploadContext context =
                new WechatVideoWebSession.UploadContext("auth", "322", 251,
                        20302, 20304, 2, "upload.example.com",
                        Collections.singletonList("upload.example.com"), 1, 1, 1);

        String arguments = WechatVideoStorageClient.buildArguments(context, 20302,
                "精彩 highlight.mp4", 123, "task-id");

        assertEquals("apptype=251&filetype=20302&weixinnum=322&"
                        + "filekey=%E7%B2%BE%E5%BD%A9%20highlight.mp4&filesize=123&"
                        + "taskid=task-id&scene=2",
                arguments);
    }
}
