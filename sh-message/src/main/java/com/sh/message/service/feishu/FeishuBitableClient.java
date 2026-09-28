package com.sh.message.service.feishu;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sh.config.utils.OkHttpClientUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Uses a Feishu custom application's tenant token to access one Bitable base. */
@Component
@Slf4j
public class FeishuBitableClient {
    private static final String API_BASE_URL = "https://open.feishu.cn/open-apis";
    private static final String STREAMER_TABLE_NAME = "主播表";
    private static final String LIVE_RECORD_TABLE_NAME = "直播记录表";
    private static final String STREAMER_NAME_FIELD = "主播名称";
    private static final String STREAMER_URL_FIELD = "直播地址";
    private static final String RECORD_NAME_FIELD = "主播名称";
    private static final String RECORD_START_FIELD = "开始时间";
    private static final String RECORD_END_FIELD = "结束时间";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final int PAGE_SIZE = 100;
    private static final long TOKEN_REFRESH_MARGIN_MS = 5 * 60 * 1000L;

    private final String appId;
    private final String appSecret;
    private final Map<String, TableIds> tableIdsByBase = new ConcurrentHashMap<>();
    private final Map<String, String> baseTokensByWikiNode = new ConcurrentHashMap<>();
    private volatile String tenantAccessToken;
    private volatile long tokenExpiresAt;

    public FeishuBitableClient(ConfigurableEnvironment environment) {
        this.appId = readConfiguredValue(environment, "feishu.app-id");
        this.appSecret = readConfiguredValue(environment, "feishu.app-secret");
        if (!isConfigured()) {
            log.warn("Feishu Bitable monitor credentials are not configured; checks will be skipped");
        }
    }

    public boolean isConfigured() {
        return StringUtils.isNoneBlank(appId, appSecret);
    }

    public String resolveWikiBaseToken(String wikiNodeToken) {
        if (!isConfigured() || StringUtils.isBlank(wikiNodeToken)) {
            throw new IllegalStateException("Feishu Bitable monitor configuration is incomplete");
        }
        return baseTokensByWikiNode.computeIfAbsent(wikiNodeToken, token -> {
            HttpUrl url = HttpUrl.parse(API_BASE_URL + "/wiki/v2/spaces/get_node")
                    .newBuilder().addQueryParameter("token", token).build();
            JSONObject node = requestData(new Request.Builder().url(url).get()).getJSONObject("node");
            if (node == null || !"bitable".equals(node.getString("obj_type"))
                    || StringUtils.isBlank(node.getString("obj_token"))) {
                throw new IllegalStateException("Feishu wiki link does not point to a Bitable base");
            }
            return node.getString("obj_token");
        });
    }

    public List<StreamerRoom> listStreamerRooms(String baseToken) {
        requireConfigured(baseToken);
        TableIds tableIds = tableIdsByBase.computeIfAbsent(baseToken, this::resolveTableIds);
        List<StreamerRoom> rooms = new ArrayList<>();
        String pageToken = null;
        do {
            HttpUrl.Builder url = HttpUrl.parse(recordsUrl(baseToken, tableIds.streamerTableId)).newBuilder()
                    .addQueryParameter("page_size", String.valueOf(PAGE_SIZE));
            if (StringUtils.isNotBlank(pageToken)) {
                url.addQueryParameter("page_token", pageToken);
            }
            JSONObject data = requestData(new Request.Builder().url(url.build()).get());
            JSONArray items = data.getJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.size(); i++) {
                    JSONObject record = items.getJSONObject(i);
                    JSONObject fields = record.getJSONObject("fields");
                    if (fields == null) {
                        continue;
                    }
                    String name = readText(fields.get(STREAMER_NAME_FIELD));
                    String roomUrl = readText(fields.get(STREAMER_URL_FIELD));
                    if (StringUtils.isBlank(name) || StringUtils.isBlank(roomUrl)) {
                        log.warn("skip Feishu streamer row with missing name or URL, recordId: {}", record.getString("record_id"));
                        continue;
                    }
                    rooms.add(new StreamerRoom(name.trim(), roomUrl.trim()));
                }
            }
            pageToken = Boolean.TRUE.equals(data.getBoolean("has_more")) ? data.getString("page_token") : null;
            if (Boolean.TRUE.equals(data.getBoolean("has_more")) && StringUtils.isBlank(pageToken)) {
                throw new IllegalStateException("Feishu Bitable returned has_more without page_token");
            }
        } while (StringUtils.isNotBlank(pageToken));
        return rooms;
    }

    public void createLiveRecord(String baseToken, String name, long startTimeMillis, long endTimeMillis) {
        requireConfigured(baseToken);
        TableIds tableIds = tableIdsByBase.computeIfAbsent(baseToken, this::resolveTableIds);
        JSONObject fields = new JSONObject();
        fields.put(RECORD_NAME_FIELD, name);
        fields.put(RECORD_START_FIELD, startTimeMillis);
        fields.put(RECORD_END_FIELD, endTimeMillis);
        JSONObject body = new JSONObject();
        body.put("fields", fields);
        requestData(new Request.Builder().url(recordsUrl(baseToken, tableIds.liveRecordTableId))
                .post(RequestBody.create(JSON_MEDIA_TYPE, body.toJSONString())));
    }

    private JSONObject requestData(Request.Builder builder) {
        Request request = builder.header("Authorization", "Bearer " + getTenantAccessToken())
                .header("Content-Type", "application/json; charset=utf-8")
                .build();
        JSONObject response = JSON.parseObject(OkHttpClientUtil.execute(request));
        checkResponse(response, "Bitable request");
        JSONObject data = response.getJSONObject("data");
        if (data == null) {
            throw new IllegalStateException("Feishu Bitable response has no data");
        }
        return data;
    }

    private String getTenantAccessToken() {
        if (StringUtils.isNotBlank(tenantAccessToken) && System.currentTimeMillis() < tokenExpiresAt) {
            return tenantAccessToken;
        }
        synchronized (this) {
            if (StringUtils.isNotBlank(tenantAccessToken) && System.currentTimeMillis() < tokenExpiresAt) {
                return tenantAccessToken;
            }
            JSONObject body = new JSONObject();
            body.put("app_id", appId);
            body.put("app_secret", appSecret);
            Request request = new Request.Builder().url(API_BASE_URL + "/auth/v3/tenant_access_token/internal")
                    .post(RequestBody.create(JSON_MEDIA_TYPE, body.toJSONString())).build();
            JSONObject response = JSON.parseObject(OkHttpClientUtil.execute(request));
            checkResponse(response, "tenant token request");
            String token = response.getString("tenant_access_token");
            long expireSeconds = response.getLongValue("expire");
            if (StringUtils.isBlank(token) || expireSeconds <= 0) {
                throw new IllegalStateException("Feishu tenant token response is incomplete");
            }
            tenantAccessToken = token;
            tokenExpiresAt = System.currentTimeMillis() + Math.max(1_000L, expireSeconds * 1_000L - TOKEN_REFRESH_MARGIN_MS);
            return token;
        }
    }

    private void requireConfigured(String baseToken) {
        if (!isConfigured() || StringUtils.isBlank(baseToken)) {
            throw new IllegalStateException("Feishu Bitable monitor configuration is incomplete");
        }
    }

    private static String readConfiguredValue(ConfigurableEnvironment environment, String key) {
        for (PropertySource<?> source : environment.getPropertySources()) {
            // This synthetic source resolves placeholders; inspect the original values instead.
            if ("configurationProperties".equals(source.getName())) {
                continue;
            }
            Object raw = source.getProperty(key);
            if (raw == null) {
                continue;
            }
            String value = StringUtils.trimToNull(raw.toString());
            if (value != null && !value.startsWith("${")) {
                return value;
            }
        }
        return null;
    }

    private TableIds resolveTableIds(String baseToken) {
        String streamerTableId = null;
        String liveRecordTableId = null;
        String pageToken = null;
        do {
            HttpUrl.Builder url = HttpUrl.parse(API_BASE_URL + "/bitable/v1/apps/" + baseToken + "/tables")
                    .newBuilder().addQueryParameter("page_size", String.valueOf(PAGE_SIZE));
            if (StringUtils.isNotBlank(pageToken)) {
                url.addQueryParameter("page_token", pageToken);
            }
            JSONObject data = requestData(new Request.Builder().url(url.build()).get());
            JSONArray items = data.getJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.size(); i++) {
                    JSONObject table = items.getJSONObject(i);
                    if (STREAMER_TABLE_NAME.equals(table.getString("name"))) {
                        streamerTableId = table.getString("table_id");
                    } else if (LIVE_RECORD_TABLE_NAME.equals(table.getString("name"))) {
                        liveRecordTableId = table.getString("table_id");
                    }
                }
            }
            pageToken = Boolean.TRUE.equals(data.getBoolean("has_more")) ? data.getString("page_token") : null;
            if (Boolean.TRUE.equals(data.getBoolean("has_more")) && StringUtils.isBlank(pageToken)) {
                throw new IllegalStateException("Feishu Bitable table list returned has_more without page_token");
            }
        } while (StringUtils.isNotBlank(pageToken));
        if (StringUtils.isAnyBlank(streamerTableId, liveRecordTableId)) {
            throw new IllegalStateException("Feishu Bitable must contain tables named 主播表 and 直播记录表");
        }
        return new TableIds(streamerTableId, liveRecordTableId);
    }

    private String recordsUrl(String baseToken, String tableId) {
        return API_BASE_URL + "/bitable/v1/apps/" + baseToken + "/tables/" + tableId + "/records";
    }

    private static void checkResponse(JSONObject response, String operation) {
        if (response == null || response.getInteger("code") == null || response.getInteger("code") != 0) {
            String code = response == null ? "empty" : response.getString("code");
            String message = response == null ? "empty response" : response.getString("msg");
            throw new IllegalStateException("Feishu " + operation + " failed, code=" + code + ", msg=" + message);
        }
    }

    private static String readText(Object value) {
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            return StringUtils.defaultIfBlank(object.getString("link"),
                    StringUtils.defaultIfBlank(object.getString("url"), object.getString("text")));
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder result = new StringBuilder();
            for (Object item : array) {
                result.append(StringUtils.defaultString(readText(item)));
            }
            return result.toString();
        }
        return null;
    }

    @Getter
    public static class StreamerRoom {
        private final String name;
        private final String roomUrl;

        public StreamerRoom(String name, String roomUrl) {
            this.name = name;
            this.roomUrl = roomUrl;
        }
    }

    private static class TableIds {
        private final String streamerTableId;
        private final String liveRecordTableId;

        private TableIds(String streamerTableId, String liveRecordTableId) {
            this.streamerTableId = streamerTableId;
            this.liveRecordTableId = liveRecordTableId;
        }
    }
}
