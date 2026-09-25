import { t } from './i18n/index.js';
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
  [0,  '☀️', 'weather.wmo.0'],
  [1,  '🌤', 'weather.wmo.1'],
  [2,  '⛅️', 'weather.wmo.2'],
  [3,  '☁️', 'weather.wmo.3'],
  [45, '🌫', 'weather.wmo.45'],
  [48, '🌫', 'weather.wmo.48'],
  [51, '🌦', 'weather.wmo.51'],
  [53, '🌦', 'weather.wmo.53'],
  [55, '🌧', 'weather.wmo.55'],
  [56, '🌧', 'weather.wmo.56'],
  [57, '🌧', 'weather.wmo.57'],
  [61, '🌦', 'weather.wmo.61'],
  [63, '🌧', 'weather.wmo.63'],
  [65, '🌧', 'weather.wmo.65'],
  [66, '🌧', 'weather.wmo.66'],
  [67, '🌧', 'weather.wmo.67'],
  [71, '🌨', 'weather.wmo.71'],
  [73, '🌨', 'weather.wmo.73'],
  [75, '❄️', 'weather.wmo.75'],
  [77, '🌨', 'weather.wmo.77'],
  [80, '🌦', 'weather.wmo.80'],
  [81, '🌧', 'weather.wmo.81'],
  [82, '⛈', 'weather.wmo.82'],
  [85, '🌨', 'weather.wmo.85'],
  [86, '❄️', 'weather.wmo.86'],
  [95, '⛈', 'weather.wmo.95'],
  [96, '⛈', 'weather.wmo.96'],
  [99, '⛈', 'weather.wmo.99'],
];

/** Millimetres of rain in a day, to the same icon-and-label shape. */
const RAIN = [
  [20,  '⛈', 'weather.rain.veryWet'],
  [10,  '🌧', 'weather.rain.wet'],
  [4,   '🌧', 'weather.rain.likely'],
  [1,   '🌦', 'weather.rain.showersLikely'],
  [0.1, '🌤', 'weather.rain.mostlyDry'],
  [0,   '☀️', 'weather.rain.dry'],
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
    return { icon: match[1], label: t(match[2]) };
  }

  if (day.precipitation != null) {
    for (const [threshold, icon, label] of RAIN) {
      if (day.precipitation >= threshold) return { icon, label: t(label) };
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
  return t('weather.range', { high, low });
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
    case 'CLIMATE': return t('weather.typicalNote');
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
      text: t('weather.notYetOpen'),
      title: t('weather.opensDaysBefore', { days: FORECAST_OPENS_DAYS_AHEAD }),
    };
  }
  return {
    text: t('weather.unavailable'),
    title: t('weather.lookupFailed'),
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
    case 'FORECAST': return t('weather.forecast');
    case 'CLIMATE': return t('weather.typical');
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

// ── details ──────────────────────────────────────────────────────────────
//
// `day.details` is the document the API stores beside the reading: sunrise,
// UV, wind and so on, keyed as DetailField.key() names them. Two rules carry
// over from the API and are easy to break here:
//
//  - **absent means unknown, and 0 is an answer.** Every test below is
//    `!= null`, never truthiness, or "0% chance of rain" and "no wind" would
//    quietly disappear — the very days a traveller most wants told.
//  - **the endpoints do not all answer everything.** A CLIMATE row has no UV,
//    feels-like, rain probability or wind direction, so those chips simply are
//    not there; nothing renders a placeholder for them.

const known = (value) => value != null;
const whole = (value) => Math.round(value);

/** "05:35" from "2026-09-25T05:35" — the API already sends destination-local time. */
const clock = (iso) => (typeof iso === 'string' && iso.length >= 16 ? iso.slice(11, 16) : null);

const COMPASS = ['weather.compass.n', 'weather.compass.ne', 'weather.compass.e', 'weather.compass.se',
                 'weather.compass.s', 'weather.compass.sw', 'weather.compass.w', 'weather.compass.nw'];
const compass = (degrees) => t(COMPASS[Math.round(degrees / 45) % 8]);

/**
 * The few that earn room on the card itself, in reading order.
 *
 * Every chip says what it is in words ("4% rain", "Feels 23° / 5°"), not just
 * with an icon: a tooltip cannot be reached by touch or by everyone using a
 * screen reader, so the title is a bonus and never the only explanation.
 */
export function detailChips(day) {
  const d = day?.details;
  if (!d) return [];
  const chips = [];

  const rise = clock(d.sunrise);
  const set = clock(d.sunset);
  if (rise || set) {
    chips.push({
      id: 'sun',
      icon: '🌅',
      text: [rise, set].filter(Boolean).join(' · '),
      title: t('weather.sunTimes'),
    });
  }
  if (known(d.uvIndexMax)) {
    chips.push({ id: 'uv', icon: '☀️', text: t('weather.uv', { n: whole(d.uvIndexMax) }), title: t('weather.uvTip') });
  }

  // Chance and amount share one chip. A dry day with a known chance still says
  // "0% rain" (the answer a traveller wants); a dry climate day, which has no
  // chance to state, says nothing — its condition label already reads "Dry".
  const rainParts = [];
  if (known(d.precipitationProbabilityMax)) rainParts.push(`${whole(d.precipitationProbabilityMax)}%`);
  if (known(d.rainSum) && d.rainSum > 0) rainParts.push(t('weather.mm', { n: round1(d.rainSum) }));
  if (rainParts.length) {
    chips.push({ id: 'rain', icon: '💧', text: t('weather.rain', { value: rainParts.join(' · ') }), title: t('weather.rainTip') });
  }
  if (known(d.snowfallSum) && d.snowfallSum > 0) {
    chips.push({ id: 'snow', icon: '❄️', text: t('weather.snow', { n: round1(d.snowfallSum) }), title: t('weather.snowTip') });
  }

  if (known(d.windSpeedMax)) {
    chips.push({
      id: 'wind',
      icon: '💨',
      text: t('weather.wind', { n: whole(d.windSpeedMax) }),
      title: known(d.windGustsMax) ? t('weather.gustsTip', { n: whole(d.windGustsMax) }) : t('weather.windTip'),
    });
  }

  // Feels-like only earns a chip when it differs from the real temperature by
  // a degree or more: two near-identical ranges stacked on a card are noise,
  // and it is the only chip that could be mistaken for the reading above it.
  if (known(d.apparentTemperatureMax) || known(d.apparentTemperatureMin)) {
    const differs = (feels, real) => known(feels) && (!known(real) || Math.abs(feels - real) >= 1);
    if (differs(d.apparentTemperatureMax, day.temperatureMax) || differs(d.apparentTemperatureMin, day.temperatureMin)) {
      const high = known(d.apparentTemperatureMax) ? `${whole(d.apparentTemperatureMax)}°` : null;
      const low = known(d.apparentTemperatureMin) ? `${whole(d.apparentTemperatureMin)}°` : null;
      chips.push({ id: 'feels', icon: '🌡', text: t('weather.feels', { value: [high, low].filter(Boolean).join(' / ') }), title: t('weather.feelsTip') });
    }
  }
  return chips;
}

/** Everything else that is known, as one sentence for a tooltip. */
export function detailsTitle(day) {
  const d = day?.details;
  if (!d) return '';
  const parts = [];
  if (known(d.humidityMean)) parts.push(t('weather.humidity', { n: whole(d.humidityMean) }));
  if (known(d.cloudCoverMean)) parts.push(t('weather.cloudCover', { n: whole(d.cloudCoverMean) }));
  if (known(d.daylightSeconds)) {
    const minutes = Math.round(d.daylightSeconds / 60);
    parts.push(t('weather.daylight', { h: Math.floor(minutes / 60), m: String(minutes % 60).padStart(2, '0') }));
  }
  if (known(d.windDirection)) parts.push(t('weather.windFrom', { direction: compass(d.windDirection) }));
  return parts.join(' · ');
}

function round1(value) {
  return Math.round(value * 10) / 10;
}
