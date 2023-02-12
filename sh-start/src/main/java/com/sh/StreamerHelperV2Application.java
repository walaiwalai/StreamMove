package com.sh;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * @author caiWen
 * @date 2023/1/23 9:51
 */
@SpringBootApplication(scanBasePackages = {"com.sh"})
@Slf4j
public class StreamerHelperV2Application {
    public static void main(String[] args) {
        SpringApplication.run(StreamerHelperV2Application.class, args);
    }
}
