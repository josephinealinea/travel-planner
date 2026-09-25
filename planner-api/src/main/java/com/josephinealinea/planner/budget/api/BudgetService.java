package com.josephinealinea.planner.budget.api;

import com.josephinealinea.planner.budget.domain.BudgetItem;
import com.josephinealinea.planner.budget.domain.SettlementPayment;
import com.josephinealinea.planner.budget.infra.BudgetRepository;
import com.josephinealinea.planner.budget.infra.SettlementPaymentRepository;
import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.destinations.api.TripCountries;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.identity.domain.User;
import com.josephinealinea.planner.identity.infra.UserRepository;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.rates.api.RatesService;
import com.josephinealinea.planner.rates.domain.RateTable;
import com.josephinealinea.planner.shared.ApiException;
import com.josephinealinea.planner.shared.Audit;
import com.josephinealinea.planner.shared.Ids;
import com.josephinealinea.planner.trips.api.TripAccessService;
import com.josephinealinea.planner.trips.api.TripMembers;
import com.josephinealinea.planner.trips.domain.Trip;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class BudgetService {

    public record Input(String description,
                        ChecklistCategory category,
                        BigDecimal amount,
                        String currency,
                        LocalDate date,
                        List<String> countryCodes,
                        /**
                         * "Shared by" — trip-member user ids. Empty means the
                         * whole trip; null on a patch means "leave it". See
                         * TripMembers.
                         */
                        List<String> sharedByUserIds,
                        /**
                         * "Paid by" — one trip-member user id. On a patch, null
                         * means "leave it" and an empty string clears it; on a
                         * create both mean nobody. Never counted in any figure.
                         */
                        String paidByUserId,
                        /**
                         * "Expense already charged". Null on a patch means
                         * "leave the status alone"; null on a create means
                         * charged, which is what adding an expense by hand
                         * nearly always records.
                         */
                        Boolean charged,
                        String note) {

        /** The shape from before note existed: says nothing about it. */
        public Input(String description, ChecklistCategory category, BigDecimal amount,
                     String currency, LocalDate date, List<String> countryCodes,
                     List<String> sharedByUserIds, String paidByUserId, Boolean charged) {
            this(description, category, amount, currency, date, countryCodes,
                    sharedByUserIds, paidByUserId, charged, null);
        }
    }

    /**
     * One rollup over one set of rows. Currencies with no configured rate are
     * reported separately rather than silently dropped or counted at 1:1, so a
     * missing rate is visible instead of quietly wrong.
     *
     * Every field here is derived from the same rows, so they always agree:
     * the slices of either breakdown add up to `total`, and `nativeTotals`
     * says what those same rows cost in the currencies they were actually
     * spent in. That is the reason this is a record rather than six more
     * fields on Summary — a total from one set of rows next to a breakdown
     * from another is the one way this panel can lie.
     */
    public record Breakdown(Map<String, BigDecimal> byCategory,
                            List<CountryAmount> byCountry,
                            List<NativeAmount> nativeTotals,
                            BigDecimal total,
                            List<String> currenciesMissingRates) {}

    /**
     * The rollup, twice over.
     *
     * `charged` counts only what has actually been paid — the trip's headline
     * total and the pie it draws. `forecast` counts the pending rows too, so
     * "what will this trip have cost" is a question the same panel can answer
     * without either number pretending to be the other. Both are always
     * computed; which one a reader sees is the Group by selector's business.
     *
     * displayCurrency is the trip's own anchor — what its exchange-rate table
     * is quoted against (see TripService.rebase) — and is what "record a
     * cost" forms use as their sensible default. totalsCurrency is the
     * currency both breakdowns are actually expressed in: the signed-in user's
     * own display-currency preference, falling back to the anchor when they
     * have none. The two are equal for most users most of the time, but are
     * never the same field.
     *
     * `items` is every row on the trip, regardless of status and regardless of
     * who shares it. Two different filters would otherwise fight over one
     * field: a pending expense is never hidden, and the other tabs need every
     * row — the Plan form reads the status of the row its own cost created,
     * whoever ended up sharing it. What narrows the Budget tab to one member is
     * `shares`, not this list.
     *
     * `shares` is the signed-in member's part of each row they share, keyed by
     * row id — and the two breakdowns are built from exactly those parts, so
     * the totals are that member's money rather than the trip's. A row absent
     * from the map is a row they do not share; the map is empty when there is
     * no signed-in member at all (a published page), and then the breakdowns
     * count every row in full.
     */
    public record Summary(List<BudgetItem> items,
                          String displayCurrency,
                          String totalsCurrency,
                          Map<String, BigDecimal> shares,
                          Breakdown charged,
                          Breakdown forecast,
                          List<Settlement> settlements,
                          /**
                           * True when `settlements` were simplified across the
                           * whole trip rather than listed pair by pair, so the
                           * page can say what it is showing. Tells the reader
                           * how to interpret the list; carries no money.
                           */
                          boolean settlementsSimplified,
                          /**
                           * The signed-in member's own balance per currency,
                           * with the rows behind it. Only filled while
                           * simplifying — the pairwise list already says which
                           * rows explain each line, and a simplified payment
                           * has no rows of its own, so this is what the
                           * details view shows instead. A currency is listed
                           * whenever a row involves the member, including when
                           * it nets to zero.
                           */
                          List<Balance> balances,
                          /**
                           * Every settlement payment recorded on the trip, for
                           * the signed-in member's Settle panel to list. Never
                           * counted in any figure above — see
                           * {@link SettlementPayment}. Empty with no signed-in
                           * member, so a published page has none.
                           */
                          List<SettlementPayment> payments) {}

    /**
     * One member's standing in one currency across the whole trip: what they
     * are owed (positive) or owe (negative), and the rows that add up to it.
     *
     * A `Line` carries the id of the expense row or of the payment that made
     * it (exactly one is set) and its effect on the balance, and nothing else —
     * the frontend already holds every row in `items` and every payment in
     * `payments`. An expense that nets to nothing for the member (they paid
     * for something only they share) is left out, since it moves nothing and
     * would only be noise.
     */
    public record Balance(String currency, BigDecimal net, List<Line> lines) {

        public record Line(String itemId, String paymentId, BigDecimal amount) {}
    }

    /**
     * What one other member and the signed-in member owe each other, in one
     * currency.
     *
     * Two members who have paid for each other's things have a debt in each
     * direction, and both are worth showing: the gross pair says what happened
     * and `net` says what anybody actually hands over. Reading it from the
     * other member's side mirrors it exactly, which is what stops two people
     * disagreeing about the same money.
     *
     * <b>Per currency, never converted.</b> A debt is repaid in the currency it
     * was run up in, so a settle figure that had been through a rate table
     * would be a number nobody can hand over — and it would drift with the
     * rates besides. Two currencies with one member are two of these.
     *
     * `net` is what is still owed once payments are counted: positive when the
     * other member owes the signed-in one, negative when it is the other way.
     * `owesYou` and `youOwe` stay gross on purpose, so a settled pair still
     * says what happened and the payments beside it say why it is zero.
     *
     * A `Line` carries the row's id and nothing else about it: the frontend
     * already holds every row in `items` and looks up the description, date and
     * category there, so no field is kept in two places to fall out of step.
     */
    public record Settlement(String otherUserId,
                             String currency,
                             BigDecimal owesYou,
                             BigDecimal youOwe,
                             /** What the other member has paid the signed-in member so far. */
                             BigDecimal paidYou,
                             /** What the signed-in member has paid the other member so far. */
                             BigDecimal youPaid,
                             BigDecimal net,
                             List<Line> lines) {

        public record Line(String itemId, BigDecimal amount, boolean owedToYou) {}
    }

    /**
     * One row and the amount of it that counts for whoever is looking.
     *
     * The pair exists because those two come apart the moment an expense is
     * shared: the row still says a 900 EUR flight cost 900 EUR, while the
     * figure going into one member's total is their part of it. Every sum below
     * reads the amount from here rather than from the item, which is what stops
     * a share being converted, sliced by country and then quietly totalled at
     * full price.
     */
    private record Charge(BudgetItem item, BigDecimal amount) {}

    /**
     * How much was actually spent in one currency, before any conversion —
     * "Native: 320.00 EUR + 450.00 USD" alongside the converted Total. Every
     * item counts here regardless of whether its rate resolves: converting is
     * what a missing rate blocks, not knowing what was actually spent.
     */
    public record NativeAmount(String currency, BigDecimal amount) {}

    /**
     * One slice of the country breakdown, in totalsCurrency. flag is null for
     * the one synthetic bucket, "No location": a row with no linked
     * destination. A row spanning several countries is split between them
     * rather than bucketed apart — see countrySharesFor.
     */
    public record CountryAmount(String key, String flag, BigDecimal amount) {}

    private static final CountryAmount NO_LOCATION = new CountryAmount("NO_LOCATION", null, null);

    private final BudgetRepository budget;
    private final ItineraryRepository itinerary;
    private final DestinationRepository destinations;
    private final UserRepository users;
    private final TripAccessService access;
    private final TripCountries countries;
    private final RatesService rates;
    private final boolean simplifyDebts;
    /** Null means none: the constructors that predate payments leave it unset. */
    private final SettlementPaymentRepository settlementPayments;

    /** Simplification off — the behaviour from before the flag existed. */
    public BudgetService(BudgetRepository budget,
                         ItineraryRepository itinerary,
                         DestinationRepository destinations,
                         UserRepository users,
                         TripAccessService access,
                         TripCountries countries,
                         RatesService rates) {
        this(budget, itinerary, destinations, users, access, countries, rates,
                SettlementProperties.off());
    }

    /** Simplification as configured, and no payments. */
    public BudgetService(BudgetRepository budget,
                         ItineraryRepository itinerary,
                         DestinationRepository destinations,
                         UserRepository users,
                         TripAccessService access,
                         TripCountries countries,
                         RatesService rates,
                         SettlementProperties settlement) {
        this(budget, itinerary, destinations, users, access, countries, rates, settlement, null);
    }

    @Autowired
    public BudgetService(BudgetRepository budget,
                         ItineraryRepository itinerary,
                         DestinationRepository destinations,
                         UserRepository users,
                         TripAccessService access,
                         TripCountries countries,
                         RatesService rates,
                         SettlementProperties settlement,
                         SettlementPaymentRepository settlementPayments) {
        this.simplifyDebts = settlement.simplifyDebts();
        this.settlementPayments = settlementPayments;
        this.budget = budget;
        this.itinerary = itinerary;
        this.destinations = destinations;
        this.users = users;
        this.access = access;
        this.countries = countries;
        this.rates = rates;
    }

    public Summary summarise(String tripId, String userId) {
        Trip trip = access.requireMember(tripId, userId);
        User user = users.findById(userId).orElse(null);
        return summarise(trip, user);
    }

    /**
     * Also used by the publish renderer, which already holds the trip and has
     * no signed-in user to prefer — totals show in the trip's own anchor
     * currency, exactly as before this existed.
     */
    public Summary summarise(Trip trip) {
        return summarise(trip, (User) null);
    }

    /**
     * user may be null (a published page) or may simply have no display
     * currency set yet (an account older than this field) — both fall back to
     * the trip's own anchor currency, never to 1:1 guessing.
     */
    public Summary summarise(Trip trip, User user) {
        // Two different things that used to share one variable, because the
        // old per-trip rate table happened to be quoted against the trip's own
        // display currency. The global table has its own base, so they come
        // apart:
        //
        //   tripCurrency — what the trip works in. Still the default target,
        //                  and still what an item with no currency of its own
        //                  is taken to be in.
        //   pivot        — the rate table's base, the currency every rate is
        //                  quoted against and the one a cross-rate goes
        //                  through. Nothing user-facing.
        RateTable table = rates.current();
        String tripCurrency = trip.getDisplayCurrency();
        String pivot = table.getBase() == null ? tripCurrency : table.getBase();
        String target = (user != null && user.getDisplayCurrency() != null && !user.getDisplayCurrency().isBlank())
                ? user.getDisplayCurrency().toUpperCase()
                : tripCurrency;

        List<BudgetItem> items = budget.findAllOrdered(trip.getSlug());
        // One destination per country, purely to borrow its country name and
        // flag for the breakdown's labels. Keyed by code because that is what
        // the rows now carry.
        Map<String, Destination> countrySamples = new LinkedHashMap<>();
        destinations.findAllOrdered(trip.getSlug()).forEach(destination -> {
            String code = destination.getCountryCode();
            if (code != null && !code.isBlank()) {
                countrySamples.putIfAbsent(code.trim().toUpperCase(), destination);
            }
        });

        // Whose money this rollup is about, and how much of each row is
        // theirs. With no signed-in member — a published page — there is
        // nobody to divide for, so every row counts whole.
        TripMembers members = TripMembers.of(trip);
        Map<String, BigDecimal> shares = new LinkedHashMap<>();
        List<Charge> charges = new ArrayList<>();
        for (BudgetItem item : items) {
            if (user == null) {
                charges.add(new Charge(item,
                        item.getAmount() == null ? BigDecimal.ZERO : item.getAmount()));
                continue;
            }
            BigDecimal share = members.shareOf(
                    item.getAmount(), item.getSharedByUserIds(), user.getId());
            // Null is "not shared by them", and is the whole filter.
            if (share == null) continue;
            shares.put(item.getId(), share);
            charges.add(new Charge(item, share));
        }

        // Twice over the same charges: once over the ones already paid, once
        // over all of them. Two passes rather than one pass filling two sets of
        // buckets — the arithmetic is a few dozen BigDecimal operations, and a
        // single pass would have to thread "which buckets does this row
        // belong in" through the country split and the native sums as well.
        // Payments settle debts and are otherwise invisible: nothing above this
        // line reads them, which is what keeps a repayment out of every total.
        // With no signed-in member (a published page) there is nobody to
        // settle for, so none are loaded at all.
        List<SettlementPayment> allPayments = user == null || settlementPayments == null
                ? List.of()
                : settlementPayments.findAll(trip.getSlug());
        List<SettlementPayment> counted = countedPayments(allPayments, members);
        Simplified simplified = simplifyDebts && user != null
                ? simplify(items, counted, members, user.getId(), tripCurrency)
                : null;
        return new Summary(items, tripCurrency, target, shares,
                breakdown(charges.stream().filter(c -> c.item().isConfirmed()).toList(),
                        target, tripCurrency, pivot, table, countrySamples),
                breakdown(charges, target, tripCurrency, pivot, table, countrySamples),
                simplified != null ? simplified.settlements()
                        : settlements(items, counted, members, user, tripCurrency),
                simplifyDebts,
                simplified != null ? simplified.balances() : List.of(),
                allPayments);
    }

    /**
     * The payments that take part in the maths: both people still on the trip.
     * The trip no longer knows anybody who has left, so it does not settle with
     * them — the same rule that drops a departed payer's expense — and the
     * record itself is kept, so re-adding them restores it.
     */
    private static List<SettlementPayment> countedPayments(List<SettlementPayment> all, TripMembers members) {
        return all.stream()
                .filter(p -> p.getAmount() != null && p.getAmount().signum() > 0)
                .filter(p -> p.getFromUserId() != null && p.getToUserId() != null
                        && !p.getFromUserId().equals(p.getToUserId()))
                .filter(p -> members.userIds().contains(p.getFromUserId())
                        && members.userIds().contains(p.getToUserId()))
                .toList();
    }

    /** A viewer's payments and balances under simplification, worked out together. */
    private record Simplified(List<Settlement> settlements, List<Balance> balances) {}

    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(2);

    /** One payment in the plan: `from` hands `to` this much, in one currency. */
    private record Transfer(String from, String to, String currency, BigDecimal amount) {}

    /**
     * Settle Expenses with simplify-debts on: net every member's position across
     * the whole trip, then pay it off with as few transfers as a greedy match
     * finds (Splitwise's approach — the true minimum is NP-hard, and this is
     * never worse than one payment fewer than there are people).
     *
     * <b>The plan is the trip's, not the viewer's.</b> Every member computes it
     * independently from the same rows, so nothing in the matching may depend on
     * who is asking, or two members would be told different things about the
     * same money. The viewer only filters the finished plan down to the
     * payments they are part of. Ties are broken by user id for the same reason.
     *
     * The rules that made the pairwise version right are kept: charged rows
     * only, per currency and never converted, a payer who has left the trip is
     * not settled with, and the division is {@link TripMembers#shareOf}, so the
     * figures reconcile with the Budget tab to the cent. Because shareOf hands
     * out the odd cent, each currency's balances sum to exactly zero.
     */
    private static Simplified simplify(List<BudgetItem> items, List<SettlementPayment> payments,
                                       TripMembers members, String me, String tripCurrency) {
        // currency -> user -> what they are owed (positive) or owe (negative)
        Map<String, Map<String, BigDecimal>> standing = new LinkedHashMap<>();
        // currency -> the viewer's own rows behind their balance
        Map<String, List<Balance.Line>> mine = new LinkedHashMap<>();

        for (BudgetItem item : items) {
            if (!item.isConfirmed() || item.getAmount() == null) continue;
            String payer = effectivePayerOf(item);
            if (payer == null || !members.userIds().contains(payer)) continue;

            String currency = nativeCurrencyOf(item, tripCurrency);
            Map<String, BigDecimal> ledger = standing.computeIfAbsent(currency, c -> new LinkedHashMap<>());
            BigDecimal amount = item.getAmount().setScale(2, RoundingMode.HALF_UP);
            ledger.merge(payer, amount, BigDecimal::add);

            BigDecimal myEffect = payer.equals(me) ? amount : BigDecimal.ZERO;
            for (String sharer : members.sharersOf(item.getSharedByUserIds())) {
                BigDecimal share = members.shareOf(item.getAmount(), item.getSharedByUserIds(), sharer);
                if (share == null) continue;
                ledger.merge(sharer, share.negate(), BigDecimal::add);
                if (sharer.equals(me)) myEffect = myEffect.subtract(share);
            }
            if (myEffect.signum() != 0) {
                mine.computeIfAbsent(currency, c -> new ArrayList<>())
                        .add(new Balance.Line(item.getId(), null, myEffect));
            }
        }

        // A payment is a row "paid by from, shared by to" and nothing more: the
        // payer is credited and the receiver debited, so it lands in the same
        // ledger before anything is matched and the remaining plan shrinks.
        for (SettlementPayment payment : payments) {
            String currency = paymentCurrencyOf(payment, tripCurrency);
            Map<String, BigDecimal> ledger = standing.computeIfAbsent(currency, c -> new LinkedHashMap<>());
            BigDecimal amount = payment.getAmount().setScale(2, RoundingMode.HALF_UP);
            ledger.merge(payment.getFromUserId(), amount, BigDecimal::add);
            ledger.merge(payment.getToUserId(), amount.negate(), BigDecimal::add);

            BigDecimal myEffect = payment.getFromUserId().equals(me) ? amount
                    : payment.getToUserId().equals(me) ? amount.negate() : BigDecimal.ZERO;
            if (myEffect.signum() != 0) {
                mine.computeIfAbsent(currency, c -> new ArrayList<>())
                        .add(new Balance.Line(null, payment.getId(), myEffect));
            }
        }

        List<Transfer> plan = new ArrayList<>();
        List<Balance> balances = new ArrayList<>();
        for (String currency : new TreeSet<>(standing.keySet())) {
            Map<String, BigDecimal> ledger = standing.get(currency);
            plan.addAll(match(currency, ledger));
            // Listed whenever a row involves the member, even if it nets to
            // zero: "you are square" is an answer worth being able to see.
            List<Balance.Line> lines = mine.getOrDefault(currency, List.of());
            if (!lines.isEmpty()) {
                balances.add(new Balance(currency,
                        ledger.getOrDefault(me, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP),
                        lines));
            }
        }

        List<Settlement> settlements = new ArrayList<>();
        for (Transfer payment : plan) {
            BigDecimal amount = payment.amount().setScale(2, RoundingMode.HALF_UP);
            if (payment.to().equals(me)) {
                settlements.add(new Settlement(payment.from(), payment.currency(),
                        amount, ZERO_MONEY, ZERO_MONEY, ZERO_MONEY, amount, List.of()));
            } else if (payment.from().equals(me)) {
                settlements.add(new Settlement(payment.to(), payment.currency(),
                        ZERO_MONEY, amount, ZERO_MONEY, ZERO_MONEY, amount.negate(), List.of()));
            }
        }
        settlements.sort(Comparator.comparing(Settlement::otherUserId)
                .thenComparing(Settlement::currency));
        return new Simplified(settlements, balances);
    }

    /**
     * The greedy match for one currency: the biggest creditor is paid by the
     * biggest debtor, as much as either can cover, until nobody is owed. Each
     * step clears at least one person, so n people need at most n−1 payments.
     */
    private static List<Transfer> match(String currency, Map<String, BigDecimal> ledger) {
        Map<String, BigDecimal> open = new LinkedHashMap<>();
        ledger.forEach((user, amount) -> {
            if (amount.signum() != 0) open.put(user, amount);
        });

        Comparator<Map.Entry<String, BigDecimal>> largestFirst = Map.Entry
                .<String, BigDecimal>comparingByValue(Comparator.comparing(BigDecimal::abs))
                .reversed()
                .thenComparing(Map.Entry.comparingByKey());

        List<Transfer> payments = new ArrayList<>();
        while (true) {
            var creditor = open.entrySet().stream()
                    .filter(e -> e.getValue().signum() > 0).min(largestFirst).orElse(null);
            var debtor = open.entrySet().stream()
                    .filter(e -> e.getValue().signum() < 0).min(largestFirst).orElse(null);
            if (creditor == null || debtor == null) break;

            BigDecimal pay = creditor.getValue().min(debtor.getValue().negate());
            payments.add(new Transfer(debtor.getKey(), creditor.getKey(), currency, pay));
            String creditorId = creditor.getKey();
            String debtorId = debtor.getKey();
            BigDecimal creditorLeft = creditor.getValue().subtract(pay);
            BigDecimal debtorLeft = debtor.getValue().add(pay);
            if (creditorLeft.signum() == 0) open.remove(creditorId); else open.put(creditorId, creditorLeft);
            if (debtorLeft.signum() == 0) open.remove(debtorId); else open.put(debtorId, debtorLeft);
        }
        return payments;
    }

    /**
     * Who owes whom, read off the same rows the breakdowns are built from.
     *
     * <b>Charged rows only.</b> A pending row is an intention rather than a
     * payment: nobody has handed anything over, so there is nothing to give
     * back. Deliberately not wired to the panel's Group by selector, which
     * would otherwise produce debts for expenses that have not happened.
     *
     * The division is {@link TripMembers#shareOf}, the one place that owns it,
     * so these figures reconcile with the Budget tab's to the cent. Dividing
     * again anywhere else — in particular in the browser, where it would be a
     * plain amount/n — would drift on any row that does not divide evenly, and
     * the drift would show up as two members disagreeing by a penny.
     *
     * Empty when there is no signed-in member, exactly as `shares` is: a
     * published page has nobody to settle for, and a member's name beside a
     * figure is what a public file must never carry.
     */
    private static List<Settlement> settlements(List<BudgetItem> items,
                                                List<SettlementPayment> payments,
                                                TripMembers members,
                                                User user,
                                                String tripCurrency) {
        if (user == null) return List.of();
        String me = user.getId();

        // Keyed by the other member and the currency, which is what one line of
        // the table is. LinkedHashMap so the order is stable between reloads.
        Map<String, Pair> pairs = new LinkedHashMap<>();

        for (BudgetItem item : items) {
            if (!item.isConfirmed()) continue;

            String payer = effectivePayerOf(item);
            // A payer who has left cannot be settled with — the trip no longer
            // knows them, the same reason their share drops out of a split.
            if (payer == null || !members.userIds().contains(payer)) continue;

            List<String> sharers = members.sharersOf(item.getSharedByUserIds());
            String currency = nativeCurrencyOf(item, tripCurrency);

            if (payer.equals(me)) {
                // They owe me their share of what I paid for. My own share is
                // skipped: I am not in debt to myself for my half of dinner.
                for (String sharer : sharers) {
                    if (sharer.equals(me)) continue;
                    BigDecimal share = members.shareOf(item.getAmount(), item.getSharedByUserIds(), sharer);
                    if (share == null || share.signum() == 0) continue;
                    pair(pairs, sharer, currency).owedToYou(item.getId(), share);
                }
            } else if (sharers.contains(me)) {
                // Somebody else paid for something I share, so I owe them my
                // part of it — and only my part.
                BigDecimal share = members.shareOf(item.getAmount(), item.getSharedByUserIds(), me);
                if (share == null || share.signum() == 0) continue;
                pair(pairs, payer, currency).owedByYou(item.getId(), share);
            }
            // Anything else is two other people's business.
        }

        // Payments settle what the rows above say is owed, and add nothing to
        // it: they are kept apart from owesYou/youOwe so a pair that has been
        // paid off still shows what happened and why it now reads zero.
        for (SettlementPayment payment : payments) {
            String currency = paymentCurrencyOf(payment, tripCurrency);
            if (payment.getFromUserId().equals(me)) {
                pair(pairs, payment.getToUserId(), currency).youPaid(payment.getAmount());
            } else if (payment.getToUserId().equals(me)) {
                pair(pairs, payment.getFromUserId(), currency).paidYou(payment.getAmount());
            }
        }

        return pairs.values().stream()
                .map(Pair::toSettlement)
                .sorted(Comparator.comparing(Settlement::otherUserId)
                        .thenComparing(Settlement::currency))
                .toList();
    }

    private static Pair pair(Map<String, Pair> pairs, String other, String currency) {
        return pairs.computeIfAbsent(other + ' ' + currency, key -> new Pair(other, currency));
    }

    private static String paymentCurrencyOf(SettlementPayment payment, String tripCurrency) {
        String currency = payment.getCurrency();
        return (currency == null || currency.isBlank()) ? tripCurrency : currency.toUpperCase();
    }

    /** One line of the pairwise table while it is being added up. */
    private static final class Pair {
        private final String other;
        private final String currency;
        private BigDecimal owesYou = BigDecimal.ZERO;
        private BigDecimal youOwe = BigDecimal.ZERO;
        private BigDecimal paidYou = BigDecimal.ZERO;
        private BigDecimal youPaid = BigDecimal.ZERO;
        private final List<Settlement.Line> lines = new ArrayList<>();

        Pair(String other, String currency) {
            this.other = other;
            this.currency = currency;
        }

        void owedToYou(String itemId, BigDecimal amount) {
            owesYou = owesYou.add(amount);
            lines.add(new Settlement.Line(itemId, amount, true));
        }

        void owedByYou(String itemId, BigDecimal amount) {
            youOwe = youOwe.add(amount);
            lines.add(new Settlement.Line(itemId, amount, false));
        }

        void paidYou(BigDecimal amount) { paidYou = paidYou.add(amount); }

        void youPaid(BigDecimal amount) { youPaid = youPaid.add(amount); }

        Settlement toSettlement() {
            return new Settlement(other, currency,
                    owesYou.setScale(2, RoundingMode.HALF_UP),
                    youOwe.setScale(2, RoundingMode.HALF_UP),
                    paidYou.setScale(2, RoundingMode.HALF_UP),
                    youPaid.setScale(2, RoundingMode.HALF_UP),
                    owesYou.subtract(youOwe).subtract(paidYou).add(youPaid)
                            .setScale(2, RoundingMode.HALF_UP),
                    lines);
        }
    }

    /**
     * The rollup over one set of charges, in `target`.
     *
     * byCategory always names every category, including the ones at zero: it
     * keys the legend, and a category that drops out of the map entirely
     * cannot be told apart by a reader from one that was never spent on.
     */
    private static Breakdown breakdown(List<Charge> charges,
                                       String target,
                                       String tripCurrency,
                                       String pivot,
                                       RateTable table,
                                       Map<String, Destination> countrySamples) {
        Map<String, BigDecimal> byCategory = new LinkedHashMap<>();
        for (ChecklistCategory category : ChecklistCategory.values()) {
            byCategory.put(category.name(), BigDecimal.ZERO);
        }

        Map<String, BigDecimal> countryTotals = new LinkedHashMap<>();
        Map<String, CountryAmount> countryMeta = new LinkedHashMap<>();
        Map<String, BigDecimal> nativeSums = new LinkedHashMap<>();

        BigDecimal total = BigDecimal.ZERO;
        var missing = new TreeSet<String>();

        for (Charge charge : charges) {
            BudgetItem item = charge.item();
            // Unconditional, ahead of the conversion attempt below: what was
            // actually spent does not depend on whether a rate exists to
            // convert it, so a missing rate must not also hide this.
            String nativeCurrency = nativeCurrencyOf(item, tripCurrency);
            nativeSums.merge(nativeCurrency, charge.amount(), BigDecimal::add);

            BigDecimal converted = convert(item, charge.amount(),
                    target, tripCurrency, pivot, table.getRates(), missing);
            if (converted == null) continue;

            byCategory.merge(item.getCategory().name(), converted, BigDecimal::add);
            total = total.add(converted);

            countrySharesFor(item, converted, countrySamples).forEach((country, share) -> {
                countryTotals.merge(country.key(), share, BigDecimal::add);
                countryMeta.putIfAbsent(country.key(), country);
            });
        }

        List<CountryAmount> byCountry = countryTotals.entrySet().stream()
                .map(entry -> {
                    CountryAmount meta = countryMeta.get(entry.getKey());
                    return new CountryAmount(meta.key(), meta.flag(),
                            entry.getValue().setScale(2, RoundingMode.HALF_UP));
                })
                .sorted(Comparator.comparing(CountryAmount::amount).reversed())
                .toList();

        Map<String, BigDecimal> rateMap = table.getRates();
        List<NativeAmount> nativeTotals = nativeSums.entrySet().stream()
                .map(entry -> new NativeAmount(entry.getKey(), entry.getValue().setScale(2, RoundingMode.HALF_UP)))
                .sorted(Comparator
                        // Biggest contribution to the total first. A currency
                        // with no rate sorts after every convertible one
                        // rather than vanishing from the ordering entirely.
                        .comparing((NativeAmount n) -> {
                            BigDecimal converted = convertAmount(
                                    n.amount(), n.currency(), target, pivot, rateMap, new HashSet<>());
                            return converted == null ? BigDecimal.valueOf(-1) : converted;
                        })
                        .reversed()
                        .thenComparing(NativeAmount::currency))
                .toList();

        return new Breakdown(byCategory, byCountry, nativeTotals,
                total.setScale(2, RoundingMode.HALF_UP), new ArrayList<>(missing));
    }

    /** The currency an item's amount is actually in — convert()'s own fallback, pulled out so the native-totals pass can agree with it exactly. */
    private static String nativeCurrencyOf(BudgetItem item, String tripCurrency) {
        String currency = item.getCurrency();
        return (currency == null || currency.isBlank()) ? tripCurrency : currency.toUpperCase();
    }

    /**
     * How one row's money divides between countries.
     *
     * A row with no linked destination — or none whose destination has ever
     * resolved a country — goes whole to "No location" rather than being
     * silently dropped. Otherwise the amount is split evenly across the linked
     * destinations and then gathered by country, so a row linked to two places
     * in France and one in Belgium leaves two thirds in France. Splitting per
     * destination rather than per country is what makes that weighting follow
     * where the trip actually spends its time.
     *
     * The split is exact to the cent: dividing evenly almost always leaves a
     * remainder, and handing it out a cent at a time keeps the country slices
     * summing to precisely the same total as the category ones. A breakdown
     * whose slices did not add up to the total would be worse than no
     * breakdown at all.
     */
    private static Map<CountryAmount, BigDecimal> countrySharesFor(
            BudgetItem item, BigDecimal amount, Map<String, Destination> countrySamples) {

        // The row carries its countries directly now, so there is no resolving
        // a city to a country first — the name and flag are looked up only for
        // display, from whichever destination in that country has them.
        List<CountryAmount> perDestination = new ArrayList<>();
        for (String code : item.getCountryCodes() == null ? List.<String>of() : item.getCountryCodes()) {
            if (code == null || code.isBlank()) continue;
            String key = code.trim().toUpperCase();
            Destination sample = countrySamples.get(key);
            perDestination.add(new CountryAmount(
                    key,
                    sample == null ? null : sample.getCountryFlag(),
                    null));
        }

        Map<CountryAmount, BigDecimal> shares = new LinkedHashMap<>();
        if (perDestination.isEmpty()) {
            shares.put(NO_LOCATION, amount);
            return shares;
        }

        for (int i = 0; i < perDestination.size(); i++) {
            CountryAmount country = perDestination.get(i);
            BigDecimal share = splitShare(amount, perDestination.size(), i);
            // Two destinations in the same country merge into one slice, which
            // is what gives that country the larger share.
            CountryAmount existing = shares.keySet().stream()
                    .filter(c -> c.key().equals(country.key()))
                    .findFirst()
                    .orElse(country);
            shares.merge(existing, share, BigDecimal::add);
        }
        return shares;
    }

    /**
     * The i-th of n even shares of an amount, to the cent, such that all n
     * shares add back up to exactly the amount. The leftover cents go to the
     * earliest shares — arbitrary, but stable and never lossy.
     */
    private static BigDecimal splitShare(BigDecimal amount, int n, int index) {
        BigDecimal cents = amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2);
        BigDecimal base = cents.divideToIntegralValue(BigDecimal.valueOf(n));
        BigDecimal leftover = cents.subtract(base.multiply(BigDecimal.valueOf(n)));
        BigDecimal extra = BigDecimal.valueOf(index).compareTo(leftover) < 0 ? BigDecimal.ONE : BigDecimal.ZERO;
        return base.add(extra).movePointLeft(2);
    }

    /**
     * Rates are hand-maintained per trip, keyed by currency code, expressed as
     * units of that currency per one unit of the trip's own anchor currency —
     * the same convention as the exchange_rates block in the Jekyll site's
     * budget YAML. Converting into some other target currency (the user's own
     * display-currency preference) pivots through that anchor: first into the
     * anchor, then from the anchor into the target, so a single rate table
     * keeps working no matter what a viewer prefers to see totals in.
     *
     * Either leg is skipped when it is unnecessary: an item already in the
     * target currency needs no rate at all, and one already in the anchor
     * currency needs only the second leg. A rate missing for either leg is
     * reported under that currency's own code and the item is excluded —
     * never folded in at 1:1.
     */
    static BigDecimal convert(BudgetItem item, BigDecimal amount,
                              String targetCurrency, String tripCurrency,
                              String pivotCurrency, Map<String, BigDecimal> rates,
                              Set<String> missing) {
        if (amount == null) return BigDecimal.ZERO;
        // The amount is passed in rather than read off the item: when an
        // expense is shared it is one member's part of it that is being
        // converted, not the whole charge. The item is still needed for the
        // currency that part is in.
        //
        // An item with no currency of its own is money spent in the currency
        // the trip works in — never in the rate table's base, which is an
        // implementation detail of how rates are quoted.
        String from = nativeCurrencyOf(item, tripCurrency);
        return convertAmount(amount, from, targetCurrency, pivotCurrency, rates, missing);
    }

    /**
     * The numeric core of a currency conversion, taking a raw amount and
     * currency rather than a BudgetItem — shared by convert() above and by
     * native-totals sorting below, which wants the same pivot-through-the-
     * anchor logic for a per-currency sum rather than a per-item amount.
     *
     * missing is a required, not optional, parameter: which leg of the
     * pivot failed can only be told apart from inside this method (the
     * anchor leg and the target leg fail against different rates), so a
     * caller that does not care — the sort below — passes a throwaway set
     * rather than this method silently dropping which currency was at fault.
     */
    /**
     * Converts through the rate table's base: divide out of `from`, multiply
     * into `targetCurrency`. Every rate reads as units per one unit of the
     * base, and the base itself is stored as 1, so no currency is a special
     * case — including the base.
     *
     * A currency with no rate returns null and is recorded in `missing`. It is
     * never counted at 1:1, which would silently make a 500 PEN dinner look
     * like 500 EUR.
     */
    private static BigDecimal convertAmount(BigDecimal amount, String from, String targetCurrency,
                                            String pivotCurrency, Map<String, BigDecimal> rates,
                                            Set<String> missing) {
        if (from.equalsIgnoreCase(targetCurrency)) {
            return amount.setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal inAnchor;
        if (from.equalsIgnoreCase(pivotCurrency)) {
            inAnchor = amount;
        } else {
            BigDecimal rate = rates.get(from);
            if (rate == null || rate.signum() == 0) {
                missing.add(from);
                return null;
            }
            inAnchor = amount.divide(rate, MathContext.DECIMAL64);
        }

        if (targetCurrency.equalsIgnoreCase(pivotCurrency)) {
            return inAnchor.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal targetRate = rates.get(targetCurrency.toUpperCase());
        if (targetRate == null || targetRate.signum() == 0) {
            missing.add(targetCurrency.toUpperCase());
            return null;
        }
        return inAnchor.multiply(targetRate).setScale(2, RoundingMode.HALF_UP);
    }

    /** A manual expense — no plan behind it, so BudgetSync leaves it alone. */
    public BudgetItem create(String tripId, String userId, Input input) {
        Trip trip = access.requireMember(tripId, userId);
        requireDescription(input.description());
        requireAmount(input.amount());

        BudgetItem item = new BudgetItem();
        item.setId(Ids.newId());
        item.setTripId(tripId);
        item.setCategory(input.category() == null ? ChecklistCategory.OTHERS : input.category());
        item.setDescription(input.description().trim());
        item.setNote(blankToNull(input.note()));
        item.setAmount(input.amount());
        item.setCurrency(input.currency() == null || input.currency().isBlank()
                ? trip.getDisplayCurrency()
                : input.currency().trim().toUpperCase());
        item.setDate(input.date());
        item.setCountryCodes(countries.validate(trip, input.countryCodes()));
        item.setSharedByUserIds(TripMembers.of(trip).validate(input.sharedByUserIds()));
        item.setPaidByUserId(TripMembers.of(trip).validateOne(input.paidByUserId()));
        // Charged unless said otherwise: an expense typed into the budget by
        // hand is nearly always one that has already been paid, which is why
        // the form's own box starts ticked. A plan's cost is the other way
        // round — see BudgetSync.
        item.markCharged(input.charged() == null || input.charged(), Instant.now());
        requirePayerWhenCharged(item);
        Audit.created(item, userId);

        return budget.save(trip.getSlug(), item);
    }

    /** Any member can change the amount, currency, category and description. */
    public BudgetItem update(String tripId, String userId, String itemId, Input input) {
        Trip trip = access.requireMember(tripId, userId);
        BudgetItem item = budget.findById(trip.getSlug(), itemId)
                .orElseThrow(() -> ApiException.notFound("error.budgetItem.notFound"));

        if (input.description() != null) {
            requireDescription(input.description());
            item.setDescription(input.description().trim());
        }
        if (input.note() != null) item.setNote(blankToNull(input.note()));
        if (input.category() != null) item.setCategory(input.category());
        if (input.amount() != null) {
            requireAmount(input.amount());
            item.setAmount(input.amount());
        }
        if (input.currency() != null && !input.currency().isBlank()) {
            item.setCurrency(input.currency().trim().toUpperCase());
        }
        if (input.date() != null) {
            // Only a date actually sent is checked, so an older row that
            // predates a change to the trip's own dates stays editable.
            item.setDate(input.date());
        }
        if (input.countryCodes() != null) {
            // An empty list is how the client clears every link.
            item.setCountryCodes(countries.validate(trip, input.countryCodes()));
        }
        if (input.sharedByUserIds() != null) {
            // An empty list clears the names, which reads as the whole trip.
            item.setSharedByUserIds(TripMembers.of(trip).validate(input.sharedByUserIds()));
        }
        if (input.paidByUserId() != null) {
            // Null is a form that said nothing. An empty string is a member
            // clearing the payer, which validateOne turns into null.
            item.setPaidByUserId(TripMembers.of(trip).validateOne(input.paidByUserId()));
        }
        if (input.charged() != null) {
            // Ticking the box on save is what confirms the charge and stamps
            // the time; markCharged keeps an existing stamp rather than moving
            // it to now on every later edit.
            item.markCharged(input.charged(), Instant.now());
        }
        // After every field is set, so that ticking the box and clearing the
        // payer in the same patch are judged on what the row ends up saying.
        requirePayerWhenCharged(item);
        Audit.touched(item, userId);

        BudgetItem saved = budget.save(trip.getSlug(), item);

        // If this row came from a plan, push the amount back so the plan's cost
        // and the budget do not disagree.
        if (saved.isFromPlan() && input.amount() != null) {
            itinerary.findById(trip.getSlug(), saved.getItineraryItemId()).ifPresent(plan -> {
                plan.setCost(saved.getAmount());
                plan.setCurrency(saved.getCurrency());
                Audit.touched(plan, userId);
                itinerary.save(trip.getSlug(), plan);
            });
        }
        return saved;
    }

    /** Deleting a budget row clears the originating plan's back-link. */
    public void delete(String tripId, String userId, String itemId) {
        Trip trip = access.requireMember(tripId, userId);
        BudgetItem item = budget.findById(trip.getSlug(), itemId)
                .orElseThrow(() -> ApiException.notFound("error.budgetItem.notFound"));

        budget.delete(trip.getSlug(), itemId);

        if (item.isFromPlan()) {
            List<ItineraryItem> plans = new ArrayList<>(itinerary.findAll(trip.getSlug()));
            boolean touched = false;
            for (ItineraryItem plan : plans) {
                if (itemId.equals(plan.getBudgetItemId())) {
                    plan.setBudgetItemId(null);
                    plan.setCost(null);
                    plan.setCurrency(null);
                    Audit.touched(plan, userId);
                    touched = true;
                }
            }
            if (touched) itinerary.replaceAll(trip.getSlug(), plans);
        }
    }

    /** Validates every id against this trip's destinations, de-duplicated, order preserved. */

    private static void requireDescription(String description) {
        if (description == null || description.isBlank()) {
            throw ApiException.badRequest("error.expense.descriptionRequired");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void requireAmount(BigDecimal amount) {
        if (amount == null) throw ApiException.badRequest("error.expense.amountRequired");
        if (amount.signum() < 0) throw ApiException.badRequest("error.amount.negative");
    }

    /**
     * A charged expense has to say who paid it.
     *
     * Money that has demonstrably left someone's hand with no record of whose
     * can appear in no settlement, so it is money the Settle Expenses panel
     * cannot account for — and it would go missing silently, in the one
     * direction a reader has no way to detect. A pending row is the opposite
     * case and stays optional: nobody has paid it, so naming a payer would be
     * a guess dressed up as a record.
     *
     * <b>This asks the item, not the input,</b> which is what makes one call
     * cover every door into the state: creating a charged row with no payer,
     * ticking "already charged" on a row that has none, and clearing the payer
     * of a row already charged all arrive here having mutated the item.
     * Checking the request instead would need three checks and would still
     * miss the next door somebody adds.
     */
    public static void requirePayerWhenCharged(BudgetItem item) {
        if (item.isConfirmed() && isBlank(item.getPaidByUserId())) {
            throw ApiException.badRequest("error.expense.payerRequired");
        }
    }

    /**
     * Who a charged row is treated as having been paid by.
     *
     * The rule above is enforced on write and never on read: these YAML files
     * are hand-editable and installs exist that predate the field, and a file
     * that refused to load would be far worse than one row reading oddly. So a
     * charged row with no stored payer still has to settle as something, and
     * it settles as whoever recorded it — the same reasoning that attributes
     * every other record the app writes for you to the member whose action
     * produced it.
     *
     * Deliberately a static method here rather than a getter on BudgetItem: a
     * derived getter is serialised into the YAML by Jackson and then fails to
     * read back (CLAUDE.md, Traps). Nothing is written back either — this
     * answers a question, it does not repair the file.
     */
    public static String effectivePayerOf(BudgetItem item) {
        String payer = item.getPaidByUserId();
        return isBlank(payer) ? item.getCreatedByUserId() : payer;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
