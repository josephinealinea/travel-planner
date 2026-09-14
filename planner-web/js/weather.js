/**
 * Turning one day's reading into something readable.
 *
 * Two sources reach here and they do not carry the same fields, which is the
 * whole reason this file exists rather than a couple of inline expressions:
 *
 *  - FORECAST, from the fortnight Open-Meteo actually predicts, carries a WMO
 *    `weather_code` and so gets a real condition ("Light rain", 🌦);
 *  - CLIMATE, the downscaled model that answers for any other date, accepts
 *    `weather_code` and returns nulls. All it can say is how much rain fell in
 *    the model's day, so the condition is derived from that instead.
 *
 * A trip booked more than a fortnight out — which is most trips — is therefore
 * almost entirely CLIMATE, and `sourceNote` is what keeps that honest on the
 * page. A climate projection is what late October is *like* in Cusco, not what
 * the weather will be on 28 October 2026, and a row that did not say so would
 * be inviting somebody to pack from a number that cannot mean what it looks
 * like it means.
 */

/**
 * WMO 4677 weather codes, as Open-Meteo documents them, collapsed to the groups
 * a traveller actually distinguishes. Codes not listed fall back to the nearest
 * group below them, so a future code never renders blank.
 */
const WMO = [
  [0,  '☀️', 'Clear'],
  [1,  '🌤', 'Mainly clear'],
  [2,  '⛅️', 'Partly cloudy'],
  [3,  '☁️', 'Overcast'],
  [45, '🌫', 'Fog'],
  [48, '🌫', 'Freezing fog'],
  [51, '🌦', 'Light drizzle'],
  [53, '🌦', 'Drizzle'],
  [55, '🌧', 'Heavy drizzle'],
  [56, '🌧', 'Freezing drizzle'],
  [57, '🌧', 'Freezing drizzle'],
  [61, '🌦', 'Light rain'],
  [63, '🌧', 'Rain'],
  [65, '🌧', 'Heavy rain'],
  [66, '🌧', 'Freezing rain'],
  [67, '🌧', 'Freezing rain'],
  [71, '🌨', 'Light snow'],
  [73, '🌨', 'Snow'],
  [75, '❄️', 'Heavy snow'],
  [77, '🌨', 'Snow grains'],
  [80, '🌦', 'Light showers'],
  [81, '🌧', 'Showers'],
  [82, '⛈', 'Violent showers'],
  [85, '🌨', 'Snow showers'],
  [86, '❄️', 'Heavy snow showers'],
  [95, '⛈', 'Thunderstorm'],
  [96, '⛈', 'Thunderstorm with hail'],
  [99, '⛈', 'Thunderstorm with hail'],
];

/** Millimetres of rain in a day, to the same icon-and-label shape. */
const RAIN = [
  [20,  '⛈', 'Very wet'],
  [10,  '🌧', 'Wet'],
  [4,   '🌧', 'Rain likely'],
  [1,   '🌦', 'Showers likely'],
  [0.1, '🌤', 'Mostly dry'],
  [0,   '☀️', 'Dry'],
];

// No placeholder glyph: an icon slot with a dot in it reads as a bullet
// point in front of every row that has no reading, which is most of them for
// a trip booked months out.
const UNKNOWN = { icon: '', label: '' };

/**
 * The icon and condition for one day. Prefers the code when there is one, and
 * reads the rainfall when there is not — which is every CLIMATE row.
 */
export function condition(day) {
  if (!day) return UNKNOWN;

  if (day.weatherCode != null) {
    let match = WMO[0];
    for (const entry of WMO) {
      if (day.weatherCode >= entry[0]) match = entry;
    }
    return { icon: match[1], label: match[2] };
  }

  if (day.precipitation != null) {
    for (const [threshold, icon, label] of RAIN) {
      if (day.precipitation >= threshold) return { icon, label };
    }
  }

  return UNKNOWN;
}

/** "18° / 7°", or just the one end when only one is known. */
export function temperatureRange(day) {
  const high = round(day?.temperatureMax);
  const low = round(day?.temperatureMin);
  if (high == null && low == null) return '';
  if (low == null) return `${high}°`;
  if (high == null) return `${low}°`;
  return `${high}° / ${low}°`;
}

/**
 * What the reader is actually looking at. Empty for a real forecast — there is
 * nothing to warn about — and explicit for a projection.
 *
 * Says nothing for an unavailable day: that case gets its own line from
 * unavailableHint() rather than a note tacked onto a row with no numbers on it.
 */
export function sourceNote(day) {
  switch (day?.source) {
    case 'FORECAST': return '';
    case 'CLIMATE': return 'typical for these dates, not a forecast';
    default: return '';
  }
}

/**
 * How far ahead Open-Meteo will actually forecast. Mirrors app.weather
 * horizon-days on the API side; duplicated here only to word this one line,
 * never to decide which endpoint gets asked.
 */
const FORECAST_OPENS_DAYS_AHEAD = 16;

/**
 * The line a day with no reading shows instead of numbers.
 *
 * Two different situations end up as UNAVAILABLE and they deserve different
 * words. A date months out has no forecast yet and never did — that is the
 * normal case for a trip being planned, and saying so is more useful than
 * calling it an error. A date inside the forecast window with no reading means
 * the lookup itself did not come back, which is worth wording as temporary
 * because reopening the tab retries it.
 */
export function unavailableHint(day) {
  const date = parseIsoDate(day?.date);
  if (date && daysFromToday(date) > FORECAST_OPENS_DAYS_AHEAD) {
    return {
      text: '📅 Forecast not yet open',
      title: `Forecast opens ${FORECAST_OPENS_DAYS_AHEAD} days before this date`,
    };
  }
  return {
    text: '📅 Weather unavailable',
    title: 'The lookup did not come back. Reopening this tab tries again.',
  };
}

function parseIsoDate(iso) {
  if (!iso) return null;
  const [y, m, d] = String(iso).slice(0, 10).split('-').map(Number);
  return y && m && d ? new Date(y, m - 1, d) : null;
}

function daysFromToday(date) {
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  return Math.round((date - today) / 86400000);
}

/** Shorter version of the same, for the badge itself. */
export function sourceBadge(day) {
  switch (day?.source) {
    case 'FORECAST': return 'Forecast';
    case 'CLIMATE': return 'Typical';
    default: return '';
  }
}

export function hasReading(day) {
  return !!day && day.source !== 'UNAVAILABLE'
    && (day.temperatureMax != null || day.temperatureMin != null);
}

function round(value) {
  return value == null ? null : Math.round(value);
}
