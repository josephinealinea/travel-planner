package com.josephinealinea.planner.budget.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * {@code app.settlement.simplify-debts} (env {@code SIMPLIFY_DEBTS}).
 *
 * False, the default: Settle Expenses lists what each other member and the
 * signed-in member owe each other, pair by pair, with the rows behind it.
 * True: debts are netted across the whole trip so fewer payments are needed —
 * if Sam owes Alex and Ray owes Sam, Ray pays Alex and Sam is left out of it.
 * See {@code BudgetService.simplifiedSettlements}.
 *
 * A record of its own rather than a component of AppProperties, which is
 * constructed positionally in a score of tests.
 */
@ConfigurationProperties(prefix = "app.settlement")
public record SettlementProperties(Boolean simplifyDebts) {

    public SettlementProperties {
        if (simplifyDebts == null) simplifyDebts = false;
    }

    public static SettlementProperties off() {
        return new SettlementProperties(false);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SettlementProperties.class)
    public static class Registration {
    }
}
