package com.rutgers.sniper.model;

import jakarta.persistence.*;
import lombok.Data; // Lombak writes the Getters/Setters for us!

@Entity // This tells Spring: "Make a SQL Table out of this class"
@Data
public class TrackedSection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String sectionIndex;

    private boolean isOpen;

    private String userContact; 

    public TrackedSection() {}

    public TrackedSection(String sectionIndex, String userContact) {
        this.sectionIndex = sectionIndex;
        this.userContact = userContact;
        this.isOpen = false; 
    }
}