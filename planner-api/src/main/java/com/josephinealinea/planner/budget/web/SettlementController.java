package com.josephinealinea.planner.budget.web;

import com.josephinealinea.planner.budget.api.SettlementService;
import com.josephinealinea.planner.budget.domain.SettlementPayment;
import com.josephinealinea.planner.config.CurrentUserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Payments between trip members that settle a debt. There is no read endpoint:
 * the payments travel with the trip's budget view, like the settlements they
 * change, and there is no edit — delete and record again.
 */
@RestController
@RequestMapping("/api/v1/trips/{tripId}/settlements/payments")
public class SettlementController {

    public record RecordRequest(
            @NotBlank(message = "Say who paid") String fromUserId,
            @NotBlank(message = "Say who was paid") String toUserId,
            @NotNull(message = "Enter an amount") BigDecimal amount,
            /** Absent means the trip's own currency. */
            String currency,
            /** Absent means today. */
            LocalDate date,
            String note) {

        SettlementService.Input toInput() {
            return new SettlementService.Input(fromUserId, toUserId, amount, currency, date, note);
        }
    }

    private final SettlementService settlement;
    private final CurrentUserContext currentUser;

    public SettlementController(SettlementService settlement, CurrentUserContext currentUser) {
        this.settlement = settlement;
        this.currentUser = currentUser;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    SettlementPayment record(@PathVariable String tripId, @Valid @RequestBody RecordRequest request) {
        return settlement.record(tripId, currentUser.userId(), request.toInput());
    }

    @DeleteMapping("/{paymentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String tripId, @PathVariable String paymentId) {
        settlement.delete(tripId, currentUser.userId(), paymentId);
    }
}
