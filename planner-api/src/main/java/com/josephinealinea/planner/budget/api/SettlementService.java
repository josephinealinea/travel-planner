package com.josephinealinea.planner.budget.api;

import com.josephinealinea.planner.budget.domain.SettlementPayment;
import com.josephinealinea.planner.budget.infra.SettlementPaymentRepository;
import com.josephinealinea.planner.shared.ApiException;
import com.josephinealinea.planner.shared.Audit;
import com.josephinealinea.planner.shared.Ids;
import com.josephinealinea.planner.trips.api.TripAccessService;
import com.josephinealinea.planner.trips.api.TripMembers;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.identity.api.UserService;
import com.josephinealinea.planner.notification.EmailSender;
import com.josephinealinea.planner.notification.MailTemplates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Recording, and taking back, a payment between two trip members.
 *
 * <b>Who may.</b> Either party — the one who paid or the one who was paid — or
 * the trip owner, who can put right a record two friends have got wrong. Any
 * other member is refused with 403, and a non-member sees the trip as not
 * there, like everywhere else. The author is stored either way, which is the
 * audit trail: an owner recording on somebody's behalf leaves their own id.
 *
 * <b>No edit.</b> A payment is a fact that happened; a wrong one is deleted and
 * recorded again, so the record never has to say "this was once different".
 * There is no receiver confirmation either — these are friends splitting a
 * trip, and delete is the undo.
 *
 * What a payment does to the figures lives in {@link BudgetService}, which is
 * the only reader of these records: it is money moving between two people who
 * already spent it, so it changes no total.
 */
@Service
public class SettlementService {

    public record Input(String fromUserId,
                        String toUserId,
                        BigDecimal amount,
                        String currency,
                        LocalDate date,
                        String note) {}

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final SettlementPaymentRepository payments;
    private final TripAccessService access;
    private final UserService users;
    private final EmailSender email;
    private final MailTemplates templates;

    /** No mail: what a hand-built service in a test means. */
    public SettlementService(SettlementPaymentRepository payments, TripAccessService access) {
        this(payments, access, null, null, null);
    }

    @Autowired
    public SettlementService(SettlementPaymentRepository payments, TripAccessService access,
                             UserService users, EmailSender email, MailTemplates templates) {
        this.payments = payments;
        this.access = access;
        this.users = users;
        this.email = email;
        this.templates = templates;
    }

    public SettlementPayment record(String tripId, String userId, Input input) {
        Trip trip = access.requireMember(tripId, userId);
        TripMembers members = TripMembers.of(trip);

        String from = input.fromUserId();
        String to = input.toUserId();
        if (isBlank(from) || isBlank(to)) {
            throw ApiException.badRequest("error.settlement.parties");
        }
        if (from.equals(to)) {
            throw ApiException.badRequest("error.settlement.self");
        }
        if (!members.userIds().contains(from) || !members.userIds().contains(to)) {
            throw ApiException.badRequest("error.settlement.notBuddies");
        }
        if (input.amount() == null
                || input.amount().setScale(2, RoundingMode.HALF_UP).signum() <= 0) {
            throw ApiException.badRequest("error.settlement.amount");
        }
        requireInvolved(trip, userId, from, to);

        SettlementPayment payment = new SettlementPayment();
        payment.setId(Ids.newId());
        payment.setTripId(tripId);
        payment.setFromUserId(from);
        payment.setToUserId(to);
        payment.setAmount(input.amount().setScale(2, RoundingMode.HALF_UP));
        payment.setCurrency(isBlank(input.currency())
                ? trip.getDisplayCurrency()
                : input.currency().trim().toUpperCase());
        payment.setDate(input.date() == null ? LocalDate.now() : input.date());
        payment.setNote(isBlank(input.note()) ? null : input.note().trim());
        Audit.created(payment, userId);

        SettlementPayment saved = payments.save(trip.getSlug(), payment);
        tellTheOtherParty(trip, userId, saved);
        return saved;
    }

    /**
     * The people in the payment who did not record it. Two friends settling up
     * hear about it from whoever entered it; an owner correcting the record
     * for both of them tells both. A failure here must never undo a payment
     * that has been saved, so it is logged and left.
     */
    private void tellTheOtherParty(Trip trip, String actorId, SettlementPayment payment) {
        if (email == null) return;
        try {
            var accounts = users.byId(List.of(actorId, payment.getFromUserId(), payment.getToUserId()));
            var recordedBy = accounts.get(actorId).displayName();
            var payer = accounts.get(payment.getFromUserId());
            var receiver = accounts.get(payment.getToUserId());
            for (var party : List.of(payer, receiver)) {
                if (party.getId().equals(actorId)) continue;
                email.send(templates.paymentRecorded(party.getLanguageCode(), party.getEmail(), trip.getTitle(),
                        recordedBy, payer.displayName(), receiver.displayName(),
                        payment.getAmount().toPlainString(), payment.getCurrency(), payment.getDate().toString()));
            }
        } catch (RuntimeException e) {
            log.error("Could not tell the other party about payment {}", payment.getId(), e);
        }
    }

    public void delete(String tripId, String userId, String paymentId) {
        Trip trip = access.requireMember(tripId, userId);
        SettlementPayment payment = payments.findById(trip.getSlug(), paymentId)
                .orElseThrow(() -> ApiException.notFound("error.payment.notFound"));
        requireInvolved(trip, userId, payment.getFromUserId(), payment.getToUserId());
        payments.delete(trip.getSlug(), paymentId);
    }

    private static void requireInvolved(Trip trip, String userId, String from, String to) {
        if (!trip.isOwner(userId) && !userId.equals(from) && !userId.equals(to)) {
            throw ApiException.forbidden(
                    "error.settlement.notInvolved");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
