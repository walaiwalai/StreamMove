package com.sh.engine.processor.checker;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.processor.recorder.Recorder;
import com.sh.engine.processor.recorder.StreamLinkRecorder;
import com.sh.engine.processor.recorder.VideoSegRecorder;
import com.sh.engine.util.DateUtil;
import com.sh.engine.util.RegexUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 视频相关信息：videoimg.afreecatv.com/php/SnapshotLoad.php?rowKey=20231228_E8F3995E_250550585_1_r
 * 分片视频：https://vod-archive-global-cdn-z02.afreecatv.com/v101/hls/vod/20231228/585/250550585/REGL_E8F3995E_250550585_1.smil/original/both/seg-3.ts
 * 总分片数地址：https://vod-archive-global-cdn-z02.afreecatv.com/v101/hls/vod/20231228/585/250550585/REGL_E8F3995E_250550585_1.smil/hd/both/playlist.m3u8
 *
 * @author caiWen
 * @date 2023/2/18 21:24
 */
@Component
@Slf4j
public class AfreecatvRoomChecker extends AbstractRoomChecker {
    private static final String BID_REGEX = "(?<=com/)([^/]+)$";
    private static final String TS_COUNT_REGEX = "seg-(\\d+)\\.ts";
    private static final String RECORD_HISTORY_URL = "https://chapi.sooplive.co.kr/api/%s/vods/all?page=1&per_page=50&orderby=reg_date&created=false";

    private static final String USER_HEADER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/119.0";

    @Override
    public Recorder getStreamRecorder(StreamerConfig streamerConfig) {
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

    private Recorder fetchTsUploadInfo(StreamerConfig streamerConfig) {
        String roomUrl = streamerConfig.getRoomUrl();
        String bid = RegexUtil.fetchMatchedOne(roomUrl, BID_REGEX);

        // 1. 获取历史直播列表
        JSONObject curVod = fetchCurVod(bid, streamerConfig);
        if (curVod == null) {
            return null;
        }

        // 2. 解析切片成链接格式
        Long titleNo = curVod.getLong("title_no");
        AfreecatvVodInfo afreecatvVodInfo = fetchTsViews(titleNo);
        return new VideoSegRecorder(afreecatvVodInfo.getBroadStartDate(), afreecatvVodInfo.getTsRecordInfos());
    }

    private AfreecatvVodInfo fetchTsViews(Long nTitleNo) {
        String playlistUrl = "https://api.m.sooplive.co.kr/station/video/a/view";
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
        if (StringUtils.isNotBlank(ConfigFetcher.getInitConfig().getSoopCookies())) {
            requestBuilder.addHeader("Cookie", ConfigFetcher.getInitConfig().getSoopCookies());
        }

        List<VideoSegRecorder.TsRecordInfo> views = Lists.newArrayList();
        Date broadStartDate = null;
        Response response = null;
        try {
            response = CLIENT.newCall(requestBuilder.build()).execute();
            if (response.isSuccessful()) {
                JSONObject respObj = JSONObject.parseObject(response.body().string());
                JSONArray files = respObj.getJSONObject("data").getJSONArray("files");
                broadStartDate = DateUtil.covertStr2Date(respObj.getString("broad_start"), DateUtil.YYYY_MM_DD_HH_MM_SS);
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
        AfreecatvVodInfo afreecatvVodInfo = new AfreecatvVodInfo();
        afreecatvVodInfo.setTsRecordInfos(views);
        afreecatvVodInfo.setBroadStartDate(broadStartDate);
        return afreecatvVodInfo;
    }

    private VideoSegRecorder.TsRecordInfo covertSingleView(JSONObject fileObj) {
        String file = fileObj.getJSONArray("quality_info").getJSONObject(0).getString("file");
        int index1 = file.lastIndexOf(":");
        int index2 = file.indexOf("/playlist.m3u8");
        String tsPrefix = "https://vod-archive-global-cdn-z02.sooplive.co.kr/v101/hls/" + file.substring(index1 + 1, index2);
        return fetchTsInfo(tsPrefix);
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

        Map<String, JSONObject> vodMap = lastedVods.stream()
                .collect(Collectors.toMap(vod -> vod.getString("title_no"), Function.identity(), (v1, v2) -> v2));
        // 根据获取的数量获取这个数量最前的lastedVods
        JSONObject curVod = null;
        if (CollectionUtils.isNotEmpty(streamerConfig.getCertainVodUrls())) {
            for (String vodUrl : streamerConfig.getCertainVodUrls()) {
                String videoId = vodUrl.split("player/")[1];
                if (vodMap.containsKey(videoId)) {
                    curVod = vodMap.get(videoId);
                }
            }
        } else {
            int lastVodCnt = streamerConfig.getLastVodCnt() > 0 ? streamerConfig.getLastVodCnt() : 1;
            int index = lastedVods.size() <= lastVodCnt ? lastedVods.size() - 1 : lastVodCnt - 1;
            curVod = lastedVods.get(index);
        }
        return curVod;
    }

    private VideoSegRecorder.TsRecordInfo fetchTsInfo(String tsPrefix) {
        String playlistUrl = tsPrefix + "/original/both/playlist.m3u8";
        Request.Builder requestBuilder = new Request.Builder()
                .url(playlistUrl)
                .get()
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/119.0")
                .addHeader("Accept-Language", "zh-CN,zh;q=0.9");
//        if (StringUtils.isNotBlank(ConfigFetcher.getInitConfig().getAfreecaTvCookies())) {
//            requestBuilder.addHeader("Cookie", ConfigFetcher.getInitConfig().getAfreecaTvCookies());
//        }
        Response response = null;
        try {
            response = CLIENT.newCall(requestBuilder.build()).execute();
            if (response.isSuccessful()) {
                String resp = response.body().string();
                String[] lines = StringUtils.split(resp, "\n");
                String lastSegFile = lines[lines.length - 2];
                String s = RegexUtil.fetchMatchedOne(lastSegFile, TS_COUNT_REGEX);
                return VideoSegRecorder.TsRecordInfo.builder()
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


    private Recorder fetchOnlineLivingInfo(StreamerConfig streamerConfig) {
        boolean isLiving = checkIsLivingByStreamLink(streamerConfig.getRoomUrl());
        Date date = new Date();
        return isLiving ? new StreamLinkRecorder(date, streamerConfig.getRoomUrl()) : null;
    }

    @Data
    private static class AfreecatvVodInfo {
        private Date broadStartDate;
        private List<VideoSegRecorder.TsRecordInfo> tsRecordInfos;

    }

}
