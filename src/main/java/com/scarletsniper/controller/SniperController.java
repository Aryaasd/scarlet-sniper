package com.scarletsniper.controller;

import com.scarletsniper.PhoneVerificationService;
import com.scarletsniper.RutgersDefaults;
import com.scarletsniper.dto.SectionCreateRequest;
import com.scarletsniper.dto.SectionCreatedResponse;
import com.scarletsniper.dto.VerifyCodeRequest;
import com.scarletsniper.model.TrackedSection;
import com.scarletsniper.repository.SectionRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api")
public class SniperController {

    private static final Logger log = LoggerFactory.getLogger(SniperController.class);

    private static final int MAX_CREATES_PER_WINDOW = 5;
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(10);

    private final SectionRepository repository;
    private final PhoneVerificationService verificationService;

    // Simple in-memory per-IP throttle on section creation. Good enough for
    // a single-instance deployment; wouldn't survive a horizontally-scaled
    // one, but this app doesn't run as more than one instance.
    private final Map<String, Deque<Instant>> creationTimestampsByIp = new ConcurrentHashMap<>();

    public SniperController(SectionRepository repository, PhoneVerificationService verificationService) {
        this.repository = repository;
        this.verificationService = verificationService;
    }

    // Only returns sections owned by one of the caller's own tokens.
    // No token presented -> no data, never the full table.
    @GetMapping("/sections")
    public List<TrackedSection> getSections(
            @RequestHeader(value = "X-Owner-Tokens", required = false) String ownerTokensHeader) {
        if (ownerTokensHeader == null || ownerTokensHeader.isBlank()) {
            return List.of();
        }
        Set<String> tokens = Set.of(ownerTokensHeader.split(","));
        return repository.findAll().stream()
                .filter(s -> tokens.contains(s.getOwnerToken()))
                .toList();
    }

    // Creates the watch unconfirmed and fires off a verification code.
    // SchedulerService will never text userContact until it's confirmed
    // via /sections/{id}/verify — this is what stops someone registering
    // a stranger's number.
    @PostMapping("/sections")
    public ResponseEntity<SectionCreatedResponse> addSection(
            @RequestBody SectionCreateRequest request, HttpServletRequest httpRequest) {

        if (isRateLimited(httpRequest.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        if (isBlank(request.sectionIndex()) || isBlank(request.userContact())) {
            return ResponseEntity.badRequest().build();
        }

        TrackedSection section = new TrackedSection();
        section.setSectionIndex(request.sectionIndex().trim());
        section.setUserContact(request.userContact().trim());
        section.setSubject(defaultIfBlank(request.subject(), RutgersDefaults.SUBJECT));
        section.setTerm(defaultIfBlank(request.term(), RutgersDefaults.TERM));
        section.setCampus(defaultIfBlank(request.campus(), RutgersDefaults.CAMPUS));
        section.setYear(request.year() != null ? request.year() : RutgersDefaults.YEAR);
        section.setOpen(false);
        section.setConfirmed(!verificationService.isEnabled());
        section.setOwnerToken(UUID.randomUUID().toString());

        TrackedSection saved = repository.save(section);
        log.info("New watch registered: section {} ({} {} {} {})", saved.getSectionIndex(),
                saved.getSubject(), saved.getTerm(), saved.getYear(), saved.getCampus());

        boolean codeSent = false;
        if (!saved.isConfirmed()) {
            try {
                verificationService.sendCode(saved.getUserContact());
                codeSent = true;
            } catch (PhoneVerificationService.VerificationSendException e) {
                // Section still exists, unconfirmed; caller can retry via /resend-code.
            }
        }

        return ResponseEntity.ok(new SectionCreatedResponse(saved, saved.getOwnerToken(), codeSent));
    }

    // Confirms a watch's phone number. Required before it will ever be
    // texted. Idempotent once confirmed.
    @PostMapping("/sections/{id}/verify")
    public ResponseEntity<Void> verifySection(
            @PathVariable Long id,
            @RequestBody VerifyCodeRequest request,
            @RequestHeader(value = "X-Owner-Token", required = false) String ownerToken) {

        return repository.findById(id)
                .map(section -> {
                    if (ownerToken == null || !ownerToken.equals(section.getOwnerToken())) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).<Void>build();
                    }
                    if (section.isConfirmed()) {
                        return ResponseEntity.noContent().<Void>build();
                    }
                    if (isBlank(request.code())) {
                        return ResponseEntity.badRequest().<Void>build();
                    }
                    boolean valid = verificationService.checkCode(section.getUserContact(), request.code().trim());
                    if (!valid) {
                        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).<Void>build();
                    }
                    section.setConfirmed(true);
                    repository.save(section);
                    log.info("Section {} verified", section.getSectionIndex());
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // Re-sends a verification code, e.g. after the original expired.
    @PostMapping("/sections/{id}/resend-code")
    public ResponseEntity<Void> resendCode(
            @PathVariable Long id,
            @RequestHeader(value = "X-Owner-Token", required = false) String ownerToken) {

        return repository.findById(id)
                .map(section -> {
                    if (ownerToken == null || !ownerToken.equals(section.getOwnerToken())) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).<Void>build();
                    }
                    if (section.isConfirmed()) {
                        return ResponseEntity.status(HttpStatus.CONFLICT).<Void>build();
                    }
                    try {
                        verificationService.sendCode(section.getUserContact());
                    } catch (PhoneVerificationService.VerificationSendException e) {
                        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).<Void>build();
                    }
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // Requires the token handed out at creation. Wrong or missing token ->
    // 403, not a silent no-op, so the caller knows it didn't work.
    @DeleteMapping("/sections/{id}")
    public ResponseEntity<Void> deleteSection(
            @PathVariable Long id,
            @RequestHeader(value = "X-Owner-Token", required = false) String ownerToken) {

        return repository.findById(id)
                .map(section -> {
                    if (ownerToken == null || !ownerToken.equals(section.getOwnerToken())) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).<Void>build();
                    }
                    repository.deleteById(id);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private boolean isRateLimited(String clientIp) {
        Instant now = Instant.now();
        Deque<Instant> timestamps = creationTimestampsByIp.computeIfAbsent(clientIp, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && Duration.between(timestamps.peekFirst(), now).compareTo(RATE_LIMIT_WINDOW) > 0) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= MAX_CREATES_PER_WINDOW) {
                return true;
            }
            timestamps.addLast(now);
            return false;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String defaultIfBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }
}
