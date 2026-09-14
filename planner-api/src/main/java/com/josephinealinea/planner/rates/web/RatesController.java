package com.josephinealinea.planner.rates.web;

import com.josephinealinea.planner.rates.api.RatesService;
import com.josephinealinea.planner.rates.domain.RateTable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Read-only on purpose. Rates are fetched daily for the whole install, so there
 * is nothing for a member to edit and no endpoint to edit it with — the old
 * per-trip PATCH and the dialog that drove it are gone.
 */
@RestController
@RequestMapping("/api/v1/rates")
public class RatesController {

    /** What the budget panel needs to say where its numbers came from. */
    public record RatesResponse(String base,
                                String date,
                                String fetchedAt,
                                Map<String, BigDecimal> rates) {}

    private final RatesService rates;

    public RatesController(RatesService rates) {
        this.rates = rates;
    }

    @GetMapping
    RatesResponse current() {
        RateTable table = rates.current();
        return new RatesResponse(
                table.getBase(),
                table.getDate(),
                table.getFetchedAt() == null ? null : table.getFetchedAt().toString(),
                table.getRates());
    }
}
