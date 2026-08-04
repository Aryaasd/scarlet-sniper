package com.scarletsniper.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
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

    // Proof of ownership for this watch. Handed to the client once, on
    // creation, and never included in any other response (see addSection
    // in SniperController) so GET /api/sections can't leak it.
    @JsonIgnore
    private String ownerToken;
}
