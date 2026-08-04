package com.scarletsniper;

import java.util.regex.Pattern;

/**
 * Input validation for client-supplied section fields.
 *
 * Bounds here are deliberately at or under the column widths in
 * V1__init_schema.sql — without them, over-long input reaches the DB and
 * surfaces as an unhandled constraint violation (500) instead of a 400.
 */
public final class SectionValidator {

    private static final Pattern SECTION_INDEX = Pattern.compile("^\\d{5}$");
    private static final Pattern US_E164 = Pattern.compile("^\\+1\\d{10}$");
    private static final Pattern SUBJECT = Pattern.compile("^[A-Za-z0-9]{1,10}$");
    private static final Pattern TERM = Pattern.compile("^\\d{1,5}$");
    private static final Pattern CAMPUS = Pattern.compile("^[A-Za-z]{1,10}$");
    private static final Pattern VERIFY_CODE = Pattern.compile("^\\d{4,10}$");

    private static final int MIN_YEAR = 2000;
    private static final int MAX_YEAR = 2100;

    private SectionValidator() {}

    public static boolean isValidSectionIndex(String value) {
        return value != null && SECTION_INDEX.matcher(value).matches();
    }

    /** US E.164 only — SmsService and Twilio Verify both assume +1##########. */
    public static boolean isValidPhone(String value) {
        return value != null && US_E164.matcher(value).matches();
    }

    public static boolean isValidSubject(String value) {
        return value != null && SUBJECT.matcher(value).matches();
    }

    public static boolean isValidTerm(String value) {
        return value != null && TERM.matcher(value).matches();
    }

    public static boolean isValidCampus(String value) {
        return value != null && CAMPUS.matcher(value).matches();
    }

    public static boolean isValidYear(Integer value) {
        return value != null && value >= MIN_YEAR && value <= MAX_YEAR;
    }

    public static boolean isValidVerifyCode(String value) {
        return value != null && VERIFY_CODE.matcher(value).matches();
    }
}
