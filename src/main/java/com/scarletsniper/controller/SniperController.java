package com.scarletsniper.controller;

import com.scarletsniper.PhoneVerificationService;
import com.scarletsniper.RutgersDefaults;
import com.scarletsniper.SectionValidator;
import com.scarletsniper.SlidingWindowLimiter;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class SniperController {

    private static final Logger log = LoggerFactory.getLogger(SniperController.class);

    private static final int MAX_CREATES_PER_IP = 5;
    private static final Duration CREATE_WINDOW = Duration.ofMinutes(10);

    // Stricter than the per-IP limit: a single phone number has no
    // legitimate reason to receive many verification texts in a row, and
    // per-IP alone doesn't stop someone spreading requests across networks.
    private static final int MAX_CREATES_PER_PHONE = 3;
    private static final Duration PHONE_WINDOW = Duration.ofHours(1);

    private static final int MAX_VERIFY_ATTEMPTS = 5;
    private static final Duration VERIFY_WINDOW = Duration.ofMinutes(15);

    private final SectionRepository repository;
    private final PhoneVerificationService verificationService;

    private final SlidingWindowLimiter createsByIp = new SlidingWindowLimiter(MAX_CREATES_PER_IP, CREATE_WINDOW);
    private final SlidingWindowLimiter createsByPhone = new SlidingWindowLimiter(MAX_CREATES_PER_PHONE, PHONE_WINDOW);
    private final SlidingWindowLimiter verifyAttempts = new SlidingWindowLimiter(MAX_VERIFY_ATTEMPTS, VERIFY_WINDOW);

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

        String sectionIndex = trimOrNull(request.sectionIndex());
        String userContact = trimOrNull(request.userContact());
        String subject = defaultIfBlank(request.subject(), RutgersDefaults.SUBJECT);
        String term = defaultIfBlank(request.term(), RutgersDefaults.TERM);
        String campus = defaultIfBlank(request.campus(), RutgersDefaults.CAMPUS);
        Integer year = request.year() != null ? request.year() : RutgersDefaults.YEAR;

        if (!SectionValidator.isValidSectionIndex(sectionIndex)
                || !SectionValidator.isValidPhone(userContact)
                || !SectionValidator.isValidSubject(subject)
                || !SectionValidator.isValidTerm(term)
                || !SectionValidator.isValidCampus(campus)
                || !SectionValidator.isValidYear(year)) {
            return ResponseEntity.badRequest().build();
        }

        if (createsByIp.isLimited(httpRequest.getRemoteAddr()) || createsByPhone.isLimited(userContact)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }

        TrackedSection section = new TrackedSection();
        section.setSectionIndex(sectionIndex);
        section.setUserContact(userContact);
        section.setSubject(subject);
        section.setTerm(term);
        section.setCampus(campus);
        section.setYear(year);
        section.setOpen(false);
        section.setConfirmed(!verificationService.isEnabled());
        section.setOwnerToken(UUID.randomUUID().toString());
        section.setCreatedAt(Instant.now());

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
    // texted. Idempotent once confirmed, and throttled so a 6-digit code
    // can't simply be guessed by whoever holds the owner token.
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
                    String code = trimOrNull(request.code());
                    if (!SectionValidator.isValidVerifyCode(code)) {
                        return ResponseEntity.badRequest().<Void>build();
                    }
                    if (verifyAttempts.isLimited(String.valueOf(id))) {
                        log.warn("Verification attempts exhausted for section {}", id);
                        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).<Void>build();
                    }
                    if (!verificationService.checkCode(section.getUserContact(), code)) {
                        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).<Void>build();
                    }
                    section.setConfirmed(true);
                    repository.save(section);
                    verifyAttempts.reset(String.valueOf(id));
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
                    if (createsByPhone.isLimited(section.getUserContact())) {
                        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).<Void>build();
                    }
                    try {
                        verificationService.sendCode(section.getUserContact());
                    } catch (PhoneVerificationService.VerificationSendException e) {
                        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).<Void>build();
                    }
                    // A fresh code invalidates any in-flight guessing.
                    verifyAttempts.reset(String.valueOf(id));
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

    private static String trimOrNull(String s) {
        return s == null ? null : s.trim();
    }

    private static String defaultIfBlank(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }
}
