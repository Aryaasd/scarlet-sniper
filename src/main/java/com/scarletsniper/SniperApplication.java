package com.scarletsniper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SniperApplication {

    public static void main(String[] args) {
        SpringApplication.run(SniperApplication.class, args);
    }
}
