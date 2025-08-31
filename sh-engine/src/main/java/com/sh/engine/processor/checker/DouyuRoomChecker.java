package com.sh.engine.processor.checker;

import cn.hutool.crypto.digest.MD5;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.ImmutableMap;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.InitConfig;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.HttpClientUtil;
import com.sh.config.utils.OkHttpClientUtil;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.processor.recorder.danmu.DanmakuRecorder;
import com.sh.engine.processor.recorder.stream.StreamRecorder;
import com.sh.engine.processor.recorder.stream.StreamUrlStreamRecorder;
import com.sh.engine.util.JavaScriptUtil;
import com.sh.engine.util.RegexUtil;
import lombok.extern.slf4j.Slf4j;
import okhttp3.FormBody;
import okhttp3.Request;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * @author caiWen
 * @date 2023/2/4 23:28
 */
@Component
@Slf4j
public class DouyuRoomChecker extends AbstractRoomChecker {
    private static final String RID_REGEX = "rid=(.*?)&";
    private static final String DIGIT_REGEX = "douyu.com/(.*?)(?=\\?|$)";
    private static final Map<String, String> QUALITY_MAP = new HashMap<>();
    private static final String DEFAULT_DOUYU_COOKIES = "dy_did=413b835d2ae00270f0c69f6400031601; acf_did=413b835d2ae00270f0c69f6400031601; Hm_lvt_e99aee90ec1b2106afe7ec3b199020a7=1692068308,1694003758; m_did=96003918aa5365bc6dcb4933000316p1; dy_teen_mode=%7B%22uid%22%3A%22472647365%22%2C%22status%22%3A0%2C%22birthday%22%3A%22%22%2C%22password%22%3A%22%22%7D; PHPSESSID=td59qi2fu2gepngb8mlehbeme3; acf_auth=94fc9s%2FeNj%2BKlpU%2Br8tZC3Jo9sZ0wz9ClcHQ1akL2Nhb6ZyCmfjVWSlR3LFFPuePWHRAMo0dt9vPSCoezkFPOeNy4mYcdVOM1a8CbW0ZAee4ipyNB%2Bflr58; dy_auth=bec5yzM8bUFYe%2FnVAjmUAljyrsX%2FcwRW%2FyMHaoArYb5qi8FS9tWR%2B96iCzSnmAryLOjB3Qbeu%2BBD42clnI7CR9vNAo9mva5HyyL41HGsbksx1tEYFOEwxSI; wan_auth37wan=5fd69ed5b27fGM%2FGoswWwDo%2BL%2FRMtnEa4Ix9a%2FsH26qF0sR4iddKMqfnPIhgfHZUqkAk%2FA1d8TX%2B6F7SNp7l6buIxAVf3t9YxmSso8bvHY0%2Fa6RUiv8; acf_uid=472647365; acf_username=472647365; acf_nickname=%E7%94%A8%E6%88%B776576662; acf_own_room=0; acf_groupid=1; acf_phonestatus=1; acf_avatar=https%3A%2F%2Fapic.douyucdn.cn%2Fupload%2Favatar%2Fdefault%2F24_; acf_ct=0; acf_ltkid=25305099; acf_biz=1; acf_stk=90754f8ed18f0c24; Hm_lpvt_e99aee90ec1b2106afe7ec3b199020a7=1694003778";

    private static final MD5 md5 = MD5.create();

    static {
        QUALITY_MAP.put("原画", "0");
        QUALITY_MAP.put("蓝光", "0");
        QUALITY_MAP.put("超清", "3");
        QUALITY_MAP.put("高清", "2");
        QUALITY_MAP.put("标清", "2");
    }

    private String fetchStreamData(String rid, String rate) {
        String did = "10000000000000000000000000003306";
        String url = "https://www.douyu.com/" + rid;
        String resp = HttpClientUtil.sendGet(url, null, null, false);
        String jsStr = RegexUtil.fetchMatchedOne(resp, "(vdwdae325w_64we[\\s\\S]*function ub98484234[\\s\\S]*?)function");
        String func = jsStr.replaceAll("eval.*?;}", "strc;}");
        String funcStr = JavaScriptUtil.execJsScript(func, "ub98484234");

        String t10 = String.valueOf(System.currentTimeMillis() / 1000L);
        String v = RegexUtil.fetchMatchedOne(funcStr, "v=(\\d+)");
        String rb = md5.digestHex(rid + did + t10 + v);
        String funcSign = funcStr.replaceAll("return rt;}\\);?", "return rt;}")
                .replace("(function (", "function sign(")
                .replace("CryptoJS.MD5(cb).toString()", "\"" + rb + "\"");
        String paramStr = JavaScriptUtil.execJsScript(funcSign, "sign", rid, did, t10);

        List<String> paramsList = new ArrayList<>();
        for (String s : paramStr.split("&")) {
            paramsList.add(s.split("=")[1]);
        }
        FormBody formBody = new FormBody.Builder()
                .add("v", paramsList.get(0))
                .add("did", paramsList.get(1))
                .add("tt", paramsList.get(2))
                .add("sign", paramsList.get(3))
                .add("ver", "22011191")
                .add("rid", rid)
                .add("rate", rate)
                .build();


        // # 0蓝光、3超清、2高清、-1默认
        InitConfig initConfig = ConfigFetcher.getInitConfig();
//        String apiUrl = "https://www.douyu.com/lapi/live/getH5Play/" + rid + "?"
//                + "v=" + paramsList.get(0)
//                + "&did=" + paramsList.get(1)
//                + "&tt=" + paramsList.get(2)
//                + "&sign=" + paramsList.get(3)
//                + "&ver=22011191"
//                + "&rid=" + rid
//                + "&rate=" + rate;
        String apiUrl = "https://www.douyu.com/lapi/live/getH5Play/" + rid;
        Request.Builder reBuilder = new Request.Builder()
                .url(apiUrl)
                .post(formBody)
                .addHeader("User-Agent", "ios/7.830 (ios 17.0; ; iPhone 15 (A2846/A3089/A3090/A3092))")
                .addHeader("Referer", "https://m.douyu.com/3125893?rid=3125893&dyshid=0-96003918aa5365bc6dcb4933000316p1&dyshci=181")
                .addHeader("Cookie", DEFAULT_DOUYU_COOKIES);
        if (StringUtils.isNotBlank(initConfig.getDouyuCookies())) {
            reBuilder.addHeader("Cookie", initConfig.getDouyuCookies());
        }

        return OkHttpClientUtil.execute(reBuilder.build());
    }

    @Override
    public StreamRecorder getStreamRecorder(StreamerConfig streamerConfig) {
        String rid = RegexUtil.fetchMatchedOne(streamerConfig.getRoomUrl(), RID_REGEX);
        if (StringUtils.isBlank(rid)) {
            rid = RegexUtil.fetchMatchedOne(streamerConfig.getRoomUrl(), DIGIT_REGEX);
        }
        if (StringUtils.isBlank(rid)) {
            log.error("roomUrl is illegal for douyu, roomUrl: {}", streamerConfig.getRoomUrl());
            return null;
        }

        String url = "https://m.douyu.com/" + rid;
        Map<String, String> headers = ImmutableMap.of("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0");
        String resp1 = HttpClientUtil.sendGet(url, headers, null, false);

        String jsonStr = RegexUtil.fetchMatchedOne(resp1, "<script id=\"vike_pageContext\" type=\"application/json\">(.*?)</script>");
        JSONObject roomInfoObj = JSON.parseObject(jsonStr);
        if (roomInfoObj.getJSONObject("pageContext") != null) {
            roomInfoObj = roomInfoObj.getJSONObject("pageContext");
        }
        JSONObject roomInfo = roomInfoObj.getJSONObject("pageProps").getJSONObject("room").getJSONObject("roomInfo").getJSONObject("roomInfo");

        // 播放信息
        int status = Optional.ofNullable(roomInfo.getInteger("isLive")).orElse(0);
        if (status == 1) {
            // 在直播
            rid = roomInfo.getString("rid");
            String rate = "0";
            String flvStr = fetchStreamData(rid, rate);
            JSONObject flvObj = JSONObject.parseObject(flvStr);
            String streamUrl = flvObj.getJSONObject("data").getString("rtmp_url") + "/" + flvObj.getJSONObject("data").getString("rtmp_live");
            return new StreamUrlStreamRecorder(new Date(), getType().getType(), streamUrl);
        } else {
            // 没有直播
            return null;
        }
    }

    @Override
    public DanmakuRecorder getDanmakuRecorder(StreamerConfig streamerConfig) {
        return null;
    }

    @Override
    public StreamChannelTypeEnum getType() {
        return StreamChannelTypeEnum.DOUYU;
    }

    public static void main(String[] args) {
        DouyuRoomChecker douyuRoomChecker = new DouyuRoomChecker();
        StreamRecorder streamRecorder = douyuRoomChecker.getStreamRecorder(StreamerConfig.builder().roomUrl("https://www.douyu.com/664668").build());
        System.out.println(streamRecorder);
    }
}
