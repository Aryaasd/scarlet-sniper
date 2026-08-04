package com.scarletsniper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@SpringBootApplication
@EnableScheduling
public class SniperApplication {

    public static void main(String[] args) {
        SpringApplication.run(SniperApplication.class, args);
    }

    // Injected into SchedulerService so its HTTP path can be tested with a
    // mock. Timeouts matter here: the scheduler fires every 10s, so a hung
    // request without them would tie up the scheduling thread indefinitely.
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(15))
                .build();
    }
}
