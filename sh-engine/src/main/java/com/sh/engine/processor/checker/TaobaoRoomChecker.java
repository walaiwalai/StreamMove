package com.sh.engine.processor.checker;

import cn.hutool.core.net.url.UrlBuilder;
import cn.hutool.core.util.CharsetUtil;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.InitConfig;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.OkHttpClientUtil;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.processor.recorder.Recorder;
import com.sh.engine.processor.recorder.StreamUrlRecorder;
import com.sh.engine.util.JavaScriptUtil;
import com.sh.engine.util.RegexUtil;
import com.sh.engine.util.UrlUtil;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Headers;
import okhttp3.Request;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @Author caiwen
 * @Date 2025 04 06 10 17
 **/
@Component
@Slf4j
public class TaobaoRoomChecker extends AbstractRoomChecker {
    private static final String APP_KEY = "12574478";

    @Override
    public Recorder getStreamRecorder(StreamerConfig streamerConfig) {
        InitConfig initConfig = ConfigFetcher.getInitConfig();
        if (StringUtils.isBlank(initConfig.getTaobaoCookies()) || !initConfig.getTaobaoCookies().contains("_m_h5_tk")) {
            throw new StreamerRecordException(ErrorEnum.ROOM_CHECK_PARAM_ERROR);
        }
        String streamUrl = getStreamUrl(initConfig.getTaobaoCookies(), streamerConfig.getRoomUrl());
        return StringUtils.isBlank(streamUrl) ? null : new StreamUrlRecorder(new Date(), getType().getType(), streamUrl);
    }

    @Override
    public StreamChannelTypeEnum getType() {
        return StreamChannelTypeEnum.TAOBAO;
    }

    private String getStreamUrl(String cookies, String roomUrl) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://huodong.m.taobao.com/");
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0");
        headers.put("Cookie", cookies);

        String liveId = UrlUtil.getParams(roomUrl, "id");
        if (liveId == null) {
            Request request = new Request.Builder()
                    .url(roomUrl)
                    .get()
                    .headers(Headers.of(headers))
                    .build();
            String resp = OkHttpClientUtil.execute(request);
            String liveUrl = RegexUtil.fetchMatchedOne(resp, "var url = '(.*?)';");
            liveId = UrlUtil.getParams(liveUrl, "id");
        }

        for (int i = 0; i < 2; i++) {
            String mh5tk = RegexUtil.fetchMatchedOne(cookies, "_m_h5_tk=(.*?);");
            long t13 = System.currentTimeMillis();
            String preSignStr = mh5tk.split("_")[0] + "&" + t13 + "&" + APP_KEY + "&{\"liveId\":\"" + liveId + "\",\"creatorId\":null}";
            String sign = JavaScriptUtil.execJsFromFile("taobao-sign.js", "sign", preSignStr);

            Map<String, String> params = new HashMap<>();
            params.put("jsv", "2.7.0");
            params.put("appKey", APP_KEY);
            params.put("t", String.valueOf(t13));
            params.put("sign", sign);
            params.put("AntiFlood", "true");
            params.put("AntiCreep", "true");
            params.put("api", "mtop.mediaplatform.live.livedetail");
            params.put("v", "4.0");
            params.put("preventFallback", "true");
            params.put("type", "jsonp");
            params.put("dataType", "jsonp");
            params.put("callback", "mtopjsonp1");
            params.put("data", "{\"liveId\":\"" + liveId + "\",\"creatorId\":null}");

            String apiUrl = UrlBuilder.ofHttp("https://h5api.m.taobao.com/h5/mtop.mediaplatform.live.livedetail/4.0", CharsetUtil.CHARSET_UTF_8)
                    .addQuery("jsv", "2.7.0")
                    .addQuery("appKey", APP_KEY)
                    .addQuery("t", String.valueOf(t13))
                    .addQuery("sign", sign)
                    .addQuery("AntiFlood", "true")
                    .addQuery("AntiCreep", "true")
                    .addQuery("api", "mtop.mediaplatform.live.livedetail")
                    .addQuery("v", "4.0")
                    .addQuery("preventFallback", "true")
                    .addQuery("type", "jsonp")
                    .addQuery("dataType", "jsonp")
                    .addQuery("callback", "mtopjsonp1")
                    .addQuery("data", "{\"liveId\":\"" + liveId + "\",\"creatorId\":null}")
                    .build();
            Request request = new Request.Builder()
                    .url(apiUrl)
                    .get()
                    .headers(Headers.of(headers))
                    .build();
            Pair<String, String> pair = OkHttpClientUtil.executeWithCookies(request);
            String resp = pair.getLeft();
            String newCookie = pair.getRight();

            JSONObject respObj = jsonpToJson(resp);
            if (StringUtils.equals(respObj.getJSONArray("ret").getString(0), "SUCCESS::调用成功")) {
                JSONObject data = respObj.getJSONObject("data");
                String anchorName = data.getJSONObject("broadCaster").getString("accountName");
                String liveStatus = data.getString("streamStatus");
                if ("1".equals(liveStatus)) {
                    JSONArray playUrlList = data.getJSONArray("liveUrlList");
                    Map<String, String> playMap = playUrlList.stream()
                            .collect(Collectors.toMap(playUrl -> {
                                JSONObject obj = (JSONObject) playUrl;
                                return obj.getString("newName") == null ? obj.getString("name") : obj.getString("newName");
                            }, playUrl -> {
                                JSONObject obj = (JSONObject) playUrl;
                                return obj.getString("hlsUrl");
                            }));
                    return getUrlByMap(playMap);
                } else {
                    return null;
                }
            }

            if (newCookie.contains("_m_h5_tk") && newCookie.contains("_m_h5_tk_enc")) {
                headers.put("Cookie", updateCookie(headers.get("Cookie"), newCookie));
            } else {
                log.error("Error: Try to update cookie failed, please update the cookies in the configuration file");
            }
        }
        return null;
    }

    private JSONObject jsonpToJson(String jsonpStr) {
        String jsonStr = jsonpStr.replaceAll("^.*?\\((.*)\\);?$", "$1");
        return JSONObject.parseObject(jsonStr);
    }

    private String getUrlByMap(Map<String, String> playMap) {
        ArrayList<String> playNames = Lists.newArrayList("蓝光", "超清", "高清", "流畅", "超级流畅");
        for (String playName : playNames) {
            if (playMap.containsKey(playName)) {
                return playMap.get(playName);
            }
        }
        return null;
    }

    private String updateCookie(String oldCookie, String newCookie) {
        String replaced = oldCookie;
        String m_h5_tk = RegexUtil.fetchMatchedOne(newCookie, "_m_h5_tk");
        String m_h5_tk_enc = RegexUtil.fetchMatchedOne(newCookie, "_m_h5_tk_enc");
        replaced = replaced.replaceAll("_m_h5_tk=(.*?);", m_h5_tk + ";");
        replaced = replaced.replaceAll("_m_h5_tk_enc=(.*?);", m_h5_tk_enc + ";");
        return replaced;
    }

    public static void main(String[] args) {
        String cookies = "_samesite_flag_=true; cookie2=100eb3e61b734f9628642e0b4332627e; t=1ce2c0d298e7f52eaba181ecf7a8b492; _tb_token_=e1eb3115b0be3; 3PcFlag=1743910745427; cna=WuV4ILVhtSUCAXPA3uSUg8X+; xlly_s=1; wk_cookie2=11967149e389820599bd389e0142799c; wk_unb=UUpninsILY8h5Q%3D%3D; sgcookie=E100%2BaBIRpvQSSAw9avuzP3llhgdhRqmBZz1SvCdk4eJANOgBqLI6ZCyad%2BjUZ7JAJ2upbrXZ4oZWTQXL8IMa7mJHT%2Fo7SCFajYrs4FgCLuadXs%3D; _hvn_lgc_=0; havana_lgc2_0=eyJoaWQiOjIyNjI0OTQ4MDksInNnIjoiNjdhMjU1ZDAyMWU5NDNjMWZiNTE4ZWIzMzU3YjNjY2MiLCJzaXRlIjowLCJ0b2tlbiI6IjFKbjY1SzBCNHZEa3NzN0h2a3BmZFB3In0; unb=2262494809; csg=002e5fc2; lgc=%5Cu83DC%5Cu9E1F12382; cancelledSubSites=empty; cookie17=UUpninsILY8h5Q%3D%3D; dnk=%5Cu83DC%5Cu9E1F12382; skt=e16d18f11adc54f3; tracknick=%5Cu83DC%5Cu9E1F12382; _l_g_=Ug%3D%3D; sg=298; _nk_=%5Cu83DC%5Cu9E1F12382; cookie1=B0b4ewcg5B7xa%2BIFiJY%2FzduORi7VDGVYneQixqhz81I%3D; sn=; uc3=id2=UUpninsILY8h5Q%3D%3D&lg2=URm48syIIVrSKA%3D%3D&vt3=F8dD2ErrRHYQVTyD3qg%3D&nk2=0Qmppfx4HVLz; existShop=MTc0MzkxMDc3MQ%3D%3D; uc4=nk4=0%400zGr6qGZIj8DbFzX%2FHodycYAak8%3D&id4=0%40U2gtG%2BIA3wmRLKMQBQjmSIHY33eZ; _cc_=UtASsssmfA%3D%3D; havana_lgc_exp=1743941890606; fastSlient=1743910786606; _m_h5_tk=c5e0a00dbdc2baf2b4de819c378960cc_1743919802485; _m_h5_tk_enc=28950f286c03c2b7f0141910de4ad407; thw=cn; uc1=cookie16=W5iHLLyFPlMGbLDwA%2BdvAGZqLg%3D%3D&pas=0&cookie21=U%2BGCWk%2F7pY%2FF&cookie15=Vq8l%2BKCLz3%2F65A%3D%3D&cookie14=UoYaiGBxWQDPwA%3D%3D&existShop=false; isg=BEdHqmcg7C-S42ij_hE_zI5w3fkRTBsu1xnhDRk0Y1b9iGdKIRyrfoVaKE7We_Om; tfstk=g-fmMn6iKwnDUDZJe_ObKIxqKyBGlIO6vGh9XCKaU3-SDmhA7FRG4GUXDNrfj1xP7xhA6tHMQGIlHd59X14MG_Bx6nEXsNSpskEL9WQflIOavkUvQ3nB_FP9b8pVTT-oTkEL97QflCOavip-wHWv-eRZuFJ4ra-WRCl2Qjkr43ty_CRNbazk53-w_CSwNeiXDTW5au9wG0KjV01koKxnT3cisPLDnH72qUT5a59DYZ-o6k4rwSKD86r-hevcu6AAwoyRo3WV_IC42fjhq9RlwKgajEWV-69C_ujyLYkFnil6v7btCA92PUxpFHBM_LCnW8aurvSe3UTzkz4oCA92PUxLrzDnhK8WzrC..";
        String url = "https://e.tb.cn/h.6UymAEZ";
        TaobaoRoomChecker checker = new TaobaoRoomChecker();
        String streamUrl = checker.getStreamUrl(cookies, url);
        System.out.println(streamUrl);
    }
}
