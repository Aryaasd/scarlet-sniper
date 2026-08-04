package com.scarletsniper.repository;

import com.scarletsniper.model.TrackedSection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface SectionRepository extends JpaRepository<TrackedSection, Long> {
    List<TrackedSection> findBySectionIndex(String sectionIndex);
}