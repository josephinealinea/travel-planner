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

/**
 * An instant ("2026-09-17T05:43:30Z") in the reader's own timezone, in the
 * same day-month-year order as every other date on the page. toLocaleString()
 * gave "17/09/2026, 07:43:30" beside "24 Oct" — three formats on one screen,
 * with seconds nobody needs.
 */
export function dateTimeLabel(iso) {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  const hh = String(date.getHours()).padStart(2, '0');
  const mm = String(date.getMinutes()).padStart(2, '0');
  return `${dateLabel(iso)}, ${hh}:${mm}`;
}

/** The date part of an instant, local time: "14 Sep 2026". */
export function dateLabel(iso) {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  return `${date.getDate()} ${MONTHS_SHORT[date.getMonth()]} ${date.getFullYear()}`;
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

/**
 * Days a destination is visited, counting both ends — Cusco 25-Oct to 31-Oct
 * is 7, and a day trip on 24-Oct is 1.
 *
 * Deliberately not `nightsBetween() + 1`: nights is null for a day trip on
 * purpose (that null is what tells the API a destination needs no room), and
 * adding one to it would turn the trip's only single-day stop into "—" instead
 * of the 1D it plainly is. The two answer different questions, so they count
 * separately from the same two dates. See CLAUDE.md, "Nights versus days".
 */
export function daysBetween(startIso, endIso) {
  const start = parseDate(startIso);
  const end = parseDate(endIso);
  if (!start || !end) return null;
  const days = Math.round((end - start) / 86400000) + 1;
  return days > 0 ? days : null;
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

/**
 * Mirrors ChecklistCategory on the API, in declaration order — which is the
 * order every picker, filter and legend here shows.
 *
 * The icons and colours are not chosen here: they come from the Jekyll site's
 * _data/travels/budget_categories.yml, the same file PublishStyle reads, so a
 * category looks identical in the planner, on a published page, and next to
 * the hand-written trips. Adding one means adding it there first.
 */
export const CATEGORIES = [
  { value: 'TRANSPORTATION', label: 'Transpo',        icon: '✈️', color: '#F76707' },
  { value: 'LODGING',        label: 'Lodging',        icon: '🏨', color: '#4C6EF5' },
  { value: 'ACTIVITIES',     label: 'Activities',     icon: '🎟️', color: '#E64980' },
  { value: 'SHOPPING',       label: 'Shopping',       icon: '🛍️', color: '#AE3EC9' },
  { value: 'FOOD',           label: 'Food',           icon: '🍽️', color: '#2F9E44' },
  { value: 'OTHERS',         label: 'Others',         icon: '💰', color: '#868E96' },
];

const BY_VALUE = Object.fromEntries(CATEGORIES.map((c) => [c.value, c]));

export function category(value) {
  return BY_VALUE[value] || BY_VALUE.OTHERS;
}
