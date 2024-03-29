package com.sh.config.utils;

/**
 * @author caiWen
 * @date 2023/1/26 18:40
 */

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.FormBodyPartBuilder;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;


@Slf4j
public class HttpClientUtil {
    private static final int MAX_TOTAL_CONN = 600;
    private static final int MAX_CONN_PER_HOST = 300;
    private static final int SOCKET_TIMEOUT = 18000000;
    private static final int CONNECTION_TIMEOUT = 200;
    private static final int CONNECTION_MANAGER_TIMEOUT = 100;


    private static CloseableHttpClient httpclient;
    private static PoolingHttpClientConnectionManager connMrg;
    // 默认字符集
    private static String encoding = "utf-8";

    static {
        init();
        destroyByJvmExit();
    }

    private static void destroyByJvmExit() {
        Thread hook = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    httpclient.close();
                } catch (IOException e) {
                }
            }
        });
        Runtime.getRuntime().addShutdownHook(hook);
    }

    private static void init() {
        connMrg = new PoolingHttpClientConnectionManager();
        // 最大连接数
        connMrg.setMaxTotal(MAX_TOTAL_CONN);
        //每个路由基础的连接
        connMrg.setDefaultMaxPerRoute(MAX_CONN_PER_HOST);

        RequestConfig defaultRequestConfig = RequestConfig.custom()
                //设置连接超时时间，单位毫秒。
                .setConnectTimeout(CONNECTION_TIMEOUT)
                //请求获取数据的超时时间，单位毫秒
                .setSocketTimeout(SOCKET_TIMEOUT)
                //设置从连接池获取连接超时时间，单位毫秒
                .setConnectionRequestTimeout(CONNECTION_MANAGER_TIMEOUT)
                .build();
        httpclient = HttpClients.custom()
                .setConnectionManager(connMrg)
                .setDefaultRequestConfig(defaultRequestConfig)
                .build();
    }

    public static CloseableHttpClient getClient() {
        return httpclient;
    }

    /**
     * @param url      请求地址
     * @param headers  请求头
     * @param encoding 字符集
     * @return String
     * @throws
     */
    private static String sendPost(String url, Map<String, String> headers, String entityStr, String encoding) {
        log.info("send http post, encoding: {}, headers: {}, url: {}, data: {}", encoding, JSON.toJSONString(headers),
                url, entityStr);
        HttpPost httpPost = new HttpPost();
        try {
            // 设置请求地址
            httpPost.setURI(new URI(url));
            // 设置请求头
            if (headers != null) {
                Header[] allHeader = new BasicHeader[headers.size()];
                int i = 0;
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    allHeader[i] = new BasicHeader(entry.getKey(), entry.getValue());
                    i++;
                }
                httpPost.setHeaders(allHeader);
            }
            // 设置实体
            httpPost.setEntity(new StringEntity(entityStr, encoding));
            // 发送请求,返回响应对象
            CloseableHttpResponse response = httpclient.execute(httpPost);
            return parseData(response);

        } catch (Exception e) {
            log.error("发送post请求失败", e);
        } finally {
            httpPost.releaseConnection();
        }
        return null;
    }

    public static String encodeParams(Map<String, String> params) {
        StringBuilder encodedParams = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (encodedParams.length() > 0) {
                encodedParams.append("&");
            }
            encodedParams.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return encodedParams.toString();
    }

    /**
     * @param url      请求地址
     * @param headers  请求头
     * @param httpEntity     请求实体
     * @return
     */
    public static String sendPost(String url, Map<String, String> headers, HttpEntity httpEntity) {
//        log.info("send http post, encoding: {}, headers: {}, url: {}", encoding, JSON.toJSONString(headers),url);
        HttpPost httpPost = new HttpPost();
        try {
            // 设置请求地址
            httpPost.setURI(new URI(url));
            // 设置请求头
            if (headers != null) {
                Header[] allHeader = new BasicHeader[headers.size()];
                int i = 0;
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    allHeader[i] = new BasicHeader(entry.getKey(), entry.getValue());
                    i++;
                }
                httpPost.setHeaders(allHeader);
            }
            // 设置实体
            httpPost.setEntity(httpEntity);
            // 发送请求,返回响应对象
            CloseableHttpResponse response = httpclient.execute(httpPost);
            return parseData(response);
        } catch (Exception e) {
            log.error("do post request fail", e);
        } finally {
            httpPost.releaseConnection();
        }
        return null;
    }

    /**
     * @param url     请求地址
     * @param headers 请求头
     * @param data    请求实体
     * @return
     */
    public static String sendPost(String url, Map<String, String> headers, JSONObject data) {
        return sendPost(url, headers, JSON.toJSONString(data), encoding);
    }

    /**
     * @param url     请求地址
     * @param headers 请求头
     * @param params  请求实体
     * @return
     */
    public static String sendPost(String url, Map<String, String> headers, Map<String, String> params) {
        return sendPost(url, headers, JSON.toJSONString(params), encoding);
    }

    /**
     * @param url     请求地址
     * @param headers 请求头
     * @param entityStr  请求实体
     * @return
     */
    public static String sendPost(String url, Map<String, String> headers, String entityStr) {
        return sendPost(url, headers, entityStr, encoding);
    }

    public static String sendGet(String url, Map<String, String> headers, Map<String, String> params) {
        log.info("HttpClientUtil>sendGet：URL={}, params={}", url, JSON.toJSON(params));
        HttpGet httpGet = new HttpGet();
        try {
            URIBuilder builder = new URIBuilder(url);
            if (headers != null) {
                for (String key : headers.keySet()) {
                    httpGet.addHeader(key, headers.get(key));
                }
            }

            if (params != null) {
                for (String key : params.keySet()) {
                    builder.addParameter(key, params.get(key));
                }
            }
            URI uri = builder.build();
            httpGet.setURI(uri);
            CloseableHttpResponse response = httpclient.execute(httpGet);
            return parseData(response);
        } catch (Exception e) {
            log.error("HttpClientUtil>sendGet error", e);
        } finally {
            httpGet.releaseConnection();
        }
        return null;
    }

    /**
     * @param url    请求地址
     * @param params 请求参数
     * @return
     */
    public static String sendGet(String url, Map<String, String> params) {
        return sendGet(url, null, params);
    }

    /**
     * @param url 请求地址
     * @return
     */
    public static String sendGet(String url) {
        return sendGet(url, null);
    }

    /**
     * 解析response
     *
     * @param response
     * @return
     * @throws Exception
     */
    public static String parseData(CloseableHttpResponse response) throws Exception {
        // 获取响应状态
        int status = response.getStatusLine().getStatusCode();
        if (status == HttpStatus.SC_OK) {
            // 获取响应数据
            String res = EntityUtils.toString(response.getEntity(), encoding);
            log.info("HttpClientUtil receive response: {}", res);
            return res;
        } else {
            log.error("response fail, code: {}", status);
        }
        return null;
    }
}

