package com.scarletsniper.dto;

import com.scarletsniper.model.TrackedSection;

/**
 * Response for a successful POST /api/sections. The only place ownerToken
 * is ever sent to a client — the caller must hold onto it to list,
 * delete, or verify this section later.
 *
 * codeSent is false either because verification isn't configured
 * (section.confirmed will already be true — nothing to do) or because
 * sending genuinely failed and the caller should try the resend endpoint.
 */
public record SectionCreatedResponse(TrackedSection section, String ownerToken, boolean codeSent) {}
