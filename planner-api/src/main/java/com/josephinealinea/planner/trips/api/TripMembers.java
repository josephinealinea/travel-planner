package com.josephinealinea.planner.trips.api;

import com.josephinealinea.planner.shared.ApiException;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.domain.TripMember;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The trip's own members, as the set anything on it may be shared by, and the
 * one place that decides how a shared cost divides.
 *
 * A static record rather than a service, like {@link TripWindow} beside it: the
 * trip already carries its members, so there is nothing to look up.
 *
 * <b>An empty list means the whole trip.</b> That is the rule everything else
 * here rests on, and it is a safety property rather than a convenience: the
 * Budget tab shows a member only the rows they share, so a row shared by nobody
 * would be money visible to no one — present in the file, absent from every
 * screen. Reading "unspecified" as "all of us" means no row can ever go
 * missing, and it is also the honest reading of an expense nobody has divided
 * up yet.
 */
public record TripMembers(List<String> userIds) {

    public static TripMembers of(Trip trip) {
        List<String> ids = new ArrayList<>();
        for (TripMember member : trip.getMembers()) {
            if (member.getUserId() != null && !ids.contains(member.getUserId())) {
                ids.add(member.getUserId());
            }
        }
        return new TripMembers(ids);
    }

    /**
     * Validates ids against the trip, de-duplicated, order preserved.
     *
     * An id that is not a member is a client error rather than something to
     * drop quietly — the same reasoning as TripCountries.validate: it means the
     * caller is working from a stale member list, and silently saving nothing
     * would look like success.
     *
     * An empty or absent list is returned empty, which every reader takes as
     * the whole trip.
     */
    public List<String> validate(List<String> wanted) {
        if (wanted == null || wanted.isEmpty()) return new ArrayList<>();

        List<String> valid = new ArrayList<>();
        for (String raw : wanted) {
            if (raw == null || raw.isBlank()) continue;
            String id = raw.trim();
            if (!userIds.contains(id)) {
                throw ApiException.badRequest("That person is not a member of this trip.");
            }
            if (!valid.contains(id)) valid.add(id);
        }
        return valid;
    }

    /**
     * Validates a single member id — the payer of an expense, where validate
     * above takes the list of people sharing it.
     *
     * Null or blank is returned as null, meaning nobody. Deciding whether that
     * nobody should clear a stored value or leave it alone is the caller's
     * business, because only the caller knows whether the field was sent. An id
     * that is not a member is rejected for the reason validate gives.
     */
    public String validateOne(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String id = raw.trim();
        if (!userIds.contains(id)) {
            throw ApiException.badRequest("That person is not a member of this trip.");
        }
        return id;
    }

    /**
     * Who actually shares a row: whoever it names, or every member when it
     * names nobody.
     *
     * Resolved per request rather than written back, so a row left unshared
     * follows the member list as people join and leave. That is the same reason
     * nights are never stored — a copy taken now would answer a question about
     * a member list that has since changed.
     */
    public List<String> sharersOf(List<String> sharedByUserIds) {
        if (sharedByUserIds == null || sharedByUserIds.isEmpty()) return userIds;
        // Anyone since removed from the trip stops counting, or their share
        // would be charged to a person the trip no longer has.
        List<String> present = sharedByUserIds.stream().filter(userIds::contains).toList();
        return present.isEmpty() ? userIds : present;
    }

    /**
     * One person's part of an amount, to the cent, such that everyone's parts
     * add back up to exactly the amount.
     *
     * The leftover cents go to the earliest sharers — arbitrary, but stable and
     * never lossy, which matters because these shares are what the budget's
     * totals are built from. Three people splitting 10.00 owe 3.34, 3.33 and
     * 3.33, and the trip still cost ten.
     *
     * Returns null when — and only when — this person does not share the row,
     * which is what the caller filters on. A row they do share with no amount
     * on it yet is zero, not null: null has to mean "not mine" alone, or a row
     * would drop off their budget for having no figure typed in.
     */
    public BigDecimal shareOf(BigDecimal amount, List<String> sharedByUserIds, String userId) {
        List<String> sharers = sharersOf(sharedByUserIds);
        // A trip carrying no member list at all has nobody to divide between,
        // and answering null for every row would blank the asker's budget
        // rather than tell them anything. Every trip TripService creates has
        // its owner in that list, so this is about staying defined for an
        // input rather than about a state the app produces.
        if (sharers.isEmpty()) {
            return amount == null ? BigDecimal.ZERO : amount.setScale(2, RoundingMode.HALF_UP);
        }
        int index = sharers.indexOf(userId);
        if (index < 0) return null;
        if (amount == null) return BigDecimal.ZERO;

        BigDecimal cents = amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2);
        BigDecimal n = BigDecimal.valueOf(sharers.size());
        BigDecimal base = cents.divideToIntegralValue(n);
        BigDecimal leftover = cents.subtract(base.multiply(n));
        BigDecimal extra = BigDecimal.valueOf(index).compareTo(leftover) < 0
                ? BigDecimal.ONE : BigDecimal.ZERO;
        return base.add(extra).movePointLeft(2);
    }
}
