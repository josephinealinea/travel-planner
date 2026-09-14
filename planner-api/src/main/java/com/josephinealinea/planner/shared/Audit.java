package com.josephinealinea.planner.shared;

import java.time.Instant;

/**
 * Stamps who made a record and who changed it last. Two calls, one rule each.
 *
 * The services are what call this, not the repositories, for two reasons. The
 * actor is a property of the request, which is the layer a service sits in and
 * a repository deliberately knows nothing about. And a write is not the same
 * thing as an edit: {@code replaceAll} rewrites a whole list to unlink three
 * rows out of fifteen, and a repository hook firing on that would record twelve
 * edits nobody made. The caller knows which rows it actually changed.
 *
 * The update fields stay absent until something is actually changed. A record
 * that still reads {@code createdByUserId} with no {@code updatedByUserId} has
 * not been touched since it was made, which is a real answer worth being able
 * to read off the file — and it keeps the YAML the size it was.
 */
public final class Audit {

    private Audit() {}

    /**
     * For a record being created. Leaves {@code createdAt} alone if a caller
     * has already set it, so this can be added to an existing create path
     * without moving the timestamp it already writes.
     */
    public static <T extends Audited> T created(T record, String userId) {
        if (record.getCreatedAt() == null) record.setCreatedAt(Instant.now());
        record.setCreatedByUserId(userId);
        return record;
    }

    /**
     * For a record being changed. Call it only where something really changed:
     * stamping on the way past turns the field into "who last looked at this",
     * which answers nothing.
     */
    public static <T extends Audited> T touched(T record, String userId) {
        record.setUpdatedAt(Instant.now());
        record.setUpdatedByUserId(userId);
        return record;
    }

    /**
     * Stamps every record in a batch as created by the same member — the three
     * checklist items a new destination seeds, and the nights a lodging plan
     * spreads itself over.
     */
    public static <T extends Audited> Iterable<T> allCreated(Iterable<T> records, String userId) {
        for (T record : records) created(record, userId);
        return records;
    }
}
