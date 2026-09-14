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

    TRANSPORTATION("transport", "Transportation"),
    LODGING("lodging", "Lodging"),
    ACTIVITIES("activities", "Activities"),
    SHOPPING("shopping", "Shopping"),
    FOOD("food", "Food"),
    // Also the fallback for any category a published page does not recognise,
    // which is why it stays last.
    OTHERS("other", "Others");

    private final String dataKey;
    private final String label;

    ChecklistCategory(String dataKey, String label) {
        this.dataKey = dataKey;
        this.label = label;
    }

    public String dataKey() { return dataKey; }
    public String label() { return label; }
}
