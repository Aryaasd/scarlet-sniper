package com.scarletsniper.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

// Deliberately @Getter/@Setter rather than @Data: Lombok's generated
// equals/hashCode/toString on a JPA entity is a known footgun once lazy
// proxies or relationships enter the picture.
@Entity
@Getter
@Setter
public class TrackedSection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String sectionIndex;
    private String subject;
    private String term;

    @Column(name = "academic_year")
    private Integer year;

    private String campus;

    private boolean isOpen;

    // Only sections with a verified userContact are ever texted — see
    // SchedulerService.applyStatus and PhoneVerificationService.
    private boolean confirmed;

    private String userContact;

    // Used by SchedulerService to reap unconfirmed watches that were
    // registered and then abandoned, so they don't poll Rutgers forever.
    @JsonIgnore
    private Instant createdAt;

    // Proof of ownership for this watch. Handed to the client once, on
    // creation, and never included in any other response (see addSection
    // in SniperController) so GET /api/sections can't leak it.
    @JsonIgnore
    private String ownerToken;
}
