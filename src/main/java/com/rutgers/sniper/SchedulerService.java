package com.rutgers.sniper;

import com.rutgers.sniper.model.TrackedSection;
import com.rutgers.sniper.repository.SectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

@Service
public class SchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/54.0.2840.99 Safari/537.36";

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    private final SectionRepository repository;
    private final SmsService smsService;

    public SchedulerService(SectionRepository repository, SmsService smsService) {
        this.repository = repository;
        this.smsService = smsService;
    }

    // Every tracked section carries its own subject/term/year/campus, so
    // this groups them by that combination and issues one Schedule-of-
    // Classes request per distinct combination rather than one hardcoded
    // request for everything.
    @Scheduled(fixedRate = 10000)
    public void checkRutgersCourses() {
        List<TrackedSection> mySections = repository.findAll();
        if (mySections.isEmpty()) return;

        Map<CourseQuery, List<TrackedSection>> byQuery = mySections.stream()
                .collect(Collectors.groupingBy(CourseQuery::from));

        for (Map.Entry<CourseQuery, List<TrackedSection>> entry : byQuery.entrySet()) {
            checkOneQuery(entry.getKey(), entry.getValue());
        }
    }

    private void checkOneQuery(CourseQuery query, List<TrackedSection> tracked) {
        try {
            JsonNode allCourses = fetchCourses(query);
            Map<String, Boolean> openBySectionIndex = indexOpenStatus(allCourses);

            log.info("Checked {} courses for {} against {} tracked section(s)",
                    allCourses.size(), query, tracked.size());

            for (TrackedSection section : tracked) {
                Boolean isCurrentlyOpen = openBySectionIndex.get(section.getSectionIndex());
                if (isCurrentlyOpen == null) {
                    log.warn("Section {} not found in {} response", section.getSectionIndex(), query);
                    continue;
                }
                applyStatus(section, isCurrentlyOpen);
            }
        } catch (Exception e) {
            log.error("Failed to check courses for {}: {}", query, e.getMessage(), e);
        }
    }

    private Map<String, Boolean> indexOpenStatus(JsonNode allCourses) {
        Map<String, Boolean> result = new HashMap<>();
        for (JsonNode course : allCourses) {
            JsonNode sections = course.get("sections");
            if (sections == null) continue;
            for (JsonNode section : sections) {
                result.put(section.get("index").asText(), section.get("openStatus").asBoolean());
            }
        }
        return result;
    }

    // Package-private so it's directly unit-testable without mocking HTTP/JSON.
    void applyStatus(TrackedSection tracked, boolean isCurrentlyOpen) {
        if (isCurrentlyOpen && !tracked.isOpen()) {
            if (!tracked.isConfirmed()) {
                // Don't mark it open or save anything — leave isOpen false so
                // this same branch fires again on the next poll. The moment
                // the owner confirms, the very next cycle catches it and
                // alerts normally. We just never text an unverified number.
                log.info("Section {} opened but contact is unverified — not alerting.", tracked.getSectionIndex());
                return;
            }
            log.info("SNIPER ALERT: {} is OPEN!", tracked.getSectionIndex());
            smsService.sendSms(tracked.getUserContact(),
                    "ScarletSniper: Section " + tracked.getSectionIndex() + " is OPEN! Go register!");
            tracked.setOpen(true);
            repository.save(tracked);
        } else if (!isCurrentlyOpen && tracked.isOpen()) {
            log.info("Section {} has closed again.", tracked.getSectionIndex());
            tracked.setOpen(false);
            repository.save(tracked);
        }
    }

    private JsonNode fetchCourses(CourseQuery query) throws IOException {
        String url = "https://sis.rutgers.edu/soc/api/courses.json?year=%d&term=%s&campus=%s&subject=%s"
                .formatted(query.year(), query.term(), query.campus(), query.subject());

        HttpHeaders headers = new HttpHeaders();
        headers.add("User-Agent", USER_AGENT);
        HttpEntity<String> entity = new HttpEntity<>(headers);
        ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.GET, entity, byte[].class);

        byte[] payload = response.getBody();
        if (payload == null) return mapper.createArrayNode();

        String jsonString;
        if (payload.length > 2 && payload[0] == (byte) 0x1f && payload[1] == (byte) 0x8b) {
            try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(payload))) {
                jsonString = new String(gis.readAllBytes(), StandardCharsets.UTF_8);
            }
        } else {
            jsonString = new String(payload, StandardCharsets.UTF_8);
        }
        return mapper.readTree(jsonString);
    }

    private record CourseQuery(int year, String term, String campus, String subject) {
        static CourseQuery from(TrackedSection s) {
            return new CourseQuery(s.getYear(), s.getTerm(), s.getCampus(), s.getSubject());
        }
    }
}
