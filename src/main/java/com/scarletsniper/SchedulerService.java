package com.scarletsniper;

import com.scarletsniper.model.TrackedSection;
import com.scarletsniper.repository.SectionRepository;
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
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

@Service
public class SchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

    // Identifies the app honestly. A spoofed browser UA was verified to make
    // no difference to this endpoint (byte-identical responses), so there's
    // nothing to gain by pretending to be Chrome.
    private static final String USER_AGENT = "ScarletSniper/1.0 (+https://github.com/Aryaasd/scarlet-sniper)";

    // classes.rutgers.edu is where sis.rutgers.edu 302-redirects to; going
    // straight there saves a redirect hop on every poll.
    private static final String COURSES_URL =
            "https://classes.rutgers.edu/soc/api/courses.json?year=%d&term=%s&campus=%s&subject=%s";

    // How long an unconfirmed watch may sit before it's reaped.
    static final Duration UNCONFIRMED_TTL = Duration.ofHours(24);

    private final ObjectMapper mapper = new ObjectMapper();

    private final SectionRepository repository;
    private final SmsService smsService;
    private final RestTemplate restTemplate;

    public SchedulerService(SectionRepository repository, SmsService smsService, RestTemplate restTemplate) {
        this.repository = repository;
        this.smsService = smsService;
        this.restTemplate = restTemplate;
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

    // Registrations that never completed verification would otherwise poll
    // Rutgers forever. Package-private for direct testing.
    @Scheduled(fixedRate = 3600000)
    void reapAbandonedUnconfirmedSections() {
        Instant cutoff = Instant.now().minus(UNCONFIRMED_TTL);
        List<TrackedSection> stale = repository.findByConfirmedFalseAndCreatedAtBefore(cutoff);
        if (stale.isEmpty()) return;
        repository.deleteAll(stale);
        log.info("Reaped {} unconfirmed watch(es) older than {}", stale.size(), UNCONFIRMED_TTL);
    }

    // Package-private so a failure in one query can be shown not to affect
    // the others.
    void checkOneQuery(CourseQuery query, List<TrackedSection> tracked) {
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

    // Package-private for testing. Tolerates malformed entries rather than
    // letting one bad section abort the whole response.
    Map<String, Boolean> indexOpenStatus(JsonNode allCourses) {
        Map<String, Boolean> result = new HashMap<>();
        if (allCourses == null || !allCourses.isArray()) return result;
        for (JsonNode course : allCourses) {
            JsonNode sections = course.get("sections");
            if (sections == null || !sections.isArray()) continue;
            for (JsonNode section : sections) {
                JsonNode index = section.get("index");
                JsonNode openStatus = section.get("openStatus");
                if (index == null || openStatus == null) continue;
                result.put(index.asText(), openStatus.asBoolean());
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
            boolean sent = smsService.sendSms(tracked.getUserContact(),
                    "ScarletSniper: Section " + tracked.getSectionIndex() + " is OPEN! Go register!");
            if (!sent) {
                // Leave isOpen false and persist nothing, so the next poll
                // retries instead of silently recording an alert that never
                // reached anyone.
                log.warn("Alert for section {} failed to send — will retry next poll.", tracked.getSectionIndex());
                return;
            }
            tracked.setOpen(true);
            repository.save(tracked);
        } else if (!isCurrentlyOpen && tracked.isOpen()) {
            log.info("Section {} has closed again.", tracked.getSectionIndex());
            tracked.setOpen(false);
            repository.save(tracked);
        }
    }

    // Package-private for testing. Handles both gzipped and plain responses —
    // the live endpoint returns gzip.
    JsonNode fetchCourses(CourseQuery query) throws IOException {
        String url = COURSES_URL.formatted(query.year(), query.term(), query.campus(), query.subject());

        HttpHeaders headers = new HttpHeaders();
        headers.add("User-Agent", USER_AGENT);
        HttpEntity<String> entity = new HttpEntity<>(headers);
        ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.GET, entity, byte[].class);

        byte[] payload = response.getBody();
        if (payload == null || payload.length == 0) return mapper.createArrayNode();

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

    record CourseQuery(int year, String term, String campus, String subject) {
        static CourseQuery from(TrackedSection s) {
            return new CourseQuery(s.getYear(), s.getTerm(), s.getCampus(), s.getSubject());
        }
    }
}
