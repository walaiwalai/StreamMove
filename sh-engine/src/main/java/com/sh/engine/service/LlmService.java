package com.sh.engine.service;

import java.util.concurrent.CompletableFuture;

public interface LlmService {

    <T> T chat(String prompt, Class<T> resultType);

    <T> CompletableFuture<T> chatAsync(String prompt, Class<T> resultType);
}
