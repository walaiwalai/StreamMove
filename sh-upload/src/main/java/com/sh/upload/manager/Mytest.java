package com.sh.upload.manager;

import com.alibaba.fastjson.JSONObject;
import com.sh.config.utils.HttpClientUtil;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * @author caiWen
 * @date 2023/1/27 22:09
 */
public class Mytest {
    public static void main(String[] args) throws IOException {
        String cookies
                = "buvid3=BE94C297-37F5-4180-93F9-1040DFF8DC3034758infoc; LIVE_BUVID=AUTO6316203107933649; "
                + "i-wanna-go-back=-1; buvid4=461CF39D-4660-5C2A-77DF-A2A10F98526C55219-022012118"
                + "-SjAPq9halyhvjYHHwqTpMw==; CURRENT_BLACKGAP=0; nostalgia_conf=-1; hit-dyn-v2=1; "
                + "buvid_fp_plain=undefined; fingerprint3=db2272f3edefa30c022365b750f89c48; "
                + "_uuid=522A93D6-4B31-E846-9999-10171DED6101F1036656infoc; is-2022-channel=1; blackside_state=0; "
                + "b_nut=100; DedeUserID=14527951; DedeUserID__ckMd5=687c6341d4ea00d9; b_ut=5; rpdid=|"
                + "(umRk|YRJYR0J'uYY)~~YJJY; hit-new-style-dyn=0; CURRENT_FNVAL=4048; "
                + "fingerprint=7bd1138169e7fbf084d8e6e098b332f6; buvid_fp=7bd1138169e7fbf084d8e6e098b332f6; "
                + "CURRENT_QUALITY=80; SESSDATA=00ab5a51,1690292639,15fa4*12; "
                + "bili_jct=a1b05e6e5d426c1c9fad5366b8f7a811; sid=6z6xno3r; PVID=2; innersign=0; "
                + "bsource=search_baidu; bp_video_offset_14527951=755852469786902700; b_lsid=577E6EDF_185F3878C83";
        String name = "2part-000.mp4";
        String filePath = "D:\\360MoveData\\Users\\caiwe\\Desktop\\2part-000.mp4";


        File file = new File(filePath);
        FileInputStream fis = new FileInputStream(file);
        long fileSize = fis.getChannel().size();
        String url = "https://member.bilibili.com/preupload?upcdn=bda2&zone=cs&name=" + name
                + "&r=upos&profile=ugcfx%2Fbup&size=" + fileSize + "&webVersion=2.0.0";



        CloseableHttpClient httpclient = HttpClientBuilder.create().build();
        HttpGet httpGet = new HttpGet();
        httpGet.addHeader("Accept","*/*");
        httpGet.addHeader("Accept-Encoding","gzip, deflate, br");
        httpGet.addHeader("Cookie", cookies);
        httpGet.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) Chrome/63.0.3239.132 Safari/537.36");
        CloseableHttpResponse res = httpclient.execute(httpGet);
        String resp = EntityUtils.toString(res.getEntity(), "UTF-8");
        Map<String, Object> json = JSONObject.parseObject(resp, Map.class);

//        Map<String,Object> map = JSONObject.parseObject(post(this.url + "?uploads&output=json",null));
    }


}
