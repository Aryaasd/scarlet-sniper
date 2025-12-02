package com.rutgers.sniper;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SmsService {

    @Value("${twilio.account.sid}")
    private String accountSid;

    @Value("${twilio.auth.token}")
    private String authToken;

    @Value("${twilio.phone.number}")
    private String fromPhone;

    @PostConstruct
    public void init() {
        Twilio.init(accountSid, authToken);
    }

    public void sendSms(String toPhone, String messageBody) {
        try {
            Message.creator(
                new PhoneNumber(toPhone),
                new PhoneNumber(fromPhone),
                messageBody
            ).create();
            System.out.println("✅ SMS SENT to " + toPhone);
        } catch (Exception e) {
            System.err.println("❌ FAILED TO SEND SMS: " + e.getMessage());
        }
    }
}