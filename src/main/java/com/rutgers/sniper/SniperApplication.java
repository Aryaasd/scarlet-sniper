package com.rutgers.sniper;

import com.rutgers.sniper.model.TrackedSection;
import com.rutgers.sniper.repository.SectionRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SniperApplication {

    public static void main(String[] args) {
        SpringApplication.run(SniperApplication.class, args);
    }

    @Bean
    public CommandLineRunner demo(SectionRepository repository) {
        return (args) -> {
            if (repository.count() == 0) {
                System.out.println("--- SEEDING DATABASE ---");
                repository.save(new TrackedSection("08278", "+15555550123"));
                System.out.println("Added '08278' to the watchlist.");
            }
        };
    }
}