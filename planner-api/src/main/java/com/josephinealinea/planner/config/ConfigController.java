package com.josephinealinea.planner.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * What this deployment offers, for the frontend to read rather than keep its
 * own copy of.
 *
 * These static pages have no build step, so there is nowhere to inject
 * environment values at compile time the way a bundler would. Serving the
 * settings that both halves care about — the currency catalogue, the default
 * a new account starts on — keeps application.yml the single place they are
 * configured, instead of a JS constant drifting from the YAML.
 *
 * Deliberately anonymous and free of user data: it is the same answer for
 * everybody, and the sign-in page may need it before anyone has signed in.
 */
@RestController
@RequestMapping("/api/v1/config")
public class ConfigController {

    public record ConfigResponse(List<String> currencies, String defaultDisplayCurrency) {}

    private final AppProperties props;

    public ConfigController(AppProperties props) {
        this.props = props;
    }

    @GetMapping
    ConfigResponse config() {
        return new ConfigResponse(
                props.currencies().available(),
                props.currencies().defaultDisplay());
    }
}
