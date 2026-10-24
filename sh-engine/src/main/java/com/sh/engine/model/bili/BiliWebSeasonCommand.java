package com.sh.engine.model.bili;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.manager.CommonParamCache;
import com.sh.config.model.config.CommonParamKey;
import com.sh.config.utils.OkHttpClientUtil;
import com.sh.config.utils.PictureFileUtil;
import com.sh.engine.model.StreamerInfoHolder;
import com.sh.engine.service.OssUploadService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 使用 B站创作中心接口，将已投稿稿件加入主播同名合集。
 */
@Slf4j
@Component
public class BiliWebSeasonCommand {
    private static final String MEMBER_URL = "https://member.bilibili.com";
    private static final int PAGE_SIZE = 30;
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    @Resource
    private CommonParamCache commonParamCache;

    @Resource
    private OssUploadService ossUploadService;

    /**
     * 查询主播合集，必要时创建，再将稿件的每个分 P 加入合集。重复执行不会重复添加已有分 P。
     *
     * @param aid 已投稿稿件 ID
     * @param cookies 当前投稿使用的 B 站 Cookie
     */
    public void addWorkToStreamerSeason(long aid, String cookies) {
        String streamerName = StreamerInfoHolder.getCurStreamerName();
        String csrf = StringUtils.substringBetween(cookies + ";", "bili_jct=", ";");
        if (StringUtils.isBlank(cookies) || StringUtils.isBlank(csrf)) {
            throw new StreamerRecordException(ErrorEnum.BILI_SEASON_ERROR,
                    "Missing Bilibili login Cookie or CSRF token for season request");
        }

        JSONObject archive = request(MEMBER_URL + "/x/vupre/web/archive/view?aid=" + aid, cookies, null)
                .getJSONObject("data");
        if (archive == null || archive.getJSONObject("archive") == null
                || archive.getJSONArray("videos") == null || archive.getJSONArray("videos").isEmpty()) {
            throw new StreamerRecordException(ErrorEnum.BILI_SEASON_ERROR,
                    "Cannot load posted Bilibili archive, aid=" + aid);
        }

        JSONObject season = findSeason(streamerName, cookies);
        if (season == null) {
            String coverKey = commonParamCache.get(CommonParamKey.BILI_SEASON_COVER_URL);
            if (StringUtils.isBlank(coverKey)) {
                throw new StreamerRecordException(ErrorEnum.BILI_SEASON_ERROR,
                        "Missing common parameter " + CommonParamKey.BILI_SEASON_COVER_URL.getKey()
                                + " for Bilibili season, streamer=" + streamerName);
            }
            String imageDataUrl = PictureFileUtil.createTextWithVeilOnImage(
                    ossUploadService.getImageDataUrl(coverKey), streamerName);
            String cover = BiliWebWorkPostCommand.uploadCover(imageDataUrl, cookies, ErrorEnum.BILI_SEASON_ERROR);
            createSeason(streamerName, cover, cookies, csrf);
            season = findSeason(streamerName, cookies);
            if (season == null) {
                throw new StreamerRecordException(ErrorEnum.BILI_SEASON_ERROR,
                        "Created Bilibili season is missing from season list, streamer=" + streamerName);
            }
        }

        JSONObject sections = season.getJSONObject("sections");
        JSONArray sectionList = sections == null ? null : sections.getJSONArray("sections");
        if (sectionList == null || sectionList.isEmpty()) {
            throw new StreamerRecordException(ErrorEnum.BILI_SEASON_ERROR,
                    "Bilibili season has no available section, streamer=" + streamerName);
        }
        long sectionId = sectionList.getJSONObject(0).getLongValue("id");
        addMissingEpisodes(aid, archive, sectionId, cookies, csrf);
        log.info("Bilibili archive is in streamer season, streamer: {}, aid: {}, seasonId: {}",
                streamerName, aid, season.getJSONObject("season").getLongValue("id"));
    }

    /**
     * 按页查找当前账号下与主播名称完全相同的合集。
     */
    private JSONObject findSeason(String streamerName, String cookies) {
        int page = 1;
        while (true) {
            JSONObject data = request(MEMBER_URL + "/x2/creative/web/seasons?pn=" + page
                    + "&ps=" + PAGE_SIZE, cookies, null).getJSONObject("data");
            JSONArray seasons = data == null ? null : data.getJSONArray("seasons");
            if (seasons == null) {
                if (data != null && data.getIntValue("total") == 0) {
                    return null;
                }
                throw new StreamerRecordException(ErrorEnum.BILI_SEASON_ERROR,
                        "Bilibili season list response is missing seasons");
            }
            for (int i = 0; i < seasons.size(); i++) {
                JSONObject item = seasons.getJSONObject(i);
                JSONObject season = item.getJSONObject("season");
                if (season != null && streamerName.equals(season.getString("title"))) {
                    return item;
                }
            }
            if (page * PAGE_SIZE >= data.getIntValue("total")) {
                return null;
            }
            page++;
        }
    }

    /**
     * 创建主播同名合集，使用 OSS 图片上传后获得的 B 站封面地址。
     */
    private void createSeason(String streamerName, String cover, String cookies, String csrf) {
        RequestBody body = new FormBody.Builder()
                .add("title", streamerName)
                .add("cover", cover)
                .add("season_price", "0")
                .add("csrf", csrf)
                .build();
        JSONObject response = request(MEMBER_URL + "/x2/creative/web/season/add", cookies, body);
        if (response.getLong("data") == null || response.getLongValue("data") <= 0) {
            throw new StreamerRecordException(ErrorEnum.BILI_SEASON_ERROR,
                    "Bilibili season creation returned no season ID, streamer=" + streamerName);
        }
        log.info("Created Bilibili streamer season, streamer: {}, seasonId: {}",
                streamerName, response.getLongValue("data"));
    }

    /**
     * 查询小节中已有的分 P，只提交尚未加入的分 P。
     */
    private void addMissingEpisodes(long aid, JSONObject archive, long sectionId, String cookies, String csrf) {
        JSONObject sectionData = request(MEMBER_URL + "/x2/creative/web/season/section?id=" + sectionId,
                cookies, null).getJSONObject("data");
        JSONArray existing = sectionData == null ? null : sectionData.getJSONArray("episodes");
        if (existing == null) {
            JSONObject section = sectionData == null ? null : sectionData.getJSONObject("section");
            if (section == null || section.getIntValue("epCount") != 0) {
                throw new StreamerRecordException(ErrorEnum.BILI_SEASON_ERROR,
                        "Bilibili season section response is missing episodes");
            }
            existing = new JSONArray();
        }

        JSONArray videos = archive.getJSONArray("videos");
        String archiveTitle = archive.getJSONObject("archive").getString("title");
        JSONArray missing = new JSONArray();
        for (int i = 0; i < videos.size(); i++) {
            JSONObject video = videos.getJSONObject(i);
            long cid = video.getLongValue("cid");
            if (cid <= 0) {
                throw new StreamerRecordException(ErrorEnum.BILI_SEASON_ERROR,
                        "Posted Bilibili archive is missing a video cid, aid=" + aid);
            }
            if (containsEpisode(existing, aid, cid)) {
                continue;
            }
            JSONObject episode = new JSONObject();
            episode.put("aid", aid);
            episode.put("cid", cid);
            episode.put("title", videos.size() == 1 ? archiveTitle : archiveTitle + " " + video.getString("title"));
            episode.put("charging_pay", 0);
            missing.add(episode);
        }
        if (missing.isEmpty()) {
            return;
        }
        JSONObject payload = new JSONObject();
        payload.put("sectionId", sectionId);
        payload.put("episodes", missing);
        request(MEMBER_URL + "/x2/creative/web/season/section/episodes/add?csrf=" + csrf,
                cookies, RequestBody.create(JSON_MEDIA_TYPE, JSON.toJSONString(payload)));
    }

    /**
     * 判断指定分 P 是否已经位于目标小节中。
     */
    private boolean containsEpisode(JSONArray episodes, long aid, long cid) {
        for (int i = 0; i < episodes.size(); i++) {
            JSONObject episode = episodes.getJSONObject(i);
            if (episode.getLongValue("aid") == aid && episode.getLongValue("cid") == cid) {
                return true;
            }
        }
        return false;
    }

    /**
     * 发送创作中心请求并检查 B站业务状态码；不在异常中暴露 Cookie 或请求正文。
     */
    private JSONObject request(String url, String cookies, RequestBody body) {
        Request.Builder builder = new Request.Builder().url(url)
                .addHeader("Cookie", cookies)
                .addHeader("Origin", MEMBER_URL)
                .addHeader("Referer", MEMBER_URL + "/platform/upload/video/frame");
        Request httpRequest = body == null ? builder.get().build() : builder.post(body).build();
        JSONObject response;
        try {
            response = JSON.parseObject(OkHttpClientUtil.execute(httpRequest));
        } catch (RuntimeException e) {
            throw new StreamerRecordException(ErrorEnum.BILI_SEASON_ERROR,
                    "Bilibili season request failed, path=" + httpRequest.url().encodedPath(), e);
        }
        if (response == null || response.getInteger("code") == null || response.getIntValue("code") != 0) {
            throw new StreamerRecordException(ErrorEnum.BILI_SEASON_ERROR,
                    "Bilibili season API returned an error, path=" + httpRequest.url().encodedPath()
                            + ", code=" + (response == null ? "null" : response.getString("code")));
        }
        return response;
    }
}
