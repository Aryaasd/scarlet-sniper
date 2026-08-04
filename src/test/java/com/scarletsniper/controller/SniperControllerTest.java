package com.scarletsniper.controller;

import com.scarletsniper.PhoneVerificationService;
import com.scarletsniper.dto.SectionCreateRequest;
import com.scarletsniper.dto.SectionCreatedResponse;
import com.scarletsniper.dto.VerifyCodeRequest;
import com.scarletsniper.model.TrackedSection;
import com.scarletsniper.repository.SectionRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class SniperControllerTest {

    private static final String PHONE = "+12015550123";
    private static final String INDEX = "03608";

    private SectionRepository repository;
    private PhoneVerificationService verificationService;
    private SniperController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        repository = mock(SectionRepository.class);
        verificationService = mock(PhoneVerificationService.class);
        // Matches the local-dev/test default: no Verify Service configured.
        when(verificationService.isEnabled()).thenReturn(false);
        controller = new SniperController(repository, verificationService);
        request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.1");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static SectionCreateRequest req() {
        return new SectionCreateRequest(INDEX, null, null, null, null, PHONE);
    }

    private static SectionCreateRequest req(String phone) {
        return new SectionCreateRequest(INDEX, null, null, null, null, phone);
    }

    private static TrackedSection existing(String token, boolean confirmed) {
        TrackedSection section = new TrackedSection();
        section.setId(1L);
        section.setOwnerToken(token);
        section.setConfirmed(confirmed);
        section.setUserContact(PHONE);
        return section;
    }

    // ---- GET: owner scoping ----

    @Test
    void getSectionsReturnsEmptyWithoutToken() {
        assertThat(controller.getSections(null)).isEmpty();
        assertThat(controller.getSections("")).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void getSectionsFiltersToOwnedTokensOnly() {
        TrackedSection mine = new TrackedSection();
        mine.setOwnerToken("abc");
        TrackedSection theirs = new TrackedSection();
        theirs.setOwnerToken("xyz");
        when(repository.findAll()).thenReturn(List.of(mine, theirs));

        assertThat(controller.getSections("abc")).containsExactly(mine);
    }

    @Test
    void getSectionsSupportsMultipleCommaSeparatedTokens() {
        TrackedSection a = new TrackedSection();
        a.setOwnerToken("abc");
        TrackedSection b = new TrackedSection();
        b.setOwnerToken("def");
        TrackedSection other = new TrackedSection();
        other.setOwnerToken("xyz");
        when(repository.findAll()).thenReturn(List.of(a, b, other));

        assertThat(controller.getSections("abc,def")).containsExactlyInAnyOrder(a, b);
    }

    // ---- POST: creation + defaults ----

    @Test
    void addSectionFillsInDefaultsAndGeneratesOwnerToken() {
        ResponseEntity<SectionCreatedResponse> response = controller.addSection(req(), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        SectionCreatedResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.section().getSubject()).isEqualTo("198");
        assertThat(body.section().getTerm()).isEqualTo("9");
        assertThat(body.section().getYear()).isEqualTo(2025);
        assertThat(body.section().getCampus()).isEqualTo("NB");
        assertThat(body.section().isOpen()).isFalse();
        assertThat(body.ownerToken()).isNotBlank();
        assertThat(body.section().getOwnerToken()).isEqualTo(body.ownerToken());
    }

    @Test
    void addSectionStampsCreatedAtForTheReaper() {
        ResponseEntity<SectionCreatedResponse> response = controller.addSection(req(), request);

        assertThat(response.getBody().section().getCreatedAt()).isNotNull();
    }

    @Test
    void addSectionHonoursExplicitQueryOverrides() {
        ResponseEntity<SectionCreatedResponse> response = controller.addSection(
                new SectionCreateRequest(INDEX, "640", "1", 2027, "NK", PHONE), request);

        TrackedSection s = response.getBody().section();
        assertThat(s.getSubject()).isEqualTo("640");
        assertThat(s.getTerm()).isEqualTo("1");
        assertThat(s.getYear()).isEqualTo(2027);
        assertThat(s.getCampus()).isEqualTo("NK");
    }

    // ---- POST: validation ----

    @ParameterizedTest
    @CsvSource({
            "'',       +12015550123",   // blank index
            "1234,     +12015550123",   // too short
            "123456,   +12015550123",   // too long
            "abcde,    +12015550123",   // non-numeric
            "03608,    ''",             // blank phone
            "03608,    2015550123",     // missing +1
            "03608,    +1201555012",    // too short
            "03608,    +120155501234",  // too long
            "03608,    +442015550123",  // non-US
    })
    void addSectionRejectsInvalidInput(String index, String phone) {
        ResponseEntity<SectionCreatedResponse> response = controller.addSection(
                new SectionCreateRequest(index, null, null, null, null, phone), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(repository, never()).save(any());
    }

    @Test
    void addSectionRejectsNullFields() {
        assertThat(controller.addSection(
                new SectionCreateRequest(null, null, null, null, null, PHONE), request)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.addSection(
                new SectionCreateRequest(INDEX, null, null, null, null, null), request)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // Over-long values previously reached the DB and surfaced as a raw
    // constraint violation (500) instead of a clean 400.
    @Test
    void addSectionRejectsOverlongOptionalFieldsBeforeTheyReachTheDatabase() {
        assertThat(controller.addSection(
                new SectionCreateRequest(INDEX, "12345678901", null, null, null, PHONE), request)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.addSection(
                new SectionCreateRequest(INDEX, null, "123456", null, null, PHONE), request)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.addSection(
                new SectionCreateRequest(INDEX, null, null, null, "12345678901", PHONE), request)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(repository, never()).save(any());
    }

    @Test
    void addSectionRejectsOutOfRangeYear() {
        assertThat(controller.addSection(
                new SectionCreateRequest(INDEX, null, null, 1900, null, PHONE), request)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.addSection(
                new SectionCreateRequest(INDEX, null, null, 9999, null, PHONE), request)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void addSectionTrimsWhitespace() {
        ResponseEntity<SectionCreatedResponse> response = controller.addSection(
                new SectionCreateRequest("  03608  ", null, null, null, null, "  " + PHONE + "  "), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().section().getSectionIndex()).isEqualTo(INDEX);
        assertThat(response.getBody().section().getUserContact()).isEqualTo(PHONE);
    }

    // ---- POST: verification wiring ----

    @Test
    void addSectionAutoConfirmsWhenVerificationDisabled() {
        ResponseEntity<SectionCreatedResponse> response = controller.addSection(req(), request);

        assertThat(response.getBody().section().isConfirmed()).isTrue();
        assertThat(response.getBody().codeSent()).isFalse();
        verify(verificationService, never()).sendCode(any());
    }

    @Test
    void addSectionSendsCodeAndLeavesUnconfirmedWhenVerificationEnabled() {
        when(verificationService.isEnabled()).thenReturn(true);

        ResponseEntity<SectionCreatedResponse> response = controller.addSection(req(), request);

        assertThat(response.getBody().section().isConfirmed()).isFalse();
        assertThat(response.getBody().codeSent()).isTrue();
        verify(verificationService).sendCode(PHONE);
    }

    @Test
    void addSectionStillCreatesSectionWhenSendCodeFails() {
        when(verificationService.isEnabled()).thenReturn(true);
        doThrow(new PhoneVerificationService.VerificationSendException("boom", new RuntimeException()))
                .when(verificationService).sendCode(any());

        ResponseEntity<SectionCreatedResponse> response = controller.addSection(req(), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().section().isConfirmed()).isFalse();
        assertThat(response.getBody().codeSent()).isFalse();
        verify(repository).save(any());
    }

    // ---- POST: rate limiting ----

    @Test
    void rateLimitsRepeatedCreationsFromSameIp() {
        // Vary the phone so the per-phone limiter isn't what trips first.
        for (int i = 0; i < 5; i++) {
            assertThat(controller.addSection(req("+120155501" + (10 + i)), request).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        assertThat(controller.addSection(req("+12015550199"), request).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void rateLimitTracksIpsIndependently() {
        HttpServletRequest otherIp = mock(HttpServletRequest.class);
        when(otherIp.getRemoteAddr()).thenReturn("198.51.100.7");

        for (int i = 0; i < 5; i++) {
            controller.addSection(req("+120155501" + (10 + i)), request);
        }

        assertThat(controller.addSection(req("+12015550188"), otherIp).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // Per-IP alone doesn't stop someone spreading requests across networks
    // to spam one person's phone with verification texts.
    @Test
    void rateLimitsRepeatedCreationsForTheSamePhoneAcrossDifferentIps() {
        for (int i = 0; i < 3; i++) {
            HttpServletRequest ip = mock(HttpServletRequest.class);
            when(ip.getRemoteAddr()).thenReturn("203.0.113." + (10 + i));
            assertThat(controller.addSection(req(), ip).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        HttpServletRequest freshIp = mock(HttpServletRequest.class);
        when(freshIp.getRemoteAddr()).thenReturn("203.0.113.99");
        assertThat(controller.addSection(req(), freshIp).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void phoneRateLimitDoesNotAffectADifferentNumber() {
        for (int i = 0; i < 3; i++) {
            controller.addSection(req(), request);
        }

        assertThat(controller.addSection(req("+19085551234"), request).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void rateLimitedRequestIsNotPersisted() {
        for (int i = 0; i < 3; i++) {
            controller.addSection(req(), request);
        }
        clearInvocations(repository);

        controller.addSection(req(), request);

        verify(repository, never()).save(any());
    }

    // ---- verify ----

    @Test
    void verifyRejectsWrongOwnerToken() {
        when(repository.findById(1L)).thenReturn(Optional.of(existing("abc", false)));

        ResponseEntity<Void> response = controller.verifySection(1L, new VerifyCodeRequest("123456"), "wrong-token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(verificationService, never()).checkCode(any(), any());
    }

    @Test
    void verifyRejectsMissingOwnerToken() {
        when(repository.findById(1L)).thenReturn(Optional.of(existing("abc", false)));

        ResponseEntity<Void> response = controller.verifySection(1L, new VerifyCodeRequest("123456"), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void verifyIsIdempotentWhenAlreadyConfirmed() {
        when(repository.findById(1L)).thenReturn(Optional.of(existing("abc", true)));

        ResponseEntity<Void> response = controller.verifySection(1L, new VerifyCodeRequest("123456"), "abc");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(verificationService, never()).checkCode(any(), any());
    }

    @ParameterizedTest
    @CsvSource({"''", "'   '", "abc123", "12", "123456789012"})
    void verifyRejectsMalformedCodes(String code) {
        when(repository.findById(1L)).thenReturn(Optional.of(existing("abc", false)));

        ResponseEntity<Void> response = controller.verifySection(1L, new VerifyCodeRequest(code), "abc");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(verificationService, never()).checkCode(any(), any());
    }

    @Test
    void verifyRejectsNullCode() {
        when(repository.findById(1L)).thenReturn(Optional.of(existing("abc", false)));

        assertThat(controller.verifySection(1L, new VerifyCodeRequest(null), "abc").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void verifyRejectsWrongCode() {
        when(repository.findById(1L)).thenReturn(Optional.of(existing("abc", false)));
        when(verificationService.checkCode(PHONE, "999999")).thenReturn(false);

        ResponseEntity<Void> response = controller.verifySection(1L, new VerifyCodeRequest("999999"), "abc");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void verifyConfirmsOnCorrectCode() {
        TrackedSection section = existing("abc", false);
        when(repository.findById(1L)).thenReturn(Optional.of(section));
        when(verificationService.checkCode(PHONE, "123456")).thenReturn(true);

        ResponseEntity<Void> response = controller.verifySection(1L, new VerifyCodeRequest("123456"), "abc");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(section.isConfirmed()).isTrue();
        verify(repository).save(section);
    }

    @Test
    void verifyTrimsWhitespaceAroundTheCode() {
        when(repository.findById(1L)).thenReturn(Optional.of(existing("abc", false)));
        when(verificationService.checkCode(PHONE, "123456")).thenReturn(true);

        assertThat(controller.verifySection(1L, new VerifyCodeRequest(" 123456 "), "abc").getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void verifyReturns404ForUnknownId() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThat(controller.verifySection(99L, new VerifyCodeRequest("123456"), "anything").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- verify: brute-force protection ----

    // A 6-digit code is only a million combinations; without a cap the
    // owner-token holder could simply guess it.
    @Test
    void verifyLocksOutAfterRepeatedWrongCodes() {
        when(repository.findById(1L)).thenReturn(Optional.of(existing("abc", false)));
        when(verificationService.checkCode(any(), any())).thenReturn(false);

        for (int i = 0; i < 5; i++) {
            assertThat(controller.verifySection(1L, new VerifyCodeRequest("00000" + i), "abc").getStatusCode())
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        assertThat(controller.verifySection(1L, new VerifyCodeRequest("999999"), "abc").getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void verifyLockoutStopsCallingTwilioOnceExhausted() {
        when(repository.findById(1L)).thenReturn(Optional.of(existing("abc", false)));
        when(verificationService.checkCode(any(), any())).thenReturn(false);
        for (int i = 0; i < 5; i++) {
            controller.verifySection(1L, new VerifyCodeRequest("00000" + i), "abc");
        }
        clearInvocations(verificationService);

        controller.verifySection(1L, new VerifyCodeRequest("999999"), "abc");

        verify(verificationService, never()).checkCode(any(), any());
    }

    @Test
    void verifyLockoutIsPerSection() {
        TrackedSection first = existing("abc", false);
        TrackedSection second = existing("def", false);
        second.setId(2L);
        when(repository.findById(1L)).thenReturn(Optional.of(first));
        when(repository.findById(2L)).thenReturn(Optional.of(second));
        when(verificationService.checkCode(any(), any())).thenReturn(false);

        for (int i = 0; i < 5; i++) {
            controller.verifySection(1L, new VerifyCodeRequest("00000" + i), "abc");
        }

        // A different section must not inherit the first one's lockout.
        assertThat(controller.verifySection(2L, new VerifyCodeRequest("123456"), "def").getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void resendResetsTheVerifyLockoutBecauseTheOldCodeIsNowInvalid() {
        when(repository.findById(1L)).thenReturn(Optional.of(existing("abc", false)));
        when(verificationService.checkCode(any(), any())).thenReturn(false);
        for (int i = 0; i < 5; i++) {
            controller.verifySection(1L, new VerifyCodeRequest("00000" + i), "abc");
        }
        assertThat(controller.verifySection(1L, new VerifyCodeRequest("111111"), "abc").getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        controller.resendCode(1L, "abc");

        assertThat(controller.verifySection(1L, new VerifyCodeRequest("222222"), "abc").getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // Only a *successful* resend may clear the lockout. If the send failed,
    // no new code exists, the old one is still valid, and clearing would let
    // an attacker reset their guess budget just by forcing send failures.
    @Test
    void failedResendDoesNotResetTheVerifyLockout() {
        when(repository.findById(1L)).thenReturn(Optional.of(existing("abc", false)));
        when(verificationService.checkCode(any(), any())).thenReturn(false);
        for (int i = 0; i < 5; i++) {
            controller.verifySection(1L, new VerifyCodeRequest("00000" + i), "abc");
        }
        assertThat(controller.verifySection(1L, new VerifyCodeRequest("111111"), "abc").getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        doThrow(new PhoneVerificationService.VerificationSendException("boom", new RuntimeException()))
                .when(verificationService).sendCode(any());
        assertThat(controller.resendCode(1L, "abc").getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);

        assertThat(controller.verifySection(1L, new VerifyCodeRequest("222222"), "abc").getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void successfulVerifyClearsTheAttemptCounter() {
        TrackedSection section = existing("abc", false);
        when(repository.findById(1L)).thenReturn(Optional.of(section));
        when(verificationService.checkCode(any(), any())).thenReturn(false);
        for (int i = 0; i < 4; i++) {
            controller.verifySection(1L, new VerifyCodeRequest("00000" + i), "abc");
        }

        when(verificationService.checkCode(any(), any())).thenReturn(true);
        assertThat(controller.verifySection(1L, new VerifyCodeRequest("123456"), "abc").getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        // Counter reset, so a later unconfirmed section at this id isn't
        // immediately locked out.
        section.setConfirmed(false);
        when(verificationService.checkCode(any(), any())).thenReturn(false);
        assertThat(controller.verifySection(1L, new VerifyCodeRequest("654321"), "abc").getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ---- resend ----

    @Test
    void resendRejectsWrongOwnerToken() {
        when(repository.findById(1L)).thenReturn(Optional.of(existing("abc", false)));

        assertThat(controller.resendCode(1L, "wrong-token").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(verificationService, never()).sendCode(any());
    }

    @Test
    void resendRejectsWhenAlreadyConfirmed() {
        when(repository.findById(1L)).thenReturn(Optional.of(existing("abc", true)));

        assertThat(controller.resendCode(1L, "abc").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(verificationService, never()).sendCode(any());
    }

    @Test
    void resendSendsCodeAgain() {
        when(repository.findById(1L)).thenReturn(Optional.of(existing("abc", false)));

        assertThat(controller.resendCode(1L, "abc").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(verificationService).sendCode(PHONE);
    }

    @Test
    void resendReturns502WhenSendFails() {
        when(repository.findById(1L)).thenReturn(Optional.of(existing("abc", false)));
        doThrow(new PhoneVerificationService.VerificationSendException("boom", new RuntimeException()))
                .when(verificationService).sendCode(any());

        assertThat(controller.resendCode(1L, "abc").getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void resendReturns404ForUnknownId() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThat(controller.resendCode(99L, "anything").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // Resend sends a real text, so it shares the per-phone budget rather
    // than being an unlimited free SMS button.
    @Test
    void resendIsRateLimitedPerPhoneNumber() {
        when(repository.findById(1L)).thenReturn(Optional.of(existing("abc", false)));

        for (int i = 0; i < 3; i++) {
            assertThat(controller.resendCode(1L, "abc").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        }

        assertThat(controller.resendCode(1L, "abc").getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    // ---- delete ----

    @Test
    void deleteRejectsWrongOwnerToken() {
        when(repository.findById(1L)).thenReturn(Optional.of(existing("abc", false)));

        assertThat(controller.deleteSection(1L, "wrong-token").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(repository, never()).deleteById(anyLong());
    }

    @Test
    void deleteRejectsMissingOwnerToken() {
        when(repository.findById(1L)).thenReturn(Optional.of(existing("abc", false)));

        assertThat(controller.deleteSection(1L, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(repository, never()).deleteById(anyLong());
    }

    @Test
    void deleteSucceedsWithCorrectOwnerToken() {
        when(repository.findById(1L)).thenReturn(Optional.of(existing("abc", false)));

        assertThat(controller.deleteSection(1L, "abc").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(repository).deleteById(1L);
    }

    @Test
    void deleteReturns404ForUnknownId() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThat(controller.deleteSection(99L, "anything").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
