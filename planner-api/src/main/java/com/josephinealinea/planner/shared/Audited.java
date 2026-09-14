package com.josephinealinea.planner.shared;

import java.time.Instant;

/**
 * A stored record that remembers who made it and who touched it last.
 *
 * Implemented by the records a member can create and edit — destinations,
 * checklist items, itinerary entries, budget rows and the trip itself. The
 * stamp is written by {@link Audit} rather than by each setter, so the rule for
 * when each field is set lives in exactly one place.
 *
 * Deliberately a user id rather than a name or an email. Both of those change:
 * a screen name can be edited at any time and the member list already resolves
 * one per request, so a stored copy would be a stale answer to a question the
 * user record can answer correctly. An id also survives a member being removed
 * from the trip, which is precisely the case where "who did this?" gets asked.
 */
public interface Audited {

    Instant getCreatedAt();
    void setCreatedAt(Instant createdAt);

    String getCreatedByUserId();
    void setCreatedByUserId(String userId);

    Instant getUpdatedAt();
    void setUpdatedAt(Instant updatedAt);

    String getUpdatedByUserId();
    void setUpdatedByUserId(String userId);
}
