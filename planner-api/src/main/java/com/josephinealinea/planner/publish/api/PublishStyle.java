package com.josephinealinea.planner.publish.api;

import com.josephinealinea.planner.checklist.domain.ChecklistCategory;

import java.util.Map;

/**
 * Icons and colours for the published page, matching the Jekyll site's
 * _data/travels/budget_categories.yml and checklist_status_icons.yml so a
 * published trip looks like it belongs next to the hand-written ones.
 */
public final class PublishStyle {

    private PublishStyle() {}

    private static final Map<ChecklistCategory, String> ICONS = Map.of(
            ChecklistCategory.TRANSPORTATION, "✈️",
            ChecklistCategory.LODGING, "🏨",
            ChecklistCategory.ACTIVITIES, "🎟️",
            ChecklistCategory.SHOPPING, "🛍️",
            ChecklistCategory.FOOD, "🍽️",
            ChecklistCategory.OTHERS, "💰");

    private static final Map<ChecklistCategory, String> COLORS = Map.of(
            ChecklistCategory.TRANSPORTATION, "#F76707",
            ChecklistCategory.LODGING, "#4C6EF5",
            ChecklistCategory.ACTIVITIES, "#E64980",
            ChecklistCategory.SHOPPING, "#AE3EC9",
            ChecklistCategory.FOOD, "#2F9E44",
            ChecklistCategory.OTHERS, "#868E96");

    public static final String TODO_ICON = "▫️";
    public static final String DONE_ICON = "✔️";

    public static String icon(ChecklistCategory category) {
        return ICONS.getOrDefault(category, ICONS.get(ChecklistCategory.OTHERS));
    }

    public static String color(ChecklistCategory category) {
        return COLORS.getOrDefault(category, COLORS.get(ChecklistCategory.OTHERS));
    }
}
