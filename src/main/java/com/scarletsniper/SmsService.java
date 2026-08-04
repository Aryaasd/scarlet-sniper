package com.scarletsniper;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SmsService {

    private static final Logger log = LoggerFactory.getLogger(SmsService.class);

    @Value("${twilio.account.sid}")
    private String accountSid;

    @Value("${twilio.auth.token}")
    private String authToken;

    @Value("${twilio.phone.number}")
    private String fromPhone;

    @PostConstruct
    public void init() {
        try {
            Twilio.init(accountSid, authToken);
        } catch (Exception e) {
            log.warn("Twilio failed to init (local mode). SMS will be logged to console instead.");
        }
    }

    /**
     * @return true only if Twilio accepted the message. Callers must not
     * treat a send as delivered without checking — a swallowed failure
     * here previously meant an alert was marked sent and never retried.
     */
    public boolean sendSms(String toPhone, String messageBody) {
        try {
            Message.creator(
                new PhoneNumber(toPhone),
                new PhoneNumber(fromPhone),
                messageBody
            ).create();
            log.info("SMS sent to {}", toPhone);
            return true;
        } catch (Exception e) {
            log.error("Failed to send SMS to {}: {}", toPhone, e.getMessage());
            return false;
        }
    }
}
