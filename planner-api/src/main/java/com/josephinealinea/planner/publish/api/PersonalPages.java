package com.josephinealinea.planner.publish.api;

import com.josephinealinea.planner.identity.domain.User;
import com.josephinealinea.planner.shared.Slugs;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.domain.TripMember;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Which members get a page of their own, and what it is called.
 *
 * This exists so the two sides of that question cannot drift: PublishService
 * writes the files, and TripViewAssembler hands a member the link to theirs.
 * Two copies of "slugify the display name, then de-duplicate" would agree right
 * up until the day somebody's screen name collided, and then one of them would
 * be linking to a page that is not there.
 *
 * The directory name comes from the display name, so it follows a screen-name
 * change — a published page is a file, and renaming yourself publishes to a new
 * one next time. The link a member has already shared stops working at that
 * point, which is the same bargain every slug in this app makes.
 */
public final class PersonalPages {

    private PersonalPages() {}

    /**
     * Member user id to directory name, in trip-member order, for the members
     * who have asked for a personal page.
     *
     * A member with the box unticked is simply absent — no entry, and no file
     * written anywhere. That is what "off" has to mean for something that ends
     * up on a public URL.
     */
    public static Map<String, String> slugsFor(Trip trip, Map<String, User> accounts) {
        Map<String, String> slugs = new LinkedHashMap<>();
        for (String memberId : memberIds(trip)) {
            User member = accounts.get(memberId);
            if (member == null || !member.isPublishPersonalBudget()) continue;
            // Two members called "sam" would otherwise write to one directory,
            // and the second would silently overwrite the first's page.
            slugs.put(memberId, Slugs.unique(member.displayName(), slugs::containsValue));
        }
        return slugs;
    }

    public static List<String> memberIds(Trip trip) {
        return trip.getMembers().stream()
                .map(TripMember::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }
}
