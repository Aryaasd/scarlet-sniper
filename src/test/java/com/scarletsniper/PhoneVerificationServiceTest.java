package com.scarletsniper;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Only tests the configuration branching this class controls. The actual
 * Twilio Verify HTTP calls in sendCode/checkCode aren't mocked here — same
 * boundary as SmsService, which doesn't unit-test its Twilio call either.
 */
class PhoneVerificationServiceTest {

    @Test
    void disabledWhenServiceSidBlank() {
        PhoneVerificationService service = new PhoneVerificationService();
        ReflectionTestUtils.setField(service, "verifyServiceSid", "");

        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void disabledWhenServiceSidNull() {
        PhoneVerificationService service = new PhoneVerificationService();
        ReflectionTestUtils.setField(service, "verifyServiceSid", null);

        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void enabledWhenServiceSidConfigured() {
        PhoneVerificationService service = new PhoneVerificationService();
        ReflectionTestUtils.setField(service, "verifyServiceSid", "VAxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");

        assertThat(service.isEnabled()).isTrue();
    }

    @Test
    void sendCodeAndCheckCodeAreNoOpsWhenDisabled() {
        PhoneVerificationService service = new PhoneVerificationService();
        ReflectionTestUtils.setField(service, "verifyServiceSid", "");

        service.sendCode("+12015550123"); // must not throw despite no Twilio config
        assertThat(service.checkCode("+12015550123", "anything")).isTrue();
    }
}
