package com.josephinealinea.planner.importer;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What an import did, printed at the end of the run whatever the outcome.
 *
 * The one part that asks something of the reader is the list of published
 * trips: a published page is a rendered file, not a row, so none is carried
 * over. Each of those trips has to be published once more, which renders it
 * into the page store the deployment uses.
 */
final class ImportReport {

    enum Outcome {
        /** Written, verified and committed. */
        COMMITTED("COMMITTED — the data is in PostgreSQL"),
        /** Written and verified, then rolled back on purpose. */
        DRY_RUN("DRY RUN — imported and verified, then rolled back; nothing was written"),
        /** Nothing was written: the database already held data, or the settings were wrong. */
        REFUSED("REFUSED — nothing was written"),
        /** Something failed part-way or a verification did not pass; everything was rolled back. */
        FAILED("FAILED — everything was rolled back; nothing was written");

        final String label;

        Outcome(String label) {
            this.label = label;
        }
    }

    /** A published trip whose page has to be rendered again. */
    record PublishedTrip(String slug, String title, String publishedAt) {}

    final Path dir;
    final boolean dryRun;
    Outcome outcome = Outcome.FAILED;
    String failure;
    /** The exception behind a FAILED run, when there was one — printed with its stack trace. */
    Throwable error;

    /** Rows written, per entity, in the order they were written. */
    final Map<String, Integer> counts = new LinkedHashMap<>();
    /** Rows present in YAML that are deliberately not imported. */
    final Map<String, Integer> skipped = new LinkedHashMap<>();
    final List<String> warnings = new ArrayList<>();
    final List<String> notes = new ArrayList<>();
    final List<PublishedTrip> published = new ArrayList<>();
    final List<String> pendingRequests = new ArrayList<>();

    ImportVerifier verifier;

    ImportReport(Path dir, boolean dryRun) {
        this.dir = dir;
        this.dryRun = dryRun;
    }

    void count(String entity, int rows) {
        counts.merge(entity, rows, Integer::sum);
    }

    void print(PrintStream out) {
        out.println();
        out.println("==== YAML -> PostgreSQL import ====");
        out.println("Source:  " + dir);
        out.println("Mode:    " + (dryRun ? "dry run (rolls back)" : "import (commits)"));

        if (!counts.isEmpty()) {
            out.println();
            out.println("Imported:");
            counts.forEach((entity, rows) -> out.printf("  %-18s %6d%n", entity, rows));
        }
        if (!skipped.isEmpty()) {
            out.println("Not imported, on purpose:");
            skipped.forEach((entity, rows) -> out.printf("  %-18s %6d%n", entity, rows));
        }

        if (verifier != null) {
            out.println();
            out.println("Verification:");
            section(out, "records, field by field", verifier.entities(), "rows");
            section(out, "budget summaries & settlements", verifier.money(), "summaries");
        }

        if (!notes.isEmpty()) {
            out.println();
            out.println("Notes:");
            notes.forEach(note -> out.println("  - " + note));
        }
        if (!warnings.isEmpty()) {
            out.println();
            out.println("Warnings:");
            warnings.forEach(warning -> out.println("  ! " + warning));
        }

        out.println();
        if (published.isEmpty()) {
            out.println("Published trips: none.");
        } else {
            out.println("Published trips — their pages are NOT imported. Publish each one again:");
            published.forEach(trip -> out.println("  * " + trip.title() + "  (" + trip.slug()
                    + ", last published " + (trip.publishedAt() == null ? "unknown" : trip.publishedAt()) + ")"));
        }
        if (!pendingRequests.isEmpty()) {
            out.println("Undecided publish requests — their staged pages are not imported either;"
                    + " approving one renders the page afresh:");
            pendingRequests.forEach(line -> out.println("  * " + line));
        }

        out.println();
        out.println("Result: " + outcome.label);
        if (failure != null) out.println("Reason: " + failure);
        out.println("===================================");
        out.flush();
    }

    private static void section(PrintStream out, String name, ImportVerifier.Result result, String unit) {
        out.printf("  %-32s %s (%d %s compared)%n", name,
                result.passed() ? "PASSED" : "FAILED — " + result.differences.size() + " difference(s)",
                result.compared, unit);
        int shown = Math.min(result.differences.size(), ImportVerifier.MAX_REPORTED);
        for (int i = 0; i < shown; i++) out.println("      " + result.differences.get(i));
        if (result.differences.size() > shown) {
            out.println("      … and " + (result.differences.size() - shown) + " more");
        }
    }
}
