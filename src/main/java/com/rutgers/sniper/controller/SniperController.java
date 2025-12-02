package com.rutgers.sniper.controller;

import com.rutgers.sniper.model.TrackedSection;
import com.rutgers.sniper.repository.SectionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class SniperController {

    @Autowired
    private SectionRepository repository;

    // 1. Get the list of everything we are watching
    @GetMapping("/sections")
    public List<TrackedSection> getSections() {
        return repository.findAll();
    }

    // 2. Add a new section to watch
    @PostMapping("/sections")
    public TrackedSection addSection(@RequestBody TrackedSection section) {
        // Force it to start as closed so we get alerted if it's open
        section.setOpen(false); 
        return repository.save(section);
    }

    // 3. Delete a section (Stop watching)
    @DeleteMapping("/sections/{id}")
    public void deleteSection(@PathVariable Long id) {
        repository.deleteById(id);
    }
}