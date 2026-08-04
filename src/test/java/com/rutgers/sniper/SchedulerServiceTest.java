package com.rutgers.sniper;

import com.rutgers.sniper.model.TrackedSection;
import com.rutgers.sniper.repository.SectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SchedulerServiceTest {

    private SectionRepository repository;
    private SmsService smsService;
    private SchedulerService scheduler;

    @BeforeEach
    void setUp() {
        repository = mock(SectionRepository.class);
        smsService = mock(SmsService.class);
        scheduler = new SchedulerService(repository, smsService);
    }

    private static TrackedSection section(boolean currentlyMarkedOpen) {
        TrackedSection section = new TrackedSection();
        section.setSectionIndex("03608");
        section.setUserContact("+12015550123");
        section.setOpen(currentlyMarkedOpen);
        return section;
    }

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
}
