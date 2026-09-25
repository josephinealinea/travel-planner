package com.josephinealinea.planner.weather;

import java.time.LocalDate;
import java.util.Map;

/**
 * One day's weather for one place, as the itinerary shows it.
 *
 * {@code source} is not decoration. A trip is usually planned months out, well
 * past the fortnight Open-Meteo will forecast, so most of what this app can say
 * about a trip's weather is climatology rather than prediction — and a reader
 * deciding what to pack needs to know which one they are looking at. See
 * {@link Source}.
 *
 * Everything except the date and the place can be absent: a destination with no
 * coordinates cannot be looked up at all, and the climate endpoint serves no
 * condition code.
 */
public record DayWeather(
        LocalDate date,
        String destinationId,
        String destinationName,
        String countryCode,
        String countryFlag,
        Integer weatherCode,
        Double temperatureMax,
        Double temperatureMin,
        Double precipitation,
        Source source,
        Map<String, Object> details) {

    public enum Source {
        /**
         * A real forecast, from the fortnight or so Open-Meteo predicts. The
         * weather this place is actually expected to have that day.
         */
        FORECAST,
        /**
         * A downscaled climate projection (CMIP6), which is what is available
         * for a date beyond the forecast window. Read it as "what this place is
         * typically like then", never as a prediction for that date: the model
         * produces a plausible late-October in Cusco, not next late-October.
         */
        CLIMATE,
        /** Looked up and nothing came back, or there was nothing to look up. */
        UNAVAILABLE
    }

    public static DayWeather unavailable(LocalDate date, String destinationId, String name,
                                         String countryCode, String countryFlag) {
        return new DayWeather(date, destinationId, name, countryCode, countryFlag,
                null, null, null, null, Source.UNAVAILABLE, Map.of());
    }
}
