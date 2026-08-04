package com.rutgers.sniper.controller;

import com.rutgers.sniper.dto.SectionCreateRequest;
import com.rutgers.sniper.dto.SectionCreatedResponse;
import com.rutgers.sniper.model.TrackedSection;
import com.rutgers.sniper.repository.SectionRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SniperControllerTest {

    private SectionRepository repository;
    private SniperController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        repository = mock(SectionRepository.class);
        controller = new SniperController(repository);
        request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.1");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

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

        List<TrackedSection> result = controller.getSections("abc");

        assertThat(result).containsExactly(mine);
    }

    @Test
    void addSectionFillsInDefaultsAndGeneratesOwnerToken() {
        SectionCreateRequest req = new SectionCreateRequest("03608", null, null, null, null, "+12015550123");

        ResponseEntity<SectionCreatedResponse> response = controller.addSection(req, request);

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
    void addSectionRejectsMissingFields() {
        ResponseEntity<SectionCreatedResponse> response =
                controller.addSection(new SectionCreateRequest("", null, null, null, null, "+12015550123"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(repository, never()).save(any());
    }

    @Test
    void deleteRejectsWrongOwnerToken() {
        TrackedSection existing = new TrackedSection();
        existing.setId(1L);
        existing.setOwnerToken("abc");
        when(repository.findById(1L)).thenReturn(Optional.of(existing));

        ResponseEntity<Void> response = controller.deleteSection(1L, "wrong-token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(repository, never()).deleteById(anyLong());
    }

    @Test
    void deleteRejectsMissingOwnerToken() {
        TrackedSection existing = new TrackedSection();
        existing.setId(1L);
        existing.setOwnerToken("abc");
        when(repository.findById(1L)).thenReturn(Optional.of(existing));

        ResponseEntity<Void> response = controller.deleteSection(1L, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(repository, never()).deleteById(anyLong());
    }

    @Test
    void deleteSucceedsWithCorrectOwnerToken() {
        TrackedSection existing = new TrackedSection();
        existing.setId(1L);
        existing.setOwnerToken("abc");
        when(repository.findById(1L)).thenReturn(Optional.of(existing));

        ResponseEntity<Void> response = controller.deleteSection(1L, "abc");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(repository).deleteById(1L);
    }

    @Test
    void deleteReturns404ForUnknownId() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        ResponseEntity<Void> response = controller.deleteSection(99L, "anything");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rateLimitsRepeatedCreationsFromSameIp() {
        SectionCreateRequest req = new SectionCreateRequest("03608", null, null, null, null, "+12015550123");

        for (int i = 0; i < 5; i++) {
            ResponseEntity<SectionCreatedResponse> response = controller.addSection(req, request);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        ResponseEntity<SectionCreatedResponse> sixth = controller.addSection(req, request);

        assertThat(sixth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void rateLimitTracksIpsIndependently() {
        SectionCreateRequest req = new SectionCreateRequest("03608", null, null, null, null, "+12015550123");
        HttpServletRequest otherIp = mock(HttpServletRequest.class);
        when(otherIp.getRemoteAddr()).thenReturn("198.51.100.7");

        for (int i = 0; i < 5; i++) {
            controller.addSection(req, request);
        }
        ResponseEntity<SectionCreatedResponse> fromOtherIp = controller.addSection(req, otherIp);

        assertThat(fromOtherIp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
