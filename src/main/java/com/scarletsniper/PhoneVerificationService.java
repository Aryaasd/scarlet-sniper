package com.scarletsniper;

import com.twilio.rest.verify.v2.service.Verification;
import com.twilio.rest.verify.v2.service.VerificationCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Wraps Twilio Verify so a phone number has to prove it's reachable before
 * SchedulerService will ever text it. Without a configured Verify Service
 * (local dev, or this repo's own CI/tests) verification is a no-op that
 * auto-approves — same fallback pattern as SmsService's "local mode" so
 * nothing here requires live Twilio credentials to run or demo.
 */
@Service
public class PhoneVerificationService {

    private static final Logger log = LoggerFactory.getLogger(PhoneVerificationService.class);

    @Value("${twilio.verify.service.sid:}")
    private String verifyServiceSid;

    public boolean isEnabled() {
        return verifyServiceSid != null && !verifyServiceSid.isBlank();
    }

    public void sendCode(String phoneNumber) {
        if (!isEnabled()) {
            log.warn("Phone verification not configured (TWILIO_VERIFY_SERVICE_SID unset) — {} will be auto-approved.", phoneNumber);
            return;
        }
        try {
            Verification.creator(verifyServiceSid, phoneNumber, "sms").create();
            log.info("Verification code sent to {}", phoneNumber);
        } catch (Exception e) {
            log.error("Failed to send verification code to {}: {}", phoneNumber, e.getMessage());
            throw new VerificationSendException("Could not send verification code", e);
        }
    }

    public boolean checkCode(String phoneNumber, String code) {
        if (!isEnabled()) {
            return true;
        }
        try {
            VerificationCheck check = VerificationCheck.creator(verifyServiceSid)
                    .setTo(phoneNumber)
                    .setCode(code)
                    .create();
            return "approved".equals(check.getStatus());
        } catch (Exception e) {
            log.warn("Verification check failed for {}: {}", phoneNumber, e.getMessage());
            return false;
        }
    }

    public static class VerificationSendException extends RuntimeException {
        public VerificationSendException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
