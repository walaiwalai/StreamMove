package com.sh.engine.processor.checker;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.processor.recorder.Recorder;
import com.sh.engine.processor.recorder.StreamLinkRecorder;
import com.sh.engine.processor.recorder.VideoSegRecorder;
import com.sh.engine.util.DateUtil;
import com.sh.engine.util.RegexUtil;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Date;
import java.util.List;

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
    private static final String RECORD_HISTORY_URL = "https://chapi.sooplive.co.kr/api/%s/home";

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
        JSONObject lastedRecord = fetchLastedRecord(bid);
        Date date = DateUtil.covertStr2Date(lastedRecord.getString("reg_date"), DateUtil.YYYY_MM_DD_HH_MM_SS);
        boolean isNewTs = checkVodIsNew(streamerConfig, date);
        if (!isNewTs) {
            return null;
        }

        // 2. 解析切片成链接格式
        Long titleNo = lastedRecord.getLong("title_no");
        List<VideoSegRecorder.TsRecordInfo> tsRecordInfos = fetchTsViews(titleNo);

        return new VideoSegRecorder(date, tsRecordInfos);
    }

    private List<VideoSegRecorder.TsRecordInfo> fetchTsViews(Long nTitleNo) {
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
//        if (StringUtils.isNotBlank(ConfigFetcher.getInitConfig().getAfreecaTvCookies())) {
//            requestBuilder.addHeader("Cookie", ConfigFetcher.getInitConfig().getAfreecaTvCookies());
//        }

        List<VideoSegRecorder.TsRecordInfo> views = Lists.newArrayList();
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

    private VideoSegRecorder.TsRecordInfo covertSingleView(JSONObject fileObj) {
        String file = fileObj.getJSONArray("quality_info").getJSONObject(0).getString("file");
        int index1 = file.lastIndexOf(":");
        int index2 = file.indexOf("/playlist.m3u8");
        String tsPrefix = "https://vod-archive-global-cdn-z02.sooplive.co.kr/v101/hls/" + file.substring(index1 + 1, index2);
        return fetchTsInfo(tsPrefix);
    }

    private JSONObject fetchLastedRecord(String bid) {
        Request.Builder requestBuilder = new Request.Builder()
                .url(String.format(RECORD_HISTORY_URL, bid))
                .get()
                .addHeader("User-Agent", USER_HEADER)
                .addHeader("Accept-Language", "zh-CN,zh;q=0.9");
//        if (StringUtils.isNotBlank(ConfigFetcher.getInitConfig().getAfreecaTvCookies())) {
//            requestBuilder.addHeader("Cookie", ConfigFetcher.getInitConfig().getAfreecaTvCookies());
//        }
        Response response = null;
        try {
            response = CLIENT.newCall(requestBuilder.build()).execute();
            if (response.isSuccessful()) {
                String resp = response.body().string();
                return JSONObject.parseObject(resp).getJSONArray("vods").getJSONObject(0);
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
}
