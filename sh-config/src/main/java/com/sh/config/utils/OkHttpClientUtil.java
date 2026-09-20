package com.sh.config.utils;

import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.lang3.tuple.Pair;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Slf4j
public class OkHttpClientUtil {
    private static final OkHttpClient CLIENT = new OkHttpClient().newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(40, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build();

    public static String execute(Request request) {
        try (Response response = CLIENT.newCall(request).execute()) {
            if (response.isSuccessful()) {
                return response.body().string();
            }
            String body = response.body() != null ? response.body().string() : null;
            log.error("http execute failed, status: {}, message: {}, body: {}",
                    response.code(), response.message(), body);
            throw new IllegalStateException(
                    "HTTP request failed with status " + response.code());
        } catch (IOException e) {
            throw new IllegalStateException("HTTP request execution failed", e);
        }
    }

    public static Pair<String, String> executeWithCookies(Request request) {
        try (Response response = CLIENT.newCall(request).execute()) {
            if (response.isSuccessful()) {
                String body = response.body().string();
                String newCookie = request.header("Set-Cookie");
                return Pair.of(body, newCookie);
            }
            String body = response.body() != null ? response.body().string() : null;
            log.error("http execute failed, status: {}, message: {}, body: {}",
                    response.code(), response.message(), body);
            throw new IllegalStateException(
                    "HTTP request failed with status " + response.code());
        } catch (IOException e) {
            throw new IllegalStateException("HTTP request execution failed", e);
        }
    }
}
