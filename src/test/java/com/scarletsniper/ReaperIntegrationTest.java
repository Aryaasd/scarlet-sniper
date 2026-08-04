package com.scarletsniper;

import com.scarletsniper.model.TrackedSection;
import com.scarletsniper.repository.SectionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

// Exercises the reaper against the real JPA query + schema, not a mock —
// this is what catches a derived-query name that doesn't match the entity.
@SpringBootTest
class ReaperIntegrationTest {

    @Autowired SectionRepository repository;
    @Autowired SchedulerService scheduler;

    private TrackedSection make(boolean confirmed, Instant createdAt) {
        TrackedSection s = new TrackedSection();
        s.setSectionIndex("03608");
        s.setSubject("198"); s.setTerm("9"); s.setYear(2025); s.setCampus("NB");
        s.setUserContact("+12015550123");
        s.setOwnerToken(java.util.UUID.randomUUID().toString());
        s.setConfirmed(confirmed);
        s.setCreatedAt(createdAt);
        return repository.save(s);
    }

    @Test
    void reapsOnlyStaleUnconfirmedRows() {
        repository.deleteAll();
        Instant old = Instant.now().minus(SchedulerService.UNCONFIRMED_TTL).minusSeconds(600);
        Instant fresh = Instant.now();

        TrackedSection staleUnconfirmed = make(false, old);
        TrackedSection freshUnconfirmed = make(false, fresh);
        TrackedSection staleConfirmed   = make(true,  old);

        scheduler.reapAbandonedUnconfirmedSections();

        assertThat(repository.findById(staleUnconfirmed.getId())).isEmpty();
        assertThat(repository.findById(freshUnconfirmed.getId())).isPresent();
        assertThat(repository.findById(staleConfirmed.getId())).isPresent();
    }
}
