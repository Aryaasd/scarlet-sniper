package com.scarletsniper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SectionValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"03608", "00000", "99999"})
    void acceptsFiveDigitSectionIndexes(String value) {
        assertThat(SectionValidator.isValidSectionIndex(value)).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "1234", "123456", "0360a", "abcde", "0360 ", "-1234",
            "012345678901234567890123456789"})
    void rejectsBadSectionIndexes(String value) {
        assertThat(SectionValidator.isValidSectionIndex(value)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"+12015550123", "+19085551234"})
    void acceptsUsE164Numbers(String value) {
        assertThat(SectionValidator.isValidPhone(value)).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "2015550123", "+1201555012", "+120155501234", "+442015550123",
            "+1-201-555-0123", "(201) 555-0123", "+1201555012a"})
    void rejectsBadPhoneNumbers(String value) {
        assertThat(SectionValidator.isValidPhone(value)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"198", "640", "CS", "01"})
    void acceptsAlphanumericSubjects(String value) {
        assertThat(SectionValidator.isValidSubject(value)).isTrue();
    }

    // The DB column is VARCHAR(10) — anything longer must be rejected here
    // rather than blowing up as a constraint violation.
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "12345678901", "19-8", "19 8", "198;DROP"})
    void rejectsBadSubjects(String value) {
        assertThat(SectionValidator.isValidSubject(value)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"9", "1", "12345"})
    void acceptsNumericTerms(String value) {
        assertThat(SectionValidator.isValidTerm(value)).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "123456", "fall", "9a"})
    void rejectsBadTerms(String value) {
        assertThat(SectionValidator.isValidTerm(value)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NB", "NK", "CM"})
    void acceptsAlphabeticCampuses(String value) {
        assertThat(SectionValidator.isValidCampus(value)).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "N1", "12345678901", "N-B"})
    void rejectsBadCampuses(String value) {
        assertThat(SectionValidator.isValidCampus(value)).isFalse();
    }

    @Test
    void acceptsSaneYears() {
        assertThat(SectionValidator.isValidYear(2025)).isTrue();
        assertThat(SectionValidator.isValidYear(2000)).isTrue();
        assertThat(SectionValidator.isValidYear(2100)).isTrue();
    }

    @Test
    void rejectsOutOfRangeYears() {
        assertThat(SectionValidator.isValidYear(null)).isFalse();
        assertThat(SectionValidator.isValidYear(1999)).isFalse();
        assertThat(SectionValidator.isValidYear(2101)).isFalse();
        assertThat(SectionValidator.isValidYear(-2025)).isFalse();
        assertThat(SectionValidator.isValidYear(Integer.MAX_VALUE)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234", "123456", "1234567890"})
    void acceptsNumericVerifyCodes(String value) {
        assertThat(SectionValidator.isValidVerifyCode(value)).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "123", "12345678901", "12345a", "12 34", "abcdef"})
    void rejectsBadVerifyCodes(String value) {
        assertThat(SectionValidator.isValidVerifyCode(value)).isFalse();
    }
}
