package com.scarletsniper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scarletsniper.model.TrackedSection;
import com.scarletsniper.repository.SectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SchedulerServiceTest {

    private SectionRepository repository;
    private SmsService smsService;
    private RestTemplate restTemplate;
    private SchedulerService scheduler;

    @BeforeEach
    void setUp() {
        repository = mock(SectionRepository.class);
        smsService = mock(SmsService.class);
        restTemplate = mock(RestTemplate.class);
        // Default: Twilio accepts the message. Individual tests override.
        when(smsService.sendSms(any(), any())).thenReturn(true);
        scheduler = new SchedulerService(repository, smsService, restTemplate);
    }

    private static TrackedSection section(boolean currentlyMarkedOpen) {
        TrackedSection section = new TrackedSection();
        section.setSectionIndex("03608");
        section.setUserContact("+12015550123");
        section.setOpen(currentlyMarkedOpen);
        section.setConfirmed(true);
        section.setSubject("198");
        section.setTerm("9");
        section.setYear(2025);
        section.setCampus("NB");
        return section;
    }

    // ---- applyStatus: state transitions ----

    @Test
    void sendsAlertAndPersistsWhenSectionTransitionsToOpen() {
        TrackedSection section = section(false);

        scheduler.applyStatus(section, true);

        assertThat(section.isOpen()).isTrue();
        verify(smsService).sendSms(eq("+12015550123"), contains("03608"));
        verify(repository).save(section);
    }

    @Test
    void doesNotAlertAgainWhenAlreadyMarkedOpen() {
        TrackedSection section = section(true);

        scheduler.applyStatus(section, true);

        assertThat(section.isOpen()).isTrue();
        verify(smsService, never()).sendSms(any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void marksClosedAgainWithoutAlertingWhenSectionCloses() {
        TrackedSection section = section(true);

        scheduler.applyStatus(section, false);

        assertThat(section.isOpen()).isFalse();
        verify(smsService, never()).sendSms(any(), any());
        verify(repository).save(section);
    }

    @Test
    void doesNothingWhenStillClosed() {
        TrackedSection section = section(false);

        scheduler.applyStatus(section, false);

        assertThat(section.isOpen()).isFalse();
        verify(smsService, never()).sendSms(any(), any());
        verify(repository, never()).save(any());
    }

    // ---- applyStatus: verification gate ----

    @Test
    void doesNotAlertOrPersistWhenSectionOpensButContactIsUnconfirmed() {
        TrackedSection section = section(false);
        section.setConfirmed(false);

        scheduler.applyStatus(section, true);

        assertThat(section.isOpen()).isFalse();
        verify(smsService, never()).sendSms(any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void alertsOnTheFirstPollAfterConfirmationCatchesUpAnAlreadyOpenSection() {
        TrackedSection section = section(false);
        section.setConfirmed(false);
        scheduler.applyStatus(section, true); // opened while unconfirmed — no-op

        section.setConfirmed(true); // owner verifies
        scheduler.applyStatus(section, true); // next poll cycle

        assertThat(section.isOpen()).isTrue();
        verify(smsService).sendSms(eq("+12015550123"), contains("03608"));
        verify(repository).save(section);
    }

    // ---- applyStatus: SMS delivery failure ----

    @Test
    void doesNotMarkOpenWhenSmsFailsSoItRetriesNextPoll() {
        when(smsService.sendSms(any(), any())).thenReturn(false);
        TrackedSection section = section(false);

        scheduler.applyStatus(section, true);

        assertThat(section.isOpen()).isFalse();
        verify(repository, never()).save(any());
    }

    @Test
    void retriesAlertOnSubsequentPollAfterAFailedSend() {
        when(smsService.sendSms(any(), any())).thenReturn(false);
        TrackedSection section = section(false);
        scheduler.applyStatus(section, true); // fails

        when(smsService.sendSms(any(), any())).thenReturn(true);
        scheduler.applyStatus(section, true); // succeeds on retry

        assertThat(section.isOpen()).isTrue();
        verify(smsService, times(2)).sendSms(eq("+12015550123"), contains("03608"));
        verify(repository).save(section);
    }

    // ---- indexOpenStatus: JSON parsing ----

    private static JsonNode json(String raw) throws IOException {
        return new ObjectMapper().readTree(raw);
    }

    @Test
    void indexesOpenStatusAcrossCoursesAndSections() throws IOException {
        JsonNode courses = json("""
            [
              {"sections":[{"index":"00001","openStatus":true},{"index":"00002","openStatus":false}]},
              {"sections":[{"index":"00003","openStatus":true}]}
            ]
            """);

        Map<String, Boolean> result = scheduler.indexOpenStatus(courses);

        assertThat(result).containsExactlyInAnyOrderEntriesOf(
                Map.of("00001", true, "00002", false, "00003", true));
    }

    @Test
    void indexOpenStatusToleratesMalformedEntries() throws IOException {
        JsonNode courses = json("""
            [
              {"noSections":true},
              {"sections":null},
              {"sections":"notAnArray"},
              {"sections":[{"index":"00001"},{"openStatus":true},{"index":"00002","openStatus":true}]}
            ]
            """);

        Map<String, Boolean> result = scheduler.indexOpenStatus(courses);

        // Only the fully-formed entry survives; nothing throws.
        assertThat(result).containsExactly(Map.entry("00002", true));
    }

    @Test
    void indexOpenStatusHandlesNullAndNonArrayInput() throws IOException {
        assertThat(scheduler.indexOpenStatus(null)).isEmpty();
        assertThat(scheduler.indexOpenStatus(json("{\"not\":\"an array\"}"))).isEmpty();
        assertThat(scheduler.indexOpenStatus(json("[]"))).isEmpty();
    }

    // ---- fetchCourses: HTTP + gzip handling ----

    private static byte[] gzip(String s) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(s.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private void stubResponse(byte[] body) {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok(body));
    }

    @Test
    void fetchCoursesDecodesGzippedResponses() throws IOException {
        stubResponse(gzip("[{\"sections\":[{\"index\":\"00001\",\"openStatus\":true}]}]"));

        JsonNode result = scheduler.fetchCourses(new SchedulerService.CourseQuery(2025, "9", "NB", "198"));

        assertThat(result.isArray()).isTrue();
        assertThat(scheduler.indexOpenStatus(result)).containsExactly(Map.entry("00001", true));
    }

    @Test
    void fetchCoursesHandlesPlainUncompressedResponses() throws IOException {
        stubResponse("[{\"sections\":[{\"index\":\"00009\",\"openStatus\":false}]}]"
                .getBytes(StandardCharsets.UTF_8));

        JsonNode result = scheduler.fetchCourses(new SchedulerService.CourseQuery(2025, "9", "NB", "198"));

        assertThat(scheduler.indexOpenStatus(result)).containsExactly(Map.entry("00009", false));
    }

    @Test
    void fetchCoursesReturnsEmptyArrayForNullOrEmptyBody() throws IOException {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok(null));
        assertThat(scheduler.fetchCourses(new SchedulerService.CourseQuery(2025, "9", "NB", "198")).size()).isZero();

        stubResponse(new byte[0]);
        assertThat(scheduler.fetchCourses(new SchedulerService.CourseQuery(2025, "9", "NB", "198")).size()).isZero();
    }

    @Test
    void fetchCoursesRequestsTheCorrectUrlWithAnHonestUserAgent() throws IOException {
        stubResponse("[]".getBytes(StandardCharsets.UTF_8));

        scheduler.fetchCourses(new SchedulerService.CourseQuery(2027, "1", "NK", "640"));

        verify(restTemplate).exchange(
                eq("https://classes.rutgers.edu/soc/api/courses.json?year=2027&term=1&campus=NK&subject=640"),
                eq(HttpMethod.GET),
                argThat(entity -> {
                    String ua = entity.getHeaders().getFirst("User-Agent");
                    return ua != null && ua.startsWith("ScarletSniper/") && !ua.contains("Mozilla");
                }),
                eq(byte[].class));
    }

    // ---- checkOneQuery: error isolation ----

    @Test
    void checkOneQuerySwallowsFetchFailuresSoOtherQueriesStillRun() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenThrow(new RestClientException("upstream down"));
        TrackedSection tracked = section(false);

        // Must not propagate — the scheduler loop depends on this.
        scheduler.checkOneQuery(new SchedulerService.CourseQuery(2025, "9", "NB", "198"), List.of(tracked));

        verify(smsService, never()).sendSms(any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void checkOneQuerySkipsSectionsMissingFromTheResponse() throws IOException {
        stubResponse(gzip("[{\"sections\":[{\"index\":\"99999\",\"openStatus\":true}]}]"));
        TrackedSection tracked = section(false); // index 03608, not in response

        scheduler.checkOneQuery(new SchedulerService.CourseQuery(2025, "9", "NB", "198"), List.of(tracked));

        assertThat(tracked.isOpen()).isFalse();
        verify(smsService, never()).sendSms(any(), any());
    }

    @Test
    void checkOneQueryAlertsForASectionFoundOpenInTheResponse() throws IOException {
        stubResponse(gzip("[{\"sections\":[{\"index\":\"03608\",\"openStatus\":true}]}]"));
        TrackedSection tracked = section(false);

        scheduler.checkOneQuery(new SchedulerService.CourseQuery(2025, "9", "NB", "198"), List.of(tracked));

        assertThat(tracked.isOpen()).isTrue();
        verify(smsService).sendSms(eq("+12015550123"), contains("03608"));
    }

    // ---- checkRutgersCourses: grouping ----

    @Test
    void doesNotCallTheApiAtAllWhenNothingIsTracked() {
        when(repository.findAll()).thenReturn(List.of());

        scheduler.checkRutgersCourses();

        verifyNoInteractions(restTemplate);
    }

    @Test
    void issuesOneRequestPerDistinctQueryCombination() throws IOException {
        TrackedSection cs = section(false);
        TrackedSection alsoCs = section(false);
        alsoCs.setSectionIndex("03609");
        TrackedSection math = section(false);
        math.setSubject("640");
        when(repository.findAll()).thenReturn(List.of(cs, alsoCs, math));
        stubResponse(gzip("[]"));

        scheduler.checkRutgersCourses();

        // 3 tracked sections, but only 2 distinct subject/term/year/campus combos.
        verify(restTemplate, times(2))
                .exchange(anyString(), eq(HttpMethod.GET), any(), eq(byte[].class));
    }

    // ---- reaper ----

    @Test
    void reapsUnconfirmedSectionsOlderThanTheTtl() {
        TrackedSection stale = section(false);
        stale.setConfirmed(false);
        stale.setCreatedAt(Instant.now().minus(SchedulerService.UNCONFIRMED_TTL).minusSeconds(60));
        when(repository.findByConfirmedFalseAndCreatedAtBefore(any())).thenReturn(List.of(stale));

        scheduler.reapAbandonedUnconfirmedSections();

        verify(repository).deleteAll(List.of(stale));
    }

    @Test
    void reaperDoesNothingWhenThereIsNothingStale() {
        when(repository.findByConfirmedFalseAndCreatedAtBefore(any())).thenReturn(List.of());

        scheduler.reapAbandonedUnconfirmedSections();

        verify(repository, never()).deleteAll(any());
    }

    @Test
    void reaperCutoffIsTtlInThePast() {
        when(repository.findByConfirmedFalseAndCreatedAtBefore(any())).thenReturn(List.of());
        Instant before = Instant.now().minus(SchedulerService.UNCONFIRMED_TTL);

        scheduler.reapAbandonedUnconfirmedSections();

        verify(repository).findByConfirmedFalseAndCreatedAtBefore(argThat(cutoff ->
                !cutoff.isBefore(before.minusSeconds(5)) && !cutoff.isAfter(Instant.now())));
    }
}
