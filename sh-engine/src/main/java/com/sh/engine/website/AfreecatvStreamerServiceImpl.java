package com.sh.engine.website;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.HttpClientUtil;
import com.sh.engine.StreamChannelTypeEnum;
import com.sh.engine.model.record.LivingStreamer;
import com.sh.engine.model.record.TsRecordInfo;
import com.sh.engine.util.RegexUtil;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 *
 * 视频相关信息：videoimg.afreecatv.com/php/SnapshotLoad.php?rowKey=20231228_E8F3995E_250550585_1_r
 * 分片视频：https://vod-archive-global-cdn-z02.afreecatv.com/v101/hls/vod/20231228/585/250550585/REGL_E8F3995E_250550585_1.smil/original/both/seg-3.ts
 * 总分片数地址：https://vod-archive-global-cdn-z02.afreecatv.com/v101/hls/vod/20231228/585/250550585/REGL_E8F3995E_250550585_1.smil/hd/both/playlist.m3u8
 * @author caiWen
 * @date 2023/2/18 21:24
 */
@Component
@Slf4j
public class AfreecatvStreamerServiceImpl extends AbstractStreamerService {
    private static final String USER_URL = "http://api.m.afreecatv.com/broad/a/watch";
    private static final String BID_REGEX = "(?<=com/)([^/]+)$";
    private static final String TS_COUNT_REGEX = "seg-(\\d+)\\.ts";
    private static final String RECORD_HISTORY_URL = "https://bjapi.afreecatv.com/api/%s/vods/review?page=1&per_page=20&orderby=reg_date";

    private static final String USER_HEADER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/119.0";

    @Override
    public LivingStreamer isRoomOnline(StreamerConfig streamerConfig) {
        if (BooleanUtils.isTrue(streamerConfig.isRecordWhenOnline())) {
            return fetchOnlineLivingInfo(streamerConfig);
        } else {
            return fetchTsUploadInfo(streamerConfig);
        }
    }

    @Override
    public StreamChannelTypeEnum getType() {
        return StreamChannelTypeEnum.AFREECA_TV;
    }

    private LivingStreamer fetchTsUploadInfo(StreamerConfig streamerConfig) {
        String roomUrl = streamerConfig.getRoomUrl();
        String bid = RegexUtil.fetchMatchedOne(roomUrl, BID_REGEX);

        // 1. 获取历史直播列表
        JSONObject lastedRecord = fetchLastedRecord(bid);
        String regDate = lastedRecord.getString("reg_date");
        boolean isNewTs = checkIsNew(streamerConfig, regDate);
        if (!isNewTs) {
            return null;
        }

        log.info("new ts record upload for {}", streamerConfig.getName());
        // 2. 解析切片成链接格式
        Long titleNo = lastedRecord.getLong("title_no");
        List<TsRecordInfo> tsRecordInfos = fetchTsViews(titleNo);
        if (tsRecordInfos != null) {
            for (TsRecordInfo tsRecordInfo : tsRecordInfos) {
                try {
                    tsRecordInfo.setRegDate(DateUtils.parseDate(regDate, "yyyy-MM-dd HH:mm:ss"));
                } catch (ParseException e) {
                }
            }
        }

        return LivingStreamer.builder()
                .tsViews(tsRecordInfos)
                .build();

    }

    private List<TsRecordInfo> fetchTsViews(Long nTitleNo) {
        String playlistUrl = "https://api.m.afreecatv.com/station/video/a/view";
        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("nTitleNo", nTitleNo + "")
                .addFormDataPart("nApiLevel", "10")
                .addFormDataPart("nPlaylistIdx", "0")
                .build();
        Request.Builder requestBuilder = new Request.Builder()
                .url(playlistUrl)
                .post(body)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/119.0")
                .addHeader("Accept-Language", "zh-CN,zh;q=0.9");
        if (StringUtils.isNotBlank(ConfigFetcher.getInitConfig().getAfreecaTvCookies())) {
            requestBuilder.addHeader("Cookie", ConfigFetcher.getInitConfig().getAfreecaTvCookies());
        }

        List<TsRecordInfo> views = Lists.newArrayList();
        Response response = null;
        try {
            response = CLIENT.newCall(requestBuilder.build()).execute();
            if (response.isSuccessful()) {
                String resp = response.body().string();
                JSONArray files = JSONObject.parseObject(resp).getJSONObject("data").getJSONArray("files");
                for (int i = 0; i < files.size(); i++) {
                    views.add(covertSingleView(files.getJSONObject(i)));
                }
            } else {
                String message = response.message();
                String bodyStr = response.body() != null ? response.body().string() : null;
                log.error("query user info failed, message: {}, body: {}", message, bodyStr);
                return null;
            }
        } catch (IOException e) {
            log.error("query playlist success, playlistUrl: {}", playlistUrl, e);
            return null;
        }
        return views;
    }

    private TsRecordInfo covertSingleView(JSONObject fileObj) {
        String file = fileObj.getJSONArray("quality_info").getJSONObject(0).getString("file");
        int index1 = file.lastIndexOf(":");
        int index2 = file.indexOf("/playlist.m3u8");
        String tsPrefix = "https://vod-archive-global-cdn-z02.afreecatv.com/v101/hls/" + file.substring(index1 + 1, index2);
        return fetchTsInfo(tsPrefix);
    }

    private boolean checkIsNew(StreamerConfig streamerConfig, String tsRegDate) {
        if (StringUtils.isBlank(streamerConfig.getLastRecordTime())) {
            return true;
        }
        String lastRecordTime = streamerConfig.getLastRecordTime();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        try {
            Date date1 = dateFormat.parse(lastRecordTime);
            Date date2 = dateFormat.parse(tsRegDate);
            return date1.getTime() < date2.getTime();
        } catch (Exception e) {
        }
        return false;
    }

    private JSONObject fetchLastedRecord(String bid) {
        Request.Builder requestBuilder = new Request.Builder()
                .url(String.format(RECORD_HISTORY_URL, bid))
                .get()
                .addHeader("User-Agent", USER_HEADER)
                .addHeader("Accept-Language", "zh-CN,zh;q=0.9");
        if (StringUtils.isNotBlank(ConfigFetcher.getInitConfig().getAfreecaTvCookies())) {
            requestBuilder.addHeader("Cookie", ConfigFetcher.getInitConfig().getAfreecaTvCookies());
        }
        Response response = null;
        try {
            response = CLIENT.newCall(requestBuilder.build()).execute();
            if (response.isSuccessful()) {
                String resp = response.body().string();
                return JSONObject.parseObject(resp).getJSONArray("data").getJSONObject(0);
            } else {
                String message = response.message();
                String bodyStr = response.body() != null ? response.body().string() : null;
                log.error("query user info failed, message: {}, body: {}", message, bodyStr);
                return null;
            }
        } catch (IOException e) {
            log.error("query user info error, bid: {}", bid, e);
            return null;
        }
    }

    private TsRecordInfo fetchTsInfo(String tsPrefix) {
        String playlistUrl = tsPrefix + "/original/both/playlist.m3u8";
        Request.Builder requestBuilder = new Request.Builder()
                .url(playlistUrl)
                .get()
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/119.0")
                .addHeader("Accept-Language", "zh-CN,zh;q=0.9");
        if (StringUtils.isNotBlank(ConfigFetcher.getInitConfig().getAfreecaTvCookies())) {
            requestBuilder.addHeader("Cookie", ConfigFetcher.getInitConfig().getAfreecaTvCookies());
        }
        Response response = null;
        try {
            response = CLIENT.newCall(requestBuilder.build()).execute();
            if (response.isSuccessful()) {
                String resp = response.body().string();
                String[] lines = StringUtils.split(resp, "\n");
                String lastSegFile = lines[lines.length - 2];
                String s = RegexUtil.fetchMatchedOne(lastSegFile, TS_COUNT_REGEX);
                return TsRecordInfo.builder()
                        .tsFormatUrl(tsPrefix + "/original/both/seg-%s.ts")
                        .count(Integer.valueOf(s))
                        .build();
            } else {
                String message = response.message();
                String bodyStr = response.body() != null ? response.body().string() : null;
                log.error("query user info failed, message: {}, body: {}", message, bodyStr);
                return null;
            }
        } catch (IOException e) {
            log.error("query playlist success, playlistUrl: {}", playlistUrl, e);
            return null;
        }
    }


    private LivingStreamer fetchOnlineLivingInfo(StreamerConfig streamerConfig) {
        String roomUrl = streamerConfig.getRoomUrl();
        String bid = RegexUtil.fetchMatchedOne(roomUrl, BID_REGEX);

        JSONObject userRespObj = fetchUserInfo(bid);
        if (userRespObj == null) {
            return null;
        }

        if (userRespObj.getInteger("result") != 1) {
            return null;
        }

        String anchorName = userRespObj.getJSONObject("data").getString("user_nick");
        String boardNo = userRespObj.getJSONObject("data").getString("broad_no");
        String hlsAuthenticationKey = userRespObj.getJSONObject("data").getString("hls_authentication_key");
        String viewUrl = fetchCdnUrl(boardNo);
        String m3u8Url = StringUtils.isNotBlank(viewUrl) ? viewUrl + "?aid=" + hlsAuthenticationKey : null;
        return LivingStreamer.builder()
                .streamUrl(m3u8Url)
                .anchorName(anchorName)
                .build();
    }

    private JSONObject fetchUserInfo(String bid) {
        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("bj_id", bid)
                .addFormDataPart("agent", "web")
                .addFormDataPart("confirm_adult", "true")
                .addFormDataPart("player_type", "webm")
                .addFormDataPart("mode", "live")
                .build();

        Request.Builder requestBuilder = new Request.Builder()
                .url(USER_URL)
                .post(body)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/119.0")
                .addHeader("Accept-Language", "zh-CN,zh;q=0.8,zh-TW;q=0.7,zh-HK;q=0.5,en-US;q=0.3,en;q=0.2")
                .addHeader("Referer", "https://m.afreecatv.com/")
                .addHeader("Content-Type", "application/x-www-form-urlencoded");

        if (StringUtils.isNotBlank(ConfigFetcher.getInitConfig().getAfreecaTvCookies())) {
            requestBuilder.addHeader("Cookie", ConfigFetcher.getInitConfig().getAfreecaTvCookies());
        }
        Response response = null;
        try {
            response = CLIENT.newCall(requestBuilder.build()).execute();
            if (response.isSuccessful()) {
                String resp = response.body().string();
                log.info("query user info success, resp: {}", resp);
                return JSONObject.parseObject(resp);
            } else {
                String message = response.message();
                String bodyStr = response.body() != null ? response.body().string() : null;
                log.error("query user info failed, message: {}, body: {}", message, bodyStr);
                return null;
            }
        } catch (IOException e) {
            log.error("query user info error, bid: {}", bid, e);
            return null;
        }
    }

    private String fetchCdnUrl(String boardNo) {
        Map<String, String> params = new HashMap<>();
        params.put("return_type", "gcp_cdn");
        params.put("use_cors", "false");
        params.put("cors_origin_url", "play.afreecatv.com");
        params.put("broad_key", boardNo + "-common-master-hls");
        params.put("time", "8361.086329376785");

        String apiUrl = "http://livestream-manager.afreecatv.com/broad_stream_assign.html?" + HttpClientUtil.encodeParams(params);
        Request.Builder requestBuilder = new Request.Builder()
                .url(apiUrl)
                .get()
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/119.0")
                .addHeader("Accept-Language", "zh-CN,zh;q=0.8,zh-TW;q=0.7,zh-HK;q=0.5,en-US;q=0.3,en;q=0.2")
                .addHeader("Referer", "https://play.afreecatv.com/oul282/249469582")
                .addHeader("Content-Type", "application/x-www-form-urlencoded");
        if (StringUtils.isNotBlank(ConfigFetcher.getInitConfig().getAfreecaTvCookies())) {
            requestBuilder.addHeader("Cookie", ConfigFetcher.getInitConfig().getAfreecaTvCookies());
        }
        Response response = null;
        try {
            response = CLIENT.newCall(requestBuilder.build()).execute();
            if (response.isSuccessful()) {
                String resp = response.body().string();
                log.info("user living info success, resp: {}", resp);
                return JSONObject.parseObject(resp).getString("view_url");
            } else {
                String message = response.message();
                String bodyStr = response.body() != null ? response.body().string() : null;
                log.error("query user living info failed, message: {}, body: {}", message, bodyStr);
                return null;
            }
        } catch (IOException e) {
            log.error("query user living info error, boardNo: {}", boardNo, e);
            return null;
        }
    }

    public static void main(String[] args) {
        AfreecatvStreamerServiceImpl service = new AfreecatvStreamerServiceImpl();
        LivingStreamer s = service.isRoomOnline(StreamerConfig.builder().recordWhenOnline(false).roomUrl("https://play.afreecatv.com/tldn031").build());
        System.out.println(JSON.toJSONString(s));

    }
}
