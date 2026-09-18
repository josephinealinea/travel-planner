package com.josephinealinea.planner.budget.api;

import com.josephinealinea.planner.budget.domain.BudgetItem;
import com.josephinealinea.planner.budget.infra.BudgetRepository;
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
import com.josephinealinea.planner.trips.api.TripWindow;
import com.josephinealinea.planner.trips.domain.Trip;
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
                        Boolean charged) {}

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
                          Breakdown forecast) {}

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
    public record CountryAmount(String key, String name, String flag, BigDecimal amount) {}

    private static final CountryAmount NO_LOCATION = new CountryAmount("NO_LOCATION", "No location", null, null);

    private final BudgetRepository budget;
    private final ItineraryRepository itinerary;
    private final DestinationRepository destinations;
    private final UserRepository users;
    private final TripAccessService access;
    private final TripCountries countries;
    private final RatesService rates;

    public BudgetService(BudgetRepository budget,
                         ItineraryRepository itinerary,
                         DestinationRepository destinations,
                         UserRepository users,
                         TripAccessService access,
                         TripCountries countries,
                         RatesService rates) {
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
        return new Summary(items, tripCurrency, target, shares,
                breakdown(charges.stream().filter(c -> c.item().isConfirmed()).toList(),
                        target, tripCurrency, pivot, table, countrySamples),
                breakdown(charges, target, tripCurrency, pivot, table, countrySamples));
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
                    return new CountryAmount(meta.key(), meta.name(), meta.flag(),
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
                    sample != null && sample.getCountryName() != null ? sample.getCountryName() : key,
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
        TripWindow.of(trip).require(input.date(), "expense date");

        BudgetItem item = new BudgetItem();
        item.setId(Ids.newId());
        item.setTripId(tripId);
        item.setCategory(input.category() == null ? ChecklistCategory.OTHERS : input.category());
        item.setDescription(input.description().trim());
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
        Audit.created(item, userId);

        return budget.save(trip.getSlug(), item);
    }

    /** Any member can change the amount, currency, category and description. */
    public BudgetItem update(String tripId, String userId, String itemId, Input input) {
        Trip trip = access.requireMember(tripId, userId);
        BudgetItem item = budget.findById(trip.getSlug(), itemId)
                .orElseThrow(() -> ApiException.notFound("Budget item"));

        if (input.description() != null) {
            requireDescription(input.description());
            item.setDescription(input.description().trim());
        }
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
            TripWindow.of(trip).require(input.date(), "expense date");
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
                .orElseThrow(() -> ApiException.notFound("Budget item"));

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
            throw ApiException.badRequest("An expense needs a description.");
        }
    }

    private static void requireAmount(BigDecimal amount) {
        if (amount == null) throw ApiException.badRequest("An expense needs an amount.");
        if (amount.signum() < 0) throw ApiException.badRequest("An amount cannot be negative.");
    }
}
