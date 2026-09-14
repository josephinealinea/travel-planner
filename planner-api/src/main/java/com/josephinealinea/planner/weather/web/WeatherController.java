package com.josephinealinea.planner.weather.web;

import com.josephinealinea.planner.config.CurrentUserContext;
import com.josephinealinea.planner.weather.DayWeather;
import com.josephinealinea.planner.weather.api.WeatherService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Deliberately its own request rather than another field on GET /trips/{id}.
 *
 * That bundle is fetched on load and again after every mutation, and it is
 * expected to be fast and to always succeed. Weather is an outbound call to
 * somebody else's server: putting it in the bundle would make saving a
 * checklist item wait on Open-Meteo, and an Open-Meteo outage would take the
 * whole trip page down with it. Here it decorates one tab and nothing else
 * notices when it is slow or missing.
 */
@RestController
@RequestMapping("/api/v1/trips/{tripId}/weather")
public class WeatherController {

    public record WeatherResponse(List<DayWeather> days) {}

    private final WeatherService weather;
    private final CurrentUserContext currentUser;

    public WeatherController(WeatherService weather, CurrentUserContext currentUser) {
        this.weather = weather;
        this.currentUser = currentUser;
    }

    @GetMapping
    WeatherResponse forTrip(@PathVariable String tripId) {
        return new WeatherResponse(weather.forTrip(tripId, currentUser.userId()));
    }
}
