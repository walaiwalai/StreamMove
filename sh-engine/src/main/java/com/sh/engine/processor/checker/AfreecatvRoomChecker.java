package com.sh.engine.processor.checker;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.google.common.collect.Lists;
import com.sh.config.manager.CacheManager;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.DateUtil;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.processor.recorder.Recorder;
import com.sh.engine.processor.recorder.StreamLinkRecorder;
import com.sh.engine.processor.recorder.VodM3u8Recorder;
import com.sh.engine.util.RegexUtil;
import com.sh.message.constant.MessageConstant;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * soop直播平台
 *
 * @author caiWen
 * @date 2023/2/18 21:24
 */
@Component
@Slf4j
public class AfreecatvRoomChecker extends AbstractRoomChecker {
    @Resource
    private CacheManager cacheManager;

    private static final String BID_REGEX = "(?<=com/)([^/]+)$";
    private static final String RECORD_HISTORY_URL = "https://chapi.sooplive.co.kr/api/%s/vods/all?page=1&per_page=50&orderby=reg_date&created=false";

    private static final String USER_HEADER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/119.0";

    @Override
    public Recorder getStreamRecorder(StreamerConfig streamerConfig) {
        if (BooleanUtils.isTrue(streamerConfig.isRecordWhenOnline())) {
            return fetchOnlineLivingInfo(streamerConfig);
        } else {
            if (CollectionUtils.isNotEmpty(streamerConfig.getCertainVodUrls())) {
                return fetchCertainTsUploadInfo(streamerConfig);
            } else {
                return fetchVodInfo(streamerConfig);
            }
        }
    }

    @Override
    public StreamChannelTypeEnum getType() {
        return StreamChannelTypeEnum.AFREECA_TV;
    }

    private Recorder fetchCertainTsUploadInfo(StreamerConfig streamerConfig) {
        String key = "certain_keys_" + streamerConfig.getName();
        String videoId = null;
        for (String vodUrl : streamerConfig.getCertainVodUrls()) {
            String vid = vodUrl.split("player/")[1];
            String finishFlag = cacheManager.getHash(key, vid, new TypeReference<String>() {
            });
            if (StringUtils.isBlank(finishFlag)) {
                videoId = vid;
                break;
            }
        }
        if (videoId == null) {
            return null;
        }

        // 2. 解析切片成链接格式
        String curVodUrl = "https://vod.sooplive.co.kr/player/" + videoId;
        JSONObject curVod = fetchCurVodInfo(videoId);

        Date date = DateUtil.covertStr2Date(curVod.getJSONObject("data").getString("broad_start"), DateUtil.YYYY_MM_DD_HH_MM_SS);
        Map<String, String> extra = new HashMap<>();
        extra.put("finishKey", key);
        extra.put("finishField", videoId);

        return new VodM3u8Recorder(date, getType().getType(), curVodUrl, extra);
    }

    private Recorder fetchVodInfo(StreamerConfig streamerConfig) {
        String roomUrl = streamerConfig.getRoomUrl();
        String bid = RegexUtil.fetchMatchedOne(roomUrl, BID_REGEX);

        // 1. 获取历史直播列表
        JSONObject curVod = fetchCurVod(bid, streamerConfig);
        if (curVod == null) {
            return null;
        }

        // 2. 解析切片成链接格式
        Long titleNo = curVod.getLong("title_no");
        Date date = DateUtil.covertStr2Date(curVod.getString("reg_date"), DateUtil.YYYY_MM_DD_HH_MM_SS);
        String vodUrl = "https://vod.sooplive.co.kr/player/" + titleNo;
        return new VodM3u8Recorder(date, getType().getType(), vodUrl);
    }

    private JSONObject fetchCurVodInfo(String videoId) {
        String playlistUrl = "https://api.m.sooplive.co.kr/station/video/a/view";
        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("nTitleNo", videoId)
                .addFormDataPart("nApiLevel", "10")
                .addFormDataPart("nPlaylistIdx", "0")
                .build();
        Request.Builder requestBuilder = new Request.Builder()
                .url(playlistUrl)
                .post(body)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/119.0")
                .addHeader("Accept-Language", "zh-CN,zh;q=0.9");
        if (StringUtils.isNotBlank(ConfigFetcher.getInitConfig().getSoopCookies())) {
            requestBuilder.addHeader("Cookie", ConfigFetcher.getInitConfig().getSoopCookies());
        }

        Response response = null;
        try {
            response = CLIENT.newCall(requestBuilder.build()).execute();
            if (response.isSuccessful()) {
                return JSONObject.parseObject(response.body().string());
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

    private JSONObject fetchCurVod(String bid, StreamerConfig streamerConfig) {
        Request.Builder requestBuilder = new Request.Builder()
                .url(String.format(RECORD_HISTORY_URL, bid))
                .get()
                .addHeader("User-Agent", USER_HEADER)
                .addHeader("Accept-Language", "zh-CN,zh;q=0.9");
        Response response = null;
        try {
            response = CLIENT.newCall(requestBuilder.build()).execute();
            if (response.isSuccessful()) {
                String resp = response.body().string();
                JSONArray vodObjs = JSONObject.parseObject(resp).getJSONArray("data");
                return filterCurHistoryVod(streamerConfig, vodObjs);
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

    /**
     * 根据当前用户配置获取当前需要录制的视频
     *
     * @param streamerConfig 用户配置
     * @param vodObjs        用户的历史视频列表
     * @return 当前需要录制的视频
     */
    private JSONObject filterCurHistoryVod(StreamerConfig streamerConfig, JSONArray vodObjs) {
        if (CollectionUtils.isEmpty(vodObjs)) {
            return null;
        }

        List<JSONObject> lastedVods = Lists.newArrayList();
        for (Object vodObj : vodObjs) {
            JSONObject vodInfo = (JSONObject) vodObj;
            Date date = DateUtil.covertStr2Date(vodInfo.getString("reg_date"), DateUtil.YYYY_MM_DD_HH_MM_SS);
            if (checkVodIsNew(streamerConfig, date)) {
                lastedVods.add(vodInfo);
            }
        }
        if (CollectionUtils.isEmpty(lastedVods)) {
            return null;
        }

        // 根据获取的数量获取这个数量最前的lastedVods
        return lastedVods.get(0);
    }


    private Recorder fetchOnlineLivingInfo(StreamerConfig streamerConfig) {
        if (BooleanUtils.isTrue(streamerConfig.isOnlinePushCheck())) {
            // 有些主播过多检测会封IP，通过检测有无开播消息推送，减少服务器检测次数
            if (!checkHasOnlinePushMsg(streamerConfig)) {
                return null;
            }
        }
        boolean isLiving = checkIsLivingByStreamLink(streamerConfig.getRoomUrl());
        Date date = new Date();
        return isLiving ? new StreamLinkRecorder(date, getType().getType(), streamerConfig.getRoomUrl()) : null;
    }

    private boolean checkHasOnlinePushMsg(StreamerConfig streamerConfig) {
        String flag = cacheManager.get(MessageConstant.PLAT_PUSH_LIVE_PREFIX_KEY + MessageConstant.SOOP_PLATFORM);
        return StringUtils.isNotBlank(flag);
    }
}
