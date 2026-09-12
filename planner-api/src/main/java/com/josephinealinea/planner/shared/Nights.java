package com.josephinealinea.planner.shared;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The accommodation-nights calculation behind "Plan 6N accommodation in Cusco".
 * Both dates are optional on a destination, so this returns null whenever the
 * count is not calculable and callers fall back to the un-numbered wording.
 */
public final class Nights {

    private Nights() {}

    /** Cusco 25-Oct -> 31-Oct == 6. Null unless both dates exist and end is after start. */
    public static Long between(LocalDate start, LocalDate end) {
        if (start == null || end == null) return null;
        long nights = ChronoUnit.DAYS.between(start, end);
        return nights > 0 ? nights : null;
    }
}
