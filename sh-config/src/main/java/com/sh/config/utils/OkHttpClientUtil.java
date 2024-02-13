package com.sh.config.utils;

import lombok.extern.slf4j.Slf4j;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.concurrent.TimeUnit;

@Slf4j
public class OkHttpClientUtil {
    private static final OkHttpClient CLIENT = new OkHttpClient().newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(40, TimeUnit.SECONDS)
            .build();
    public static String execute(Request request) {
        try (Response response = CLIENT.newCall(request).execute()) {
            if (response.isSuccessful()) {
                return response.body().string();
            } else {
                String message = response.message();
                String bodyStr = response.body() != null ? response.body().string() : null;
                log.error("http execute fail, message: {}, body: {}", message, bodyStr);
                throw new RuntimeException(message);
            }
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }
    }
}
