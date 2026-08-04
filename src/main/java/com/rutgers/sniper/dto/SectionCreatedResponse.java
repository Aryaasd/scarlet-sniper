package com.rutgers.sniper.dto;

import com.rutgers.sniper.model.TrackedSection;

/**
 * Response for a successful POST /api/sections. The only place ownerToken
 * is ever sent to a client — the caller must hold onto it to list or
 * delete this section later.
 */
public record SectionCreatedResponse(TrackedSection section, String ownerToken) {}
