package com.josephinealinea.planner.checklist.domain;

/**
 * The enumerated category on every checklist, itinerary and budget row.
 *
 * The lowercase dataKey matches the category names already used by the Jekyll
 * site's _data/travels/budget_categories.yml, so published pages reuse its
 * icons and colours — which is why "shopping" and "food" needed no palette
 * decisions here: that file already defines 🛍️ #AE3EC9 and 🍽️ #2F9E44.
 *
 * Declaration order is the order every picker, filter and legend shows, so
 * adding one in the middle moves it everywhere at once. The constant names are
 * what reaches the YAML, so they are not free to rename.
 */
public enum ChecklistCategory {

    TRANSPORTATION("transport"),
    LODGING("lodging"),
    ACTIVITIES("activities"),
    SHOPPING("shopping"),
    FOOD("food"),
    // Also the fallback for any category a published page does not recognise,
    // which is why it stays last.
    OTHERS("other");

    private final String dataKey;

    ChecklistCategory(String dataKey) {
        this.dataKey = dataKey;
    }

    public String dataKey() { return dataKey; }

    /** Where the name lives: {@code category.<dataKey>} in the message files, one per language. */
    public String messageKey() { return "category." + dataKey; }
}
