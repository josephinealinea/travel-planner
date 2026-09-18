/**
 * How many budget rows the Budget tab shows per page.
 *
 * A per-browser preference like the theme and the panel style: it is about the
 * screen in front of you, not about the account, so it lives in localStorage
 * and is read once when the trip page loads.
 */
import { DEFAULT_BUDGET_PAGE_SIZE } from './config.js';

export const BUDGET_PAGE_SIZES = [10, 20, 50, 100];

const STORAGE_KEY = 'budgetPageSize';

export function savedBudgetPageSize() {
  try {
    const saved = Number(localStorage.getItem(STORAGE_KEY));
    return BUDGET_PAGE_SIZES.includes(saved) ? saved : DEFAULT_BUDGET_PAGE_SIZE;
  } catch {
    // Private browsing can throw on access, not just return null.
    return DEFAULT_BUDGET_PAGE_SIZE;
  }
}

export function saveBudgetPageSize(size) {
  const next = BUDGET_PAGE_SIZES.includes(Number(size)) ? Number(size) : DEFAULT_BUDGET_PAGE_SIZE;
  try { localStorage.setItem(STORAGE_KEY, String(next)); } catch { /* private mode */ }
  return next;
}
