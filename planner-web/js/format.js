/** Display formatting. Everything here tolerates null, because most fields are optional. */

const MONTHS_SHORT = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
                      'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const MONTHS_LONG = ['January', 'February', 'March', 'April', 'May', 'June',
                     'July', 'August', 'September', 'October', 'November', 'December'];

/** Parses "2026-10-25" as a local date, so the day never shifts by timezone. */
export function parseDate(iso) {
  if (!iso) return null;
  const [y, m, d] = String(iso).slice(0, 10).split('-').map(Number);
  if (!y || !m || !d) return null;
  return new Date(y, m - 1, d);
}

export function shortDate(iso) {
  const date = parseDate(iso);
  return date ? `${date.getDate()} ${MONTHS_SHORT[date.getMonth()]}` : '';
}

export function longDate(iso) {
  const date = parseDate(iso);
  return date ? `${date.getDate()} ${MONTHS_LONG[date.getMonth()]} ${date.getFullYear()}` : '';
}

export function dateRange(startIso, endIso) {
  if (!startIso && !endIso) return '';
  if (!endIso) return shortDate(startIso);
  if (!startIso) return shortDate(endIso);
  if (startIso === endIso) return shortDate(startIso);
  return `${shortDate(startIso)} – ${shortDate(endIso)}`;
}

/** "2026-10-25T15:00:00" -> "15:00" */
export function timeOf(isoDateTime) {
  if (!isoDateTime) return '';
  const time = String(isoDateTime).split('T')[1];
  return time ? time.slice(0, 5) : '';
}

export function dateOf(isoDateTime) {
  return isoDateTime ? String(isoDateTime).split('T')[0] : '';
}

export function timeRange(startAt, endAt) {
  const start = timeOf(startAt);
  const end = timeOf(endAt);
  if (!start) return '';
  // Only show the end time when it is the same day; otherwise it misleads.
  if (end && dateOf(startAt) === dateOf(endAt)) return `${start} – ${end}`;
  return start;
}

/**
 * Nights between two dates, or null when it is not calculable — the same rule
 * the API applies when it words a seeded accommodation item ("Plan 6N
 * accommodation in Cusco"). Kept here too because nights are pure display in
 * the editor, and storing derived values in the trip files would let them go
 * stale against the dates they came from.
 */
export function nightsBetween(startIso, endIso) {
  const start = parseDate(startIso);
  const end = parseDate(endIso);
  if (!start || !end) return null;
  const nights = Math.round((end - start) / 86400000);
  return nights > 0 ? nights : null;
}

export function money(amount, currency) {
  if (amount == null || amount === '') return '';
  const value = Number(amount);
  if (Number.isNaN(value)) return String(amount);
  const formatted = value.toLocaleString(undefined,
    { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  return currency ? `${formatted} ${currency}` : formatted;
}

export function daysUntil(iso) {
  const date = parseDate(iso);
  if (!date) return null;
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  return Math.round((date - today) / 86400000);
}

export function countdownLabel(iso) {
  const days = daysUntil(iso);
  if (days == null) return '';
  if (days === 0) return 'Today';
  if (days === 1) return 'Tomorrow';
  if (days > 1) return `In ${days} days`;
  if (days === -1) return 'Yesterday';
  return `${Math.abs(days)} days ago`;
}

export const CATEGORIES = [
  { value: 'TRANSPORTATION', label: 'Transportation', icon: '✈️', color: '#F76707' },
  { value: 'LODGING',        label: 'Lodging',        icon: '🏨', color: '#4C6EF5' },
  { value: 'ACTIVITIES',     label: 'Activities',     icon: '🎟️', color: '#E64980' },
  { value: 'OTHERS',         label: 'Others',         icon: '💰', color: '#868E96' },
];

const BY_VALUE = Object.fromEntries(CATEGORIES.map((c) => [c.value, c]));

export function category(value) {
  return BY_VALUE[value] || BY_VALUE.OTHERS;
}

/** Currencies offered in the pickers. Any code the API accepts still works. */
export const CURRENCIES = ['EUR', 'USD', 'GBP', 'PEN', 'BOB', 'BRL', 'SGD', 'CHF', 'JPY', 'AUD', 'CAD'];
