package com.scarletsniper.dto;

/**
 * Client-supplied fields for registering a watch. Deliberately excludes
 * id/isOpen/ownerToken so a POST body can never be used to overwrite an
 * existing row or forge ownership — the controller builds the entity itself.
 */
public record SectionCreateRequest(
        String sectionIndex,
        String subject,
        String term,
        Integer year,
        String campus,
        String userContact
) {}
