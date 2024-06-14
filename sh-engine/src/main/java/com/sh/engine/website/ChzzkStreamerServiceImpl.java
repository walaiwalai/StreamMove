package com.sh.engine.website;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Maps;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.HttpClientUtil;
import com.sh.engine.StreamChannelTypeEnum;
import com.sh.engine.model.record.RecordStream;
import com.sh.engine.util.DateUtil;
import com.sh.engine.util.RegexUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.util.Map;

/**
 * @Author caiwen
 * @Date 2024 03 17 14 18
 * https://github.com/streamlink/streamlink/blob/master/src/streamlink/plugins/chzzk.py#L101
 **/
@Component
@Slf4j
public class ChzzkStreamerServiceImpl extends AbstractStreamerService {
    private static final String CHANNEL_REGEX = "/(\\p{XDigit}{32})$";
    private static final String API_URL = "https://api.chzzk.naver.com/service/v2/channels/{channel_name}/live-detail";
    private static final String VIDEOS_URL = "https://api.chzzk.naver.com/service/v2/videos/{video_no}";
    private static final String API_VOD_PLAYBACK_URL = "https://apis.naver.com/neonplayer/vodplay/v2/playback/{video_id}?key={in_key}";
    private static final String LATEST_VIDEO_URL = "https://api.chzzk.naver.com/service/v1/channels/{channel_name}/videos?sortType=LATEST&pagingType=PAGE&page=0&size=24&publishDateAt=&videoType=";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Whale/3.23.214.17 Safari/537.36";

    @Override
    public RecordStream isRoomOnline(StreamerConfig streamerConfig) {
        if (BooleanUtils.isTrue(streamerConfig.isRecordWhenOnline())) {
            return fetchOnlineStream(streamerConfig);
        } else {
            return fetchReplayStream(streamerConfig);
        }
    }

    @Override
    public StreamChannelTypeEnum getType() {
        return StreamChannelTypeEnum.CHZZK;
    }

    private RecordStream fetchOnlineStream(StreamerConfig streamerConfig) {
        String channelName = RegexUtil.fetchMatchedOne(streamerConfig.getRoomUrl(), CHANNEL_REGEX);
        String detailUrl = API_URL.replace("{channel_name}", channelName);

        String resp = HttpClientUtil.sendGet(detailUrl, buildHeaders(), null, false);
        JSONObject respObj = JSONObject.parseObject(resp);
        JSONObject contentObj = respObj.getJSONObject("content");
        if (!StringUtils.equals(contentObj.getString("status"), "OPEN")) {
            // 没有直播
            return null;
        }
        JSONObject playInfoObj = JSON.parseObject(contentObj.getString("livePlaybackJson"));
        String streamUrl = playInfoObj.getJSONArray("media").stream()
                .filter(item -> {
                    JSONObject itemObj = (JSONObject) item;
                    return StringUtils.equals(itemObj.getString("protocol"), "HLS") && StringUtils.equals(itemObj.getString("mediaId"), "HLS");
                })
                .map(item -> ((JSONObject) item).getString("path"))
                .findFirst()
                .orElse(null);

//        String streamUrl = playInfoObj.getJSONArray("media").getJSONObject(0).getString("path");
        return RecordStream.builder()
                .livingStreamUrl(streamUrl)
                .anchorName(contentObj.getJSONObject("channel").getString("channelName"))
                .roomTitle(contentObj.getString("liveTitle"))
                .build();
    }

    private RecordStream fetchReplayStream(StreamerConfig streamerConfig) {
        // 1.获取最近的videoNo
        String videoNo = getLatestVideoNo(streamerConfig);

        // 2.获取视频详情
        String detailUrl = VIDEOS_URL.replace("{video_no}", videoNo);
        String resp = HttpClientUtil.sendGet(detailUrl, buildHeaders(), null, false);
        JSONObject respObj = JSONObject.parseObject(resp);

        // 2.1 最新发布时间, 已经录过跳过
        JSONObject contentObj = respObj.getJSONObject("content");
        String regDate = contentObj.getString("publishDate");
        boolean isNewTs = checkVodIsNew(streamerConfig, regDate);
        if (!isNewTs) {
            return null;
        }

        // 2.2 获取录播流地址xml信息
        String vid = contentObj.getString("videoId");
        String inKey = contentObj.getString("inKey");
        String playbackUrl = API_VOD_PLAYBACK_URL.replace("{video_id}", vid)
                .replace("{in_key}", inKey);
        Map<String, String> headers = Maps.newHashMap();
        headers.put("Accept", "application/dash+xml");
        String replayXml = HttpClientUtil.sendGet(playbackUrl, headers, null, false);

        return RecordStream.builder()
                .roomTitle(contentObj.getString("videoTitle"))
                .regDate(DateUtil.covertStr2Date(regDate, DateUtil.YYYY_MM_DD_HH_MM_SS))
                .latestReplayStreamUrl(parseReplayStreamUrl(replayXml))
                .build();
    }

    private String getLatestVideoNo(StreamerConfig streamerConfig) {
        String channelName = RegexUtil.fetchMatchedOne(streamerConfig.getRoomUrl(), CHANNEL_REGEX);

        // 获取最近发布的一个视频，获取videoId
        String latestVideoUrl = LATEST_VIDEO_URL.replace("{channel_name}", channelName);
        String latestResp = HttpClientUtil.sendGet(latestVideoUrl, buildHeaders(), null, false);
        JSONObject respObj = JSONObject.parseObject(latestResp);
        JSONObject contentObj = respObj.getJSONObject("content");

        if (contentObj.getInteger("totalCount") < 1) {
            return null;
        } else {
            return contentObj.getJSONArray("data").getJSONObject(0).getString("videoNo");
        }
    }

    private String parseReplayStreamUrl(String xmlString) {
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        dbFactory.setNamespaceAware(true);
        DocumentBuilder dBuilder;
        try {
            dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(new java.io.ByteArrayInputStream(xmlString.getBytes()));
            NodeList mpdElements = doc.getElementsByTagNameNS("urn:mpeg:dash:schema:mpd:2011", "BaseURL");
            if (mpdElements.getLength() > 0) {
                return mpdElements.item(0).getTextContent();
            }

            NodeList nvodElements = doc.getElementsByTagNameNS("urn:naver:vod:2020", "BaseURL");
            if (nvodElements.getLength() > 0) {
                return nvodElements.item(0).getTextContent();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse XML", e);
        }
        return null;
    }

    private Map<String, String> buildHeaders() {
        String chzzkCookies = ConfigFetcher.getInitConfig().getChzzkCookies();
        Map<String, String> headers = Maps.newHashMap();
        headers.put("User-Agent", USER_AGENT);
        if (StringUtils.isNotBlank(chzzkCookies)) {
            headers.put("Cookie", chzzkCookies);
        }
        return headers;
    }

    public static void main(String[] args) {
        ChzzkStreamerServiceImpl service = new ChzzkStreamerServiceImpl();
        RecordStream livingStreamer = service.isRoomOnline(StreamerConfig.builder()
                .recordWhenOnline(false)
                .roomUrl("https://chzzk.naver.com/a121dab6835c0613dd5f8ef5acd1f155")
                .build());
        System.out.println(JSON.toJSONString(livingStreamer));
    }
}
