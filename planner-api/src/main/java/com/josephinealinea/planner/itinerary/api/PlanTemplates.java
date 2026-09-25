package com.josephinealinea.planner.itinerary.api;

import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.i18n.I18nConfig;
import com.josephinealinea.planner.i18n.Messages;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The pre-filled plan a member sees when they press Plan on a checklist item,
 * so the common case is a quick edit rather than a blank form. Every field is
 * a suggestion — nothing here is enforced when the plan is saved.
 */
@Component
public class PlanTemplates {

    private final Messages messages;

    @Autowired
    public PlanTemplates(Messages messages) {
        this.messages = messages;
    }

    /** English, for tests that build the templates by hand. */
    public PlanTemplates() {
        this(I18nConfig.standalone());
    }

    /** What the Plan form opens with. */
    public record Template(String description,
                           LocalDateTime startAt,
                           LocalDateTime endAt,
                           String currency) {}

    public Template forChecklistItem(ChecklistItem item,
                                     Destination destination,
                                     List<Destination> allDestinations,
                                     String currency) {
        if (destination == null) {
            // No destination attached, so there is nothing to suggest beyond
            // the item's own wording.
            return new Template(item.getDescription(), null, null, currency);
        }

        String name = destination.getName();
        LocalDate start = destination.getStartDate();
        LocalDate end = destination.getEndDate();

        return switch (item.getCategory()) {
            case TRANSPORTATION -> {
                String previous = previousDestination(destination, allDestinations)
                        .map(Destination::getName)
                        .orElse(null);
                String description = previous == null
                        ? messages.get("plan.transport", name)
                        : messages.get("plan.flight", previous, name);
                yield new Template(description, at(start, LocalTime.MIDNIGHT), null, currency);
            }
            case LODGING -> {
                //String description = (start != null && end != null)
                //        ? "Hotel in %s — check-in %s, check-out %s"
                //                .formatted(name, DAY_MONTH.format(start), DAY_MONTH.format(end))
                //        : "Hotel in %s".formatted(name);
                String description = messages.get("plan.hotel", name);
                yield new Template(description,
                        at(start, LocalTime.of(15, 0)),
                        at(end, LocalTime.of(11, 0)),
                        currency);
            }
            case ACTIVITIES -> new Template(messages.get("plan.activity", name),
                    at(start, LocalTime.of(9, 0)), null, currency);
            // No sensible default hour for either — you shop and eat whenever —
            // so these pre-fill the place and leave the time to the member,
            // the way OTHERS does.
            case SHOPPING -> new Template(messages.get("plan.shopping", name),
                    at(start, LocalTime.of(9, 0)), null, currency);
            case FOOD -> new Template(messages.get("plan.food", name),
                    at(start, LocalTime.of(18, 0)), null, currency);
            case OTHERS -> new Template(item.getDescription(), at(start, LocalTime.MIDNIGHT), null, currency);
        };
    }

    /**
     * The stop immediately before this one along the route, so a transport plan
     * can suggest where the journey starts.
     */
    private Optional<Destination> previousDestination(Destination current, List<Destination> all) {
        List<Destination> ordered = all.stream()
                .sorted(Comparator
                        .comparing(Destination::getStartDate,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparingInt(Destination::getSortOrder))
                .toList();

        Destination previous = null;
        for (Destination candidate : ordered) {
            if (candidate.getId().equals(current.getId())) return Optional.ofNullable(previous);
            previous = candidate;
        }
        return Optional.empty();
    }

    private static LocalDateTime at(LocalDate date, LocalTime time) {
        return date == null ? null : LocalDateTime.of(date, time);
    }
}
