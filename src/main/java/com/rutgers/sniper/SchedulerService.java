package com.rutgers.sniper;

import com.rutgers.sniper.model.TrackedSection;
import com.rutgers.sniper.repository.SectionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPInputStream;

@Service
public class SchedulerService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private SectionRepository repository;

    @Autowired
    private SmsService smsService; 

    @Scheduled(fixedRate = 10000)
    public void checkRutgersCourses() {
        String url = "https://sis.rutgers.edu/soc/api/courses.json?year=2025&term=9&campus=NB&subject=198";

        try {
            List<TrackedSection> mySections = repository.findAll();
            if (mySections.isEmpty()) return;

            HttpHeaders headers = new HttpHeaders();
            headers.add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/54.0.2840.99 Safari/537.36");
            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.GET, entity, byte[].class);

            if (response.getBody() == null) return;

            byte[] payload = response.getBody();
            String jsonString;
            if (payload.length > 2 && payload[0] == (byte) 0x1f && payload[1] == (byte) 0x8b) {
                try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(payload))) {
                    jsonString = new String(gis.readAllBytes(), StandardCharsets.UTF_8);
                }
            } else {
                jsonString = new String(payload, StandardCharsets.UTF_8);
            }

            JsonNode allCourses = mapper.readTree(jsonString);

            System.out.println("Checking " + allCourses.size() + " courses against my " + mySections.size() + " targets...");

            for (JsonNode course : allCourses) {
                JsonNode sections = course.get("sections");
                if (sections != null) {
                    for (JsonNode section : sections) {
                        String sectionIndex = section.get("index").asText();
                        boolean isCurrentlyOpen = section.get("openStatus").asBoolean();

                        for (TrackedSection tracked : mySections) {
                            if (tracked.getSectionIndex().equals(sectionIndex)) {
                                
                                if (isCurrentlyOpen && !tracked.isOpen()) {
                                    
                                    System.out.println("🚨 SNIPER ALERT: " + sectionIndex + " IS OPEN!");
                                    
                                    smsService.sendSms(tracked.getUserContact(), "RUTGERS SNIPER: Class " + sectionIndex + " is OPEN! Go register!");

                                    tracked.setOpen(true);
                                    repository.save(tracked);

                                } else if (!isCurrentlyOpen && tracked.isOpen()) {
                                    System.out.println("... Section " + sectionIndex + " has closed again.");
                                    tracked.setOpen(false);
                                    repository.save(tracked);
                                } else {
                                    System.out.println("... Section " + sectionIndex + " status: " + (isCurrentlyOpen ? "OPEN (Already notified)" : "CLOSED"));
                                }
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        }
    }
}