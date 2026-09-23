import { toast } from '../../toast.js';
import { category, money, shortDate, CATEGORIES } from '../../format.js';
import { toggleId, selectedPresent, runBulkDelete } from '../../selection.js';
import { toggleLocation, locationNames, countriesOfTrip } from '../../location-picker.js';
import { toggleSharer, shareWithEveryone, sharedWithEveryone, choosePayer, chargedToggled, sharersOfTrip } from '../../member-picker.js';
import { savedBudgetPageSize } from '../../page-size.js';

/**
 * Colours for the country pie/bars, cycled by rank. Countries are not a fixed
 * set like categories, so — unlike CATEGORIES — there is no per-country
 * colour to look up; this is deliberately a different palette from the
 * category one so the two breakdowns never look interchangeable.
 */
const COUNTRY_PALETTE = [
  '#4C6EF5', '#FD7E14', '#12B886', '#E64980', '#BE4BDB', '#FAB005', '#15AABF', '#FA5252',
];

/**
 * The Budget tab.
 *
 * Rows created by a plan's cost stay editable here like any other.
 *
 * The rollup arrives twice — budget.charged and budget.forecast, the same
 * figures over the charges alone and over the charges plus everything still to
 * be paid. The Show pills choose between them and budgetView resolves it, so
 * every number on the panel comes from one of the two and never from both.
 *
 * Either rollup converts into the signed-in member's own display-currency
 * preference (budget.totalsCurrency), pivoting through the trip's own
 * displayCurrency where the two differ; anything in a currency with no rate
 * for that pivot is listed as excluded rather than folded in at some guessed
 * value. budget.displayCurrency is a different thing entirely — the trip's own
 * anchor currency — and stays what "record a cost" forms default to.
 */
export function budgetTab() {
  // The Chart instance is held here rather than on the component: Alpine deep
  // proxies its own state, and handing a live class instance to that proxy
  // breaks the library's internals.
  let pie = null;

  const blankExpense = () => ({
    id: null,
    description: '',
    note: '',
    category: 'OTHERS',
    amount: '',
    currency: '',
    date: '',
    countryCodes: [],
    // Nobody named still means the whole trip wherever a row says so — that is
    // the storage rule, and it is what stops a stored row belonging to nobody.
    // Empty here only because the factory has no context; openAddExpense
    // selects the member at the keyboard, so a new expense starts as theirs
    // and both narrowing and widening are deliberate.
    sharedByUserIds: [],
    // Who put the money down: one member or nobody. openAddExpense fills in
    // the member at the keyboard; blank here so the factory needs no context.
    paidByUserId: '',
    // "Expense already charged", ticked by default: an expense typed in here
    // by hand is nearly always one that has already been paid. A plan's cost
    // is the other way round — see BudgetSync on the API.
    charged: true,
  });

  return {
    expenseOpen: false,
    expenseForm: blankExpense(),
    expenseError: '',
    expenseBusy: false,

    // filtering
    budgetCategoryFilters: [],

    // paging — the size is read once, when the trip page loads
    budgetPage: 1,
    budgetPageSize: savedBudgetPageSize(),

    /**
     * Which rollup the panel shows, as "<dimension>" or
     * "<dimension>-forecast".
     *
     * One control choosing two things, because they are one question: what am
     * I looking at. The dimension picks category or country; the -forecast
     * suffix picks which rows are counted — the charges alone, or those plus
     * everything still to be paid. Every number on the panel follows it
     * together, so a forecast total is never shown above charged-only slices.
     *
     * Not `budgetPieMode` any more: it selects the total, the native totals and
     * the legend as much as the pie, and a name claiming otherwise is how the
     * four come apart again.
     */
    budgetShow: 'category',

    /**
     * The Show pills, in order. A list rather than a ternary chain in the
     * markup the way the itinerary's three-way Show does it — four options make
     * that unreadable, and a label belongs next to the mode it names.
     */
    budgetShowOptions: [
      { value: 'category',          label: 'Category' },
      { value: 'country',           label: 'Country' },
      { value: 'category-forecast', label: 'Category (Forecast)' },
      { value: 'country-forecast',  label: 'Country (Forecast)' },
    ],

    // bulk selection — like the checklist and itinerary, removing is a
    // select-then-delete job rather than a button on every row
    budgetSelectedIds: [],
    budgetBulkOpen: false,
    budgetBulkBusy: false,


    // ── filtering ───────────────────────────────────
    toggleBudgetCategory(value) {
      const index = this.budgetCategoryFilters.indexOf(value);
      if (index >= 0) this.budgetCategoryFilters.splice(index, 1);
      else this.budgetCategoryFilters.push(value);
      // A new filter starts from its first row.
      this.budgetPage = 1;
    },

    /**
     * The rows the table lists: the signed-in member's own, then whatever the
     * category filter leaves.
     *
     * `budget.items` is every row on the trip whoever is asking — the other
     * tabs need it whole — and `budget.shares` is what says which are mine. A
     * row absent from that map is somebody else's expense, so it is not my
     * budget to look at; an entry of zero is mine and simply costs nothing.
     * Hence the null test rather than a truthy one.
     *
     * The summary above the table is already this member's money, computed the
     * same way on the API, so the two cannot drift apart: both come from
     * exactly the rows in this map.
     */
    get myBudgetItems() {
      const shares = this.budget.shares || {};
      return (this.budget.items || []).filter((item) => shares[item.id] != null);
    },

    get filteredBudgetItems() {
      const items = this.myBudgetItems;
      if (!this.budgetCategoryFilters.length) return items;
      return items.filter((item) => this.budgetCategoryFilters.includes(item.category));
    },

    /**
     * Paging is presentation only: the chart, totals and bulk selection all
     * read filteredBudgetItems, so every figure still covers every page.
     */
    get budgetPageCount() {
      return Math.max(1, Math.ceil(this.filteredBudgetItems.length / this.budgetPageSize));
    },

    /**
     * budgetPage clamped to what exists, so deleting the last rows of the last
     * page, or narrowing a filter, never leaves the table past its own end.
     */
    get budgetCurrentPage() {
      return Math.min(Math.max(1, this.budgetPage), this.budgetPageCount);
    },

    get pagedBudgetItems() {
      const start = (this.budgetCurrentPage - 1) * this.budgetPageSize;
      return this.filteredBudgetItems.slice(start, start + this.budgetPageSize);
    },

    get budgetPageFrom() {
      return this.filteredBudgetItems.length
        ? (this.budgetCurrentPage - 1) * this.budgetPageSize + 1 : 0;
    },

    get budgetPageTo() {
      return Math.min(this.budgetCurrentPage * this.budgetPageSize, this.filteredBudgetItems.length);
    },

    goToBudgetPage(page) {
      this.budgetPage = Math.min(Math.max(1, page), this.budgetPageCount);
    },

    // ── rollup ──────────────────────────────────────

    /** True while a forecast breakdown is selected. */
    get budgetForecast() {
      return this.budgetShow.endsWith('-forecast');
    },

    /**
     * The one rollup everything on this panel reads — total, slices, native
     * totals and missing rates alike. Resolving it in a single place is what
     * keeps them consistent: they are all derived from the same rows, and the
     * only way the panel can lie is by mixing a figure from one set with a
     * breakdown from another.
     *
     * Falls back to the charged rollup if a forecast one is somehow absent,
     * rather than rendering a panel of blanks.
     */
    get budgetView() {
      const charged = this.budget.charged || {};
      if (!this.budgetForecast) return charged;
      return this.budget.forecast || charged;
    },

    get budgetCategories() {
      const totals = this.budgetView.byCategory || {};
      return CATEGORIES
        .map((cat) => ({ key: cat.value, icon: cat.icon, label: cat.label, color: cat.color,
                         amount: Number(totals[cat.value] || 0) }))
        .filter((cat) => cat.amount > 0);
    },

    /** byCountry already comes sorted largest-first and excludes zero slices. */
    get budgetCountries() {
      return (this.budgetView.byCountry || [])
        .filter((c) => Number(c.amount) > 0)
        .map((c, i) => ({
          key: c.key,
          icon: c.flag || '🌍',
          label: c.name,
          color: COUNTRY_PALETTE[i % COUNTRY_PALETTE.length],
          amount: Number(c.amount),
        }));
    },

    /** What the pie and the bars beside it currently show, per budgetShow. */
    get budgetBreakdown() {
      return this.budgetShow.startsWith('country') ? this.budgetCountries : this.budgetCategories;
    },

    get budgetLargest() {
      return this.budgetBreakdown.reduce((max, slice) => Math.max(max, slice.amount), 0);
    },

    barWidth(amount) {
      return this.budgetLargest > 0 ? (amount / this.budgetLargest) * 100 : 0;
    },

    /**
     * A slice's share of the whole breakdown, as a percentage string like the
     * reference budget panel shows beside each row ("36.2%"). Computed here
     * rather than served by the API: it is entirely derived from amount and
     * the breakdown's own total, the same rule this project already applies
     * to nights and other display-only numbers.
     */
    barPercent(amount) {
      const total = this.budgetBreakdown.reduce((sum, slice) => sum + slice.amount, 0);
      return total > 0 ? ((amount / total) * 100).toFixed(1) + '%' : '0.0%';
    },

    /**
     * "320.00 EUR + 450.00 USD" - what was actually spent, in the currencies
     * it was actually spent in, alongside the converted Total above it.
     * budget.nativeTotals already comes from the API sorted largest
     * (converted) contribution first, so this only has to format and join.
     */
    get nativeTotalsLabel() {
      return (this.budgetView.nativeTotals || [])
        .filter((n) => Number(n.amount) > 0)
        .map((n) => this.fmt(n.amount, n.currency))
        .join(' + ');
    },

    /**
     * Draws the spend-by-category-or-country pie with Chart.js, from whichever
     * breakdown budgetShow currently selects.
     *
     * Called from x-effect, so it re-runs whenever the rollup, the Show mode,
     * the totals currency or the tab changes — the reactive reads all happen
     * up front, before the frame wait, or the effect would not track them.
     *
     * Drawing waits a frame because x-show applies its display change on the
     * next one, and Chart.js measures a container that is still hidden
     * otherwise and sizes the canvas to nothing.
     */
    renderBudgetPie() {
      const slices = this.budgetBreakdown.map((s) => ({
        label: s.label, amount: s.amount, color: s.color,
      }));
      const currency = this.budget.totalsCurrency;
      const onBudgetTab = this.tab === 'budget';

      requestAnimationFrame(() => {
        if (pie) { pie.destroy(); pie = null; }

        const canvas = this.$refs.budgetPie;
        if (!onBudgetTab || !slices.length || !canvas || typeof Chart === 'undefined') return;

        pie = new Chart(canvas, {
          type: 'pie',
          data: {
            labels: slices.map((s) => s.label),
            datasets: [{
              data: slices.map((s) => s.amount),
              backgroundColor: slices.map((s) => s.color),
              borderWidth: 0,
            }],
          },
          options: {
            responsive: true,
            maintainAspectRatio: true,
            plugins: {
              // The bars beside it already name every slice.
              legend: { display: false },
              tooltip: {
                callbacks: {
                  label: (ctx) => `${ctx.label}: ${money(ctx.parsed, currency)}`,
                },
              },
            },
          },
        });
      });
    },

    /**
     * This member's part of a row, in the currency the row was spent in.
     *
     * Read back from the API rather than divided here. The split is exact to
     * the cent and hands the leftover pennies out in a fixed order so the
     * shares add up to the whole amount (see TripMembers); doing it again in
     * the browser would be a second implementation of that, free to disagree
     * with the totals by a cent.
     *
     * Null means the row is not this member's at all.
     */
    shareOf(item) {
      const share = (this.budget.shares || {})[item.id];
      return share == null ? null : Number(share);
    },

    /** This member's part of a row, in budget.totalsCurrency. */
    convertedShareOf(item) {
      const share = this.shareOf(item);
      return share == null ? null : this.convertAmount(share, item.currency);
    },

    /**
     * An amount in some currency, expressed in budget.totalsCurrency. Mirrors
     * BudgetService.convertAmount.
     *
     * Two currencies that used to be the same one, and are not any more. The
     * trip's `displayCurrency` is what an item with no currency of its own is
     * taken to be in. `ratesBase` is what every rate is quoted against and the
     * currency a cross-rate pivots through — rates are fetched daily for the
     * whole install against their own base, so it has nothing to do with the
     * trip.
     */
    convertAmount(rawAmount, currency) {
      const tripCurrency = this.budget.displayCurrency;
      const pivot = this.budget.ratesBase || tripCurrency;
      const target = this.budget.totalsCurrency;
      const amount = Number(rawAmount || 0);
      const from = currency || tripCurrency;
      if (from === target) return amount;

      const rates = this.budget.exchangeRates || {};
      let inAnchor;
      if (from === pivot) {
        inAnchor = amount;
      } else {
        const rate = Number(rates[from]);
        if (!rate) return null;
        inAnchor = amount / rate;
      }

      if (target === pivot) return inAnchor;
      const targetRate = Number(rates[target]);
      if (!targetRate) return null;
      return inAnchor * targetRate;
    },

    // ── expenses ────────────────────────────────────
    openAddExpense() {
      this.expenseForm = blankExpense();
      this.expenseForm.currency = this.budget.displayCurrency || '';
      // Whoever is typing it in most likely paid; one click clears it.
      this.expenseForm.paidByUserId = this.currentUserId || '';
      // And it is theirs until they say otherwise. An empty "Shared by" still
      // *means* the whole trip — that rule is unchanged and is what stops a
      // stored row belonging to nobody — but a form that opens empty splits a
      // new expense across everybody without ever saying so, which reads as a
      // bug the first time a solo flight turns up owed by five people.
      // Narrowing is now the explicit act, and widening is too.
      this.expenseForm.sharedByUserIds = this.currentUserId ? [this.currentUserId] : [];
      this.expenseError = '';
      this.expenseOpen = true;
      this.focusWhenShown('expenseDescription');
    },

    openEditExpense(item) {
      this.expenseForm = {
        id: item.id,
        description: item.description || '',
        note: item.note || '',
        category: item.category,
        amount: item.amount ?? '',
        currency: item.currency || this.budget.displayCurrency || '',
        date: item.date || '',
        countryCodes: [...(item.countryCodes || [])],
        sharedByUserIds: [...(item.sharedByUserIds || [])],
        paidByUserId: item.paidByUserId || '',
        charged: item.status !== 'PENDING',
      };
      this.expenseError = '';
      this.expenseOpen = true;
    },

    async saveExpense() {
      const description = this.expenseForm.description.trim();
      const amount = Number(this.expenseForm.amount);

      if (!description) {
        this.expenseError = 'Describe the expense.';
        return;
      }
      if (!Number.isFinite(amount) || amount < 0) {
        this.expenseError = 'Enter an amount of zero or more.';
        return;
      }
      const outsideTrip = this.dateOutsideTrip(this.expenseForm.date, 'date');
      if (outsideTrip) {
        this.expenseError = outsideTrip;
        return;
      }

      this.expenseError = '';
      this.expenseBusy = true;
      try {
        const payload = {
          description,
          note: this.expenseForm.note.trim() || null,
          category: this.expenseForm.category,
          amount,
          currency: this.expenseForm.currency || null,
          date: this.expenseForm.date || null,
          // An empty array clears every link server-side.
          countryCodes: this.expenseForm.countryCodes,
          // An empty array clears the names, which reads as the whole trip.
          sharedByUserIds: this.expenseForm.sharedByUserIds,
          // Always sent: on an edit an absent field leaves the payer alone,
          // so '' is what makes clearing it in the form actually clear it.
          paidByUserId: this.expenseForm.paidByUserId || '',
          charged: this.expenseForm.charged,
        };
        if (this.expenseForm.id) {
          await this.api.updateExpense(this.trip.id, this.expenseForm.id, payload);
          toast.success('Expense updated');
        } else {
          await this.api.addExpense(this.trip.id, payload);
          toast.success('Expense added');
        }
        this.expenseOpen = false;
        await this.reload();
      } catch (error) {
        this.expenseError = error.fullMessage;
      } finally {
        this.expenseBusy = false;
      }
    },

    // ── bulk selection ──────────────────────────────
    toggleBudgetSelected(id) {
      toggleId(this.budgetSelectedIds, id);
    },

    isBudgetSelected(id) {
      return this.budgetSelectedIds.includes(id);
    },

    /**
     * Selected rows the filter leaves, on any page — everything else derives
     * from this, so "Delete selected" can never remove a row a filter is
     * hiding, but does include rows ticked on another page of the table.
     */
    get budgetSelected() {
      return selectedPresent(this.budgetSelectedIds, this.filteredBudgetItems);
    },

    /** How many of the selected rows a plan created, for the confirm wording. */
    get budgetSelectedFromPlanCount() {
      return this.budgetSelected.filter((item) => item.itineraryItemId).length;
    },

    async confirmBulkDeleteExpenses() {
      const doomed = this.budgetSelected;
      if (!doomed.length) return;

      this.budgetBulkBusy = true;
      try {
        const { deleted, failed } = await runBulkDelete(
          doomed, (id) => this.api.deleteExpense(this.trip.id, id));

        this.budgetSelectedIds = [];
        this.budgetBulkOpen = false;

        if (failed) toast.error(`Deleted ${deleted} — ${failed} could not be removed`);
        else toast.success(`Deleted ${deleted} expense${deleted === 1 ? '' : 's'}`);

        await this.reload();
      } finally {
        this.budgetBulkBusy = false;
      }
    },

    // ── exchange rates ───────────────────────────────
    // Read-only. Rates are fetched daily for the whole install (see
    // RatesRefresher), so there is nothing here for a member to maintain and
    // no endpoint to maintain it with — the editor and its PATCH are gone.

    /**
     * Currencies in use with no rate in today's table, so their rows are left
     * out of the total rather than counted at 1:1.
     *
     * Nearly unreachable now — the provider carries 166 currencies and the
     * catalogue is 35 — so this means either the daily fetch has never
     * succeeded on this install or a code was stored that the provider does not
     * quote. Reported rather than silently absorbed either way.
     */
    get missingRates() {
      return this.budgetView.currenciesMissingRates || [];
    },

    /**
     * "Rates updated Mon, 14 Sep 2026 00:02:31" — the read-only note.
     *
     * Keyed on the date and nothing else. It used to guard on `ratesBase` and
     * then branch on the date, which left a branch that could only ever render
     * an empty string: a base is set only by a successful fetch, and that same
     * fetch sets the date. Both branches here are reachable — a fresh install
     * has no rates until the first refresh lands, and that is worth saying
     * rather than leaving a blank space where a date should be.
     *
     * The provider's own stamp, minus its "+0000" suffix. Which currency the
     * rates are quoted in goes unmentioned: the total beside this already names
     * the currency the numbers are in.
     */
    ratesNote() {
      const date = (this.budget.ratesDate || '').replace(/ \+\d{4}$/, '');
      return date ? `Rates updated ${date}` : 'Exchange rates not fetched yet';
    },


    // ── display helpers ─────────────────────────────
    categoryOf: (value) => category(value),
    expenseDate: (item) => (item.date ? shortDate(item.date) : '—'),
    fmt: (amount, currency) => money(amount, currency),

    /**
     * The headline total: "Total: 900.00 EUR", or "Forecast total: …" while a
     * forecast breakdown is selected.
     *
     * Named in the label rather than left to the reader to infer from the
     * Group by selector above it. The two numbers differ by exactly the
     * expenses nobody has paid yet, and an unlabelled figure that quietly
     * grew is worse than no forecast at all.
     */
    totalLabel() {
      const amount = this.fmt(this.budgetView.total, this.budget.totalsCurrency);
      return this.budgetForecast ? `Forecast total: ${amount}` : `Total: ${amount}`;
    },

    /**
     * "Your share of 6 expenses" — what the total above is actually counting.
     *
     * Worth saying out loud: this panel used to be the trip's whole budget and
     * now it is one member's part of it, and a figure that quietly got smaller
     * is the kind a reader assumes is a bug.
     */
    totalCaption() {
      const rows = this.myBudgetItems.length;
      return `Your share of ${rows} expense${rows === 1 ? '' : 's'} on this trip`;
    },

    /**
     * "Charged" or "Pending", for the table's Status column. Read off `status`
     * with PENDING as the only special case, so a row whose YAML predates the
     * field reads as the charge it was — the same rule the API applies.
     */
    statusLabel(item) {
      return item.status === 'PENDING' ? 'Pending' : 'Charged';
    },

    /**
     * Whether the budget row a plan's cost created is already charged — what
     * the Plan and Itinerary forms show in their own "Expense already charged"
     * box.
     *
     * Read back off the budget rather than kept on the plan. The row is the
     * thing that has a status, and it stays editable in the Budget tab, so a
     * second copy on the plan could only ever disagree with it. A plan with no
     * row yet answers false, which is also the default a new cost gets — see
     * BudgetSync on the API.
     */
    chargedOfPlan(plan) {
      const row = this.budgetRowOfPlan(plan);
      return row ? row.status !== 'PENDING' : false;
    },

    /**
     * Who the budget row a plan's cost created is shared by — what the Plan and
     * Itinerary forms show in their own "Shared by" field. Empty for a plan
     * with no row yet, which reads as the whole trip.
     */
    sharersOfPlan(plan) {
      return [...(this.budgetRowOfPlan(plan)?.sharedByUserIds || [])];
    },

    /**
     * Who paid for the budget row a plan's cost created. A plan with no row yet
     * (no cost so far) starts with the member filling in the form, the same
     * default a brand-new cost gets.
     */
    paidByOfPlan(plan) {
      const row = this.budgetRowOfPlan(plan);
      return row ? (row.paidByUserId || '') : (this.currentUserId || '');
    },

    /**
     * The Paid by cell. Only current members' names are known here, so a payer
     * who has since left reads "Former member" — the id stays on the row.
     */
    paidByLabel(item) {
      if (!item.paidByUserId) return '—';
      const member = this.members.find((m) => m.userId === item.paidByUserId);
      return member ? (member.displayName || member.email) : 'Former travel buddy';
    },

    /**
     * The budget row a plan's cost created, or undefined.
     *
     * Searches `budget.items`, which is every row on the trip rather than only
     * this member's — a plan's cost may well be shared by somebody else, and
     * its own form still has to show the truth about it.
     */
    budgetRowOfPlan(plan) {
      if (!plan?.id) return undefined;
      return (this.budget.items || []).find((item) => item.itineraryItemId === plan.id);
    },

    sourcePlan(item) {
      if (!item.itineraryItemId) return null;
      return this.itinerary.find((plan) => plan.id === item.itineraryItemId)?.description || 'a plan';
    },

    // ── member picker (shared with the Plan and itinerary forms) ────────
    toggleSharer,
    shareWithEveryone,
    sharedWithEveryone,
    choosePayer,
    chargedToggled,

    /** The trip's members, as the pills every "Shared by" field offers. */
    get tripSharers() {
      return sharersOfTrip(this.members);
    },

    // ── location picker (shared with the checklist and itinerary forms) ──
    toggleLocation,

    countryLabels(countryCodes) {
      return locationNames(this.destinations, countryCodes);
    },

    /** Comma-joined, for chip/label text; empty when nothing is linked. */
    countryLabel(countryCodes) {
      return this.countryLabels(countryCodes).join(', ');
    },

    // ── settle expenses ─────────────────────────────────────────────────

    /**
     * Which settlement's details are open, or null.
     *
     * The line itself is held rather than an index: the list is recomputed
     * from the summary on every reload, and an index would quietly start
     * pointing at somebody else's money the moment a row was added.
     */
    settleDetail: null,

    /**
     * What each other member and I owe each other, per currency — computed on
     * the API from the charged rows, never here. The division has to match the
     * Budget tab to the cent and `amount / n` in JS would not; see
     * BudgetService.settlements.
     */
    get settleRows() {
      return this.budget.settlements || [];
    },

    get hasSettlements() {
      return this.settleRows.length > 0;
    },

    /** Same fallback as the Paid by cell: only current members have names. */
    settleMemberName(userId) {
      const member = this.members.find((m) => m.userId === userId);
      return member ? (member.displayName || member.email) : 'Former travel buddy';
    },

    /**
     * "rainer owes you" or "you owe rainer", said in words rather than left to
     * a plus or minus sign a reader has to decode.
     */
    settleSummaryLine(row) {
      const name = this.settleMemberName(row.otherUserId);
      const net = Number(row.net || 0);
      if (net === 0) return `You and ${name} are square in ${row.currency}`;
      return net > 0
        ? `${name} owes you ${money(Math.abs(net), row.currency)}`
        : `You owe ${name} ${money(Math.abs(net), row.currency)}`;
    },

    settleNetClass(row) {
      const net = Number(row.net || 0);
      if (net === 0) return 'settle-net-level';
      return net > 0 ? 'settle-net-positive' : 'settle-net-negative';
    },

    /**
     * The Net figure said as a direction: "(to receive) 450.00 EUR" or
     * "(to pay) 450.00 EUR", never a bare negative.
     *
     * Which way the money goes is the entire point of this column, and a
     * leading minus is the part of a figure a reader skims past — so the
     * direction is carried in words, with colour only reinforcing it. That
     * also makes the column readable in monochrome and to anyone who does not
     * separate the two hues, which a red/green-only signal would not be.
     *
     * The amount is shown absolute, because "(to pay) −450.00" would state the
     * same thing twice and invite reading it as a negative debt.
     */
    settleNetLabel(row) {
      const net = Number(row.net || 0);
      if (net === 0) return money(0, row.currency);
      const direction = net > 0 ? '(to receive)' : '(to pay)';
      // The figure and its currency are glued together, so a narrow card wraps
      // after the direction rather than leaving "EUR" stranded on its own line.
      // Anchored to a trailing three-letter code, because a plain replace of
      // the first space would eat a thousands separator in some locales.
      const amount = money(Math.abs(net), row.currency).replace(/ ([A-Za-z]{3})$/, ' $1');
      return `${direction} ${amount}`;
    },

    openSettleDetails(row) {
      this.settleDetail = row;
    },

    closeSettleDetails() {
      this.settleDetail = null;
    },

    /**
     * The rows behind one settlement, each joined to the budget item it came
     * from. The API sends only an id and an amount per line, so the
     * description, date and category are read from `budget.items` — the row is
     * kept in one place rather than copied into the payload to fall out of
     * step with itself.
     */
    get settleDetailLines() {
      if (!this.settleDetail) return [];
      const items = this.budget.items || [];
      return (this.settleDetail.lines || []).map((line) => {
        const item = items.find((candidate) => candidate.id === line.itemId);
        return {
          itemId: line.itemId,
          amount: line.amount,
          owedToYou: line.owedToYou,
          description: item ? item.description : 'An expense that is no longer listed',
          category: item ? item.category : null,
          date: item ? item.date : null,
        };
      });
    },
  };
}
