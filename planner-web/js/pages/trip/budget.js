import { toast } from '../../toast.js';
import { category, money, shortDate, CATEGORIES } from '../../format.js';

/**
 * The Budget tab.
 *
 * Rows created by a plan's cost stay editable here like any other, and the
 * rollup converts into the trip's display currency using the hand-maintained
 * rates. Anything in a currency with no rate is listed as excluded rather than
 * folded in at some guessed value.
 */
export function budgetTab() {
  const blankExpense = () => ({
    id: null,
    description: '',
    category: 'OTHERS',
    amount: '',
    currency: '',
    date: '',
  });

  return {
    expenseOpen: false,
    expenseForm: blankExpense(),
    expenseError: '',
    expenseBusy: false,
    deletingExpense: null,

    ratesOpen: false,
    ratesDraft: [],
    ratesCurrency: '',
    ratesError: '',
    ratesBusy: false,

    // ── rollup ──────────────────────────────────────
    get budgetCategories() {
      const totals = this.budget.byCategory || {};
      return CATEGORIES
        .map((cat) => ({ ...cat, amount: Number(totals[cat.value] || 0) }))
        .filter((cat) => cat.amount > 0);
    },

    get budgetLargest() {
      return this.budgetCategories.reduce((max, cat) => Math.max(max, cat.amount), 0);
    },

    barWidth(amount) {
      return this.budgetLargest > 0 ? (amount / this.budgetLargest) * 100 : 0;
    },

    /**
     * Donut segments as stroke-dash offsets on a single circle — no charting
     * library for four slices.
     */
    get donutSegments() {
      const total = this.budgetCategories.reduce((sum, cat) => sum + cat.amount, 0);
      if (!total) return [];
      const circumference = 2 * Math.PI * 42;
      let offset = 0;
      return this.budgetCategories.map((cat) => {
        const length = (cat.amount / total) * circumference;
        const segment = {
          color: cat.color,
          dash: `${length} ${circumference - length}`,
          offset: -offset,
        };
        offset += length;
        return segment;
      });
    },

    convertedOf(item) {
      const display = this.budget.displayCurrency;
      if (!item.currency || item.currency === display) return Number(item.amount || 0);
      const rate = Number((this.budget.exchangeRates || {})[item.currency]);
      if (!rate) return null;
      return Number(item.amount) / rate;
    },

    // ── expenses ────────────────────────────────────
    openAddExpense() {
      this.expenseForm = blankExpense();
      this.expenseForm.currency = this.budget.displayCurrency || '';
      this.expenseError = '';
      this.expenseOpen = true;
      this.$nextTick(() => this.$refs.expenseDescription?.focus());
    },

    openEditExpense(item) {
      this.expenseForm = {
        id: item.id,
        description: item.description || '',
        category: item.category,
        amount: item.amount ?? '',
        currency: item.currency || this.budget.displayCurrency || '',
        date: item.date || '',
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

      this.expenseError = '';
      this.expenseBusy = true;
      try {
        const payload = {
          description,
          category: this.expenseForm.category,
          amount,
          currency: this.expenseForm.currency || null,
          date: this.expenseForm.date || null,
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

    askDeleteExpense(item) {
      this.deletingExpense = item;
    },

    async confirmDeleteExpense() {
      try {
        await this.api.deleteExpense(this.trip.id, this.deletingExpense.id);
        this.deletingExpense = null;
        toast.success('Expense removed');
        await this.reload();
      } catch (error) {
        toast.error(error.fullMessage);
        this.deletingExpense = null;
      }
    },

    // ── display currency and rates ──────────────────
    async setDisplayCurrency(currency) {
      try {
        await this.api.updateTrip(this.trip.id, { displayCurrency: currency });
        await this.reload();
      } catch (error) {
        toast.error(error.fullMessage);
      }
    },

    openRates() {
      this.ratesDraft = Object.entries(this.budget.exchangeRates || {})
        .map(([currency, rate]) => ({ currency, rate: String(rate) }));
      this.ratesCurrency = '';
      this.ratesError = '';
      this.ratesOpen = true;
    },

    addRateRow() {
      const currency = this.ratesCurrency.trim().toUpperCase();
      if (!currency) return;
      if (this.ratesDraft.some((row) => row.currency === currency)) {
        this.ratesError = `There is already a rate for ${currency}.`;
        return;
      }
      this.ratesDraft.push({ currency, rate: '' });
      this.ratesCurrency = '';
      this.ratesError = '';
    },

    removeRateRow(index) {
      this.ratesDraft.splice(index, 1);
    },

    async saveRates() {
      const rates = {};
      for (const row of this.ratesDraft) {
        const value = Number(row.rate);
        if (!row.currency) continue;
        if (!Number.isFinite(value) || value <= 0) {
          this.ratesError = `The rate for ${row.currency} needs to be a number above zero.`;
          return;
        }
        rates[row.currency.toUpperCase()] = value;
      }

      this.ratesError = '';
      this.ratesBusy = true;
      try {
        await this.api.updateTrip(this.trip.id, { exchangeRates: rates });
        this.ratesOpen = false;
        toast.success('Exchange rates saved');
        await this.reload();
      } catch (error) {
        this.ratesError = error.fullMessage;
      } finally {
        this.ratesBusy = false;
      }
    },

    /** Currencies in use that still have no rate — offered as one-click adds. */
    get missingRates() {
      return this.budget.currenciesMissingRates || [];
    },

    addMissingRate(currency) {
      this.openRates();
      if (!this.ratesDraft.some((row) => row.currency === currency)) {
        this.ratesDraft.push({ currency, rate: '' });
      }
    },

    // ── display helpers ─────────────────────────────
    categoryOf: (value) => category(value),
    expenseDate: (item) => (item.date ? shortDate(item.date) : '—'),
    fmt: (amount, currency) => money(amount, currency),

    sourcePlan(item) {
      if (!item.itineraryItemId) return null;
      return this.itinerary.find((plan) => plan.id === item.itineraryItemId)?.description || 'a plan';
    },
  };
}
