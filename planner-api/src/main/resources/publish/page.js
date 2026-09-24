/*
 * Renders a published trip from the inlined window.TRIP payload.
 *
 * Deliberately dependency-free and defensive: this file ships inside a static
 * page that may sit on a CDN for years, so it must not assume any section is
 * present or non-empty.
 */
(function () {
  'use strict';

  var trip = window.TRIP || {};
  var root = document.getElementById('app');
  if (!root) return;

  var MONTHS = ['January', 'February', 'March', 'April', 'May', 'June',
                'July', 'August', 'September', 'October', 'November', 'December'];

  // ── helpers ───────────────────────────────────────────

  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = text;
    return node;
  }

  /** Parses "2026-10-25" without letting the local timezone shift the day. */
  function parseDate(iso) {
    if (!iso) return null;
    var parts = String(iso).split('-');
    if (parts.length !== 3) return null;
    return new Date(Number(parts[0]), Number(parts[1]) - 1, Number(parts[2]));
  }

  function longDate(iso) {
    var date = parseDate(iso);
    if (!date) return '';
    return date.getDate() + ' ' + MONTHS[date.getMonth()] + ' ' + date.getFullYear();
  }

  function shortDate(iso) {
    var date = parseDate(iso);
    if (!date) return '';
    return date.getDate() + ' ' + MONTHS[date.getMonth()].slice(0, 3);
  }

  function money(amount, currency) {
    if (amount == null) return '';
    var value = Number(amount);
    var formatted = isNaN(value)
      ? String(amount)
      : value.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    return currency ? formatted + ' ' + currency : formatted;
  }

  // Country names come from one table, inlined by the renderer as
  // window.COUNTRIES (code -> name) from publish/countries.json. The payload
  // carries only codes, so a name is spelled in one place.
  var COUNTRY_NAMES = window.COUNTRIES || {};
  function countryName(code) {
    var key = String(code || '').toUpperCase();
    return key ? (COUNTRY_NAMES[key] || key) : '';
  }
  function flagOf(code) {
    var key = String(code || '').toUpperCase();
    if (!/^[A-Z]{2}$/.test(key)) return '';
    return String.fromCodePoint(0x1F1A5 + key.charCodeAt(0), 0x1F1A5 + key.charCodeAt(1));
  }
  function countryLabel(code) {
    var flag = flagOf(code);
    return (flag ? flag + ' ' : '') + countryName(code);
  }

  function list(value) {
    return Array.isArray(value) ? value : [];
  }

  // ── header ────────────────────────────────────────────

  function header() {
    var wrap = el('header', 'trip-header');
    wrap.appendChild(el('h1', 'trip-title', trip.title || 'Trip'));

    if (trip.startDate || trip.endDate) {
      wrap.appendChild(el('p', 'trip-dates',
        longDate(trip.startDate) + ' – ' + longDate(trip.endDate)));
    }

    var flags = list(trip.countries).map(function (c) { return c.flag; })
      .filter(Boolean).join(' ');
    if (flags) wrap.appendChild(el('div', 'trip-flags', flags));

    if (trip.routeSummary) wrap.appendChild(el('div', 'trip-route', trip.routeSummary));
    return wrap;
  }

  // ── theme switcher ────────────────────────────────────
  //
  // Every palette page.css can style is already inlined in this file as a
  // :root[data-theme] block, and the html element's data-theme is the only
  // thing selecting between them. So switching theme is one attribute write —
  // no stylesheet to fetch, no request of any kind, which is what makes it
  // work on a published page sitting on a CDN with nothing behind it.
  //
  // The theme the trip was published in is the starting point; a reader's own
  // choice overrides it from then on, remembered across every published trip
  // they open on this origin.
  var THEME_LABELS = {
    minima: 'Minima',
    y2k: 'Y2K',
    dark: 'Dark',
    'retro-game': 'FF7',
    manila: 'Manila',
  };
  var THEME_STORAGE_KEY = 'publishedTripTheme';

  /** What this page was published able to offer — see StaticSiteRenderer. */
  function offeredThemes() {
    var declared = (document.documentElement.getAttribute('data-themes') || '')
      .split(',').map(function (n) { return n.trim(); }).filter(Boolean);
    // Only names this file can label; anything else would render as a blank
    // button. An older page that shipped a withdrawn theme still lists it,
    // which is correct — its palette is inlined and still works.
    return declared.filter(function (name) { return THEME_LABELS[name]; });
  }

  function publishedTheme() {
    return document.documentElement.getAttribute('data-theme') || 'minima';
  }

  function readTheme(offered) {
    try {
      var saved = localStorage.getItem(THEME_STORAGE_KEY);
      if (saved && offered.indexOf(saved) !== -1) return saved;
    } catch (e) { /* private browsing can throw on access, not just return null */ }
    return publishedTheme();
  }

  function themeBar() {
    var offered = offeredThemes();
    // Nothing to choose between: one theme, or a page published before this
    // existed and carrying no list at all.
    if (offered.length < 2) return null;

    var bar = el('div', 'theme-bar');
    bar.appendChild(el('span', 'theme-bar-label', 'Theme'));

    var active = readTheme(offered);
    apply(active);

    offered.forEach(function (name) {
      var button = el('button', 'theme-btn', THEME_LABELS[name]);
      button.type = 'button';
      button.setAttribute('data-theme-choice', name);
      button.setAttribute('aria-pressed', String(name === active));
      bar.appendChild(button);
    });

    bar.addEventListener('click', function (event) {
      var button = event.target.closest('[data-theme-choice]');
      if (!button) return;
      var chosen = button.getAttribute('data-theme-choice');
      apply(chosen);
      try { localStorage.setItem(THEME_STORAGE_KEY, chosen); } catch (e) { /* private mode */ }
      bar.querySelectorAll('[data-theme-choice]').forEach(function (other) {
        other.setAttribute('aria-pressed',
          String(other.getAttribute('data-theme-choice') === chosen));
      });
    });

    return bar;

    function apply(name) {
      document.documentElement.setAttribute('data-theme', name);
    }
  }

  // ── sections ──────────────────────────────────────────

  function destinationsPanel() {
    var items = list(trip.destinations);
    var panel = section('destinations', '🧭 Destinations');
    if (!items.length) {
      panel.appendChild(el('p', 'empty', 'No destinations yet.'));
      return panel;
    }

    var grid = el('div', 'dest-grid');
    items.forEach(function (destination) {
      var card = el('div', 'card');

      var name = el('div', 'dest-name');
      name.textContent = (destination.flag ? destination.flag + ' ' : '') + destination.name;
      // Whichever the publishing account asked for arrives; the other is
      // absent, so there is no flag to read here — see PublishedTrip.Destination.
      if (destination.nights || destination.days) {
        name.appendChild(el('span', 'nights-badge', destination.days
          ? destination.days + 'D'
          : destination.nights + 'N'));
      }
      card.appendChild(name);

      var meta = [];
      if (destination.countryCode) meta.push(countryName(destination.countryCode));
      if (destination.startDate) {
        meta.push(destination.endDate && destination.endDate !== destination.startDate
          ? shortDate(destination.startDate) + ' – ' + shortDate(destination.endDate)
          : shortDate(destination.startDate));
      }
      if (meta.length) card.appendChild(el('div', 'dest-meta', meta.join(' · ')));

      if (destination.notes) card.appendChild(el('div', 'dest-notes', destination.notes));

      if (destination.mapUrl) {
        var link = el('a', 'dest-meta', 'View on map');
        link.href = destination.mapUrl;
        link.target = '_blank';
        link.rel = 'noopener noreferrer';
        var row = el('div', 'dest-notes');
        row.appendChild(link);
        card.appendChild(row);
      }

      grid.appendChild(card);
    });

    panel.appendChild(grid);
    return panel;
  }

  // Every weather slot waiting on a lookup, gathered while rendering so the
  // fetch can be one batched call instead of one per card.
  var pending = [];

  function itineraryPanel() {
    var days = list(trip.days);
    var panel = section('weather itinerary', '🗓 Itinerary');
    // The heading is rewritten by show() when only one half is on screen: a
    // column of weather cards under the word "Itinerary" reads wrong.
    panel.querySelector('.panel-heading').setAttribute('data-heading-weather',
      '🌤 Weather Forecast');
    if (!days.length) {
      panel.appendChild(el('p', 'empty', 'Nothing planned yet.'));
      return panel;
    }

    days.forEach(function (day) {
      var block = el('div', 'day');
      block.appendChild(el('h3', 'day-date', longDate(day.date)));

      var places = list(day.places);
      if (places.length) {
        var grid = el('div', 'weather-locations');
        grid.setAttribute('data-part', 'weather');
        places.forEach(function (place, index) {
          var pc = el('div', 'weather-card');
          // Odd counts lead with one full-width card, matching the planner.
          if (index === 0 && places.length % 2 === 1) pc.className += ' weather-card-wide';

          var head = el('div', 'weather-card-head');
          head.appendChild(el('span', 'weather-place', place.name || ''));
          if (place.countryCode) {
            head.appendChild(el('span', 'chip', countryLabel(place.countryCode)));
          }
          pc.appendChild(head);

          // Filled in by the lookup below, or left saying why it is empty.
          var line = el('div', 'weather-line weather-line-muted', '📅 Loading weather…');
          pc.appendChild(line);
          if (place.latitude != null && place.longitude != null) {
            pending.push({ date: day.date, lat: place.latitude, lon: place.longitude, line: line });
          } else {
            line.textContent = '📅 No coordinates for this place';
          }
          grid.appendChild(pc);
        });
        block.appendChild(grid);
      }

      if (!list(day.entries).length) {
        panel.appendChild(block);
        return;
      }

      var card = el('div', 'card');
      card.setAttribute('data-part', 'itinerary');
      list(day.entries).forEach(function (entry) {
        var row = el('div', 'entry');
        row.appendChild(el('div', 'entry-icon', entry.icon || '•'));

        var body = el('div', 'entry-body');
        body.appendChild(el('div', 'entry-desc', entry.description || ''));

        var meta = [];
        if (entry.startTime) {
          meta.push(entry.endTime ? entry.startTime + ' – ' + entry.endTime : entry.startTime);
        }
        if (entry.categoryLabel) meta.push(entry.categoryLabel);
        if (meta.length) body.appendChild(el('div', 'entry-meta', meta.join(' · ')));
        row.appendChild(body);

        if (entry.cost) {
          row.appendChild(el('div', 'entry-cost', money(entry.cost, entry.currency)));
        }
        card.appendChild(row);
      });

      block.appendChild(card);
      panel.appendChild(block);
    });

    return panel;
  }

  function checklistPanel() {
    var items = list(trip.checklist);
    var panel = section('checklist', '🧳 Travel Checklist');
    if (!items.length) {
      panel.appendChild(el('p', 'empty', 'No checklist items yet.'));
      return panel;
    }

    var done = items.filter(function (item) { return item.status === 'done'; }).length;
    panel.appendChild(el('p', 'trip-route', done + ' of ' + items.length + ' done'));

    var card = el('div', 'card');
    // Already ordered todo-first by the API, matching how the trip pages on the
    // main site render their checklists.
    items.forEach(function (item) {
      var row = el('div', 'check' + (item.status === 'done' ? ' check-done' : ''));
      row.appendChild(el('span', 'check-icon', item.statusIcon || ''));

      var body = el('div', 'check-desc');
      body.appendChild(document.createTextNode(item.description || ''));
      (item.countryCodes || []).forEach(function (code) {
        body.appendChild(el('span', 'chip', countryLabel(code)));
      });
      if (item.categoryLabel) body.appendChild(el('span', 'chip', item.categoryLabel));
      if (item.note) body.appendChild(el('span', 'check-note', item.note));

      row.appendChild(body);
      card.appendChild(row);
    });

    panel.appendChild(card);
    return panel;
  }

  /**
   * The spend-by-category pie, drawn by Chart.js.
   *
   * The library ships inlined in this page rather than linked from a CDN —
   * a published page has to work from a plain static host with nothing behind
   * it. Its own colours come from the category data, so a pie here matches the
   * one in the app and on the main site.
   */
  function pieChart(categories, label) {
    var wrap = el('div', 'budget-pie');
    var canvas = document.createElement('canvas');
    canvas.setAttribute('role', 'img');
    canvas.setAttribute('aria-label', label || 'Spend by category');
    wrap.appendChild(canvas);

    var total = categories.reduce(function (sum, c) { return sum + (Number(c.amount) || 0); }, 0);
    // Defensive: this file may sit on a CDN for years, and a page that cannot
    // draw its chart should still show its numbers.
    if (!total || typeof Chart === 'undefined') return wrap;

    var displayCurrency = (trip.budget || {}).displayCurrency;
    new Chart(canvas, {
      type: 'pie',
      data: {
        labels: categories.map(function (c) { return c.label; }),
        datasets: [{
          data: categories.map(function (c) { return Number(c.amount) || 0; }),
          backgroundColor: categories.map(function (c) { return c.color || '#868E96'; }),
          borderWidth: 0
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: true,
        animation: false,
        plugins: {
          // The bars beside the pie already name every category.
          legend: { display: false },
          tooltip: {
            callbacks: {
              label: function (ctx) {
                return ctx.label + ': ' + money(ctx.parsed, displayCurrency);
              }
            }
          }
        }
      }
    });

    return wrap;
  }

  // Countries are not a fixed set the way the four categories are, so the page
  // colours their slices itself, cycling this palette in slice order.
  var COUNTRY_COLOURS = ['#4C6EF5', '#F76707', '#E64980', '#12B886', '#7048E8',
                         '#FAB005', '#15AABF', '#868E96'];

  /**
   * The slices for one breakdown, in the shape pieChart and the bars want.
   *
   * `rollup` is one of budget.charged / budget.forecast — the same figures over
   * the charges alone, and over the charges plus everything still to be paid.
   * `dimension` picks category or country within it.
   */
  function budgetSlices(rollup, dimension) {
    if (dimension === 'country') {
      return list(rollup.byCountry).map(function (country, i) {
        return {
          label: country.key === 'NO_LOCATION'
            ? 'No location'
            : (country.flag ? country.flag + ' ' : '') + countryName(country.key),
          amount: Number(country.amount) || 0,
          color: COUNTRY_COLOURS[i % COUNTRY_COLOURS.length]
        };
      });
    }
    return list(rollup.byCategory).map(function (category) {
      return {
        label: (category.icon ? category.icon + ' ' : '') + category.label,
        amount: Number(category.amount) || 0,
        color: category.color
      };
    });
  }

  /**
   * A Group by key, "<dimension>" or "<dimension>-forecast", resolved into the
   * rollup it names and the dimension within it. One control choosing two
   * things, because they are one question: what am I looking at.
   */
  function budgetRollup(budget, key) {
    var forecast = key.indexOf('-forecast') > 0;
    return {
      rollup: (forecast ? budget.forecast : budget.charged) || budget.charged || {},
      dimension: key.indexOf('country') === 0 ? 'country' : 'category',
      forecast: forecast
    };
  }

  function budgetPanel() {
    var budget = trip.budget || {};
    var panel = section('budget', '💰 Budget');

    // Charged is always shipped; forecast only when the publishing account
    // asked for it, so its absence is what hides the two extra buttons.
    var charged = budget.charged || {};
    if (!list(charged.byCategory).length && !list((budget.forecast || {}).byCategory).length) {
      panel.appendChild(el('p', 'empty', 'No costs recorded yet.'));
      return panel;
    }

    var card = el('div', 'card');

    // Two buttons rather than a dropdown: this page has no form controls
    // anywhere else, and the panel toggle at the top of it already reads as a
    // row of buttons.
    var modes = el('div', 'panel-toggle budget-modes');
    modes.appendChild(el('span', 'panel-toggle-label', 'Group by'));
    var modeList = [['category', 'Category'], ['country', 'Country']];
    if (budget.forecast) {
      modeList.push(['category-forecast', 'Category (Forecast)']);
      modeList.push(['country-forecast', 'Country (Forecast)']);
    }
    modeList.forEach(function (pair) {
      var button = el('button', 'panel-btn', pair[1]);
      button.type = 'button';
      button.setAttribute('data-breakdown', pair[0]);
      modes.appendChild(button);
    });
    card.appendChild(modes);

    // Pie on the left; everything textual — total, native totals, legend —
    // together on the right, matching the reference budget panel this mirrors.
    var layout = el('div', 'budget-summary');
    var chartHolder = el('div', 'budget-pie');
    layout.appendChild(chartHolder);

    var details = el('div', 'budget-details');
    // Redrawn with the rest of the panel: a forecast total beside charged-only
    // slices would be the one way this panel can mislead.
    var totalLine = el('div', 'budget-total');
    details.appendChild(totalLine);
    var nativeLine = el('div', 'budget-native-totals');
    details.appendChild(nativeLine);

    var legend = el('ul', 'budget-legend');
    details.appendChild(legend);
    layout.appendChild(details);
    card.appendChild(layout);

    var warning = el('div', 'warn');
    card.appendChild(warning);

    function draw(mode) {
      var chosen = budgetRollup(budget, mode);
      var rollup = chosen.rollup;
      var slices = budgetSlices(rollup, chosen.dimension);

      totalLine.textContent = (chosen.forecast ? 'Forecast total: ' : 'Total: ')
        + money(rollup.total, budget.displayCurrency);

      // What was actually spent, in the currencies it was actually spent in —
      // nativeTotals already comes from the API summed and sorted, so this
      // only formats and joins.
      var nativeLabel = list(rollup.nativeTotals)
        .filter(function (n) { return Number(n.amount) > 0; })
        .map(function (n) { return money(n.amount, n.currency); })
        .join(' + ');
      nativeLine.textContent = nativeLabel ? 'Native: ' + nativeLabel : '';
      nativeLine.hidden = !nativeLabel;

      var missing = list(rollup.currenciesMissingRates);
      warning.textContent = missing.length
        ? 'Not included in the total — no exchange rate set for: ' + missing.join(', ')
        : '';
      warning.hidden = !missing.length;

      chartHolder.textContent = '';
      chartHolder.appendChild(pieChart(slices,
        (chosen.forecast ? 'Forecast spend by ' : 'Spend by ') + chosen.dimension));

      legend.textContent = '';
      var total = slices.reduce(function (sum, s) { return sum + s.amount; }, 0);
      var largest = slices.reduce(function (max, s) { return Math.max(max, s.amount); }, 0);
      slices.forEach(function (slice) {
        var row = el('li', 'budget-legend-row');

        var head = el('div', 'budget-legend-head');

        var swatch = el('span', 'budget-legend-swatch');
        swatch.style.background = slice.color || 'var(--tp-accent)';
        head.appendChild(swatch);

        head.appendChild(el('span', 'budget-legend-label', slice.label));

        var percent = total > 0 ? ((slice.amount / total) * 100).toFixed(1) + '%' : '0.0%';
        head.appendChild(el('span', 'budget-legend-percent', percent));
        head.appendChild(el('span', 'budget-legend-amount', money(slice.amount, budget.displayCurrency)));
        row.appendChild(head);

        // A thin bar under the row, sized to its own share — without it, a
        // short label and a small amount leave the row mostly empty.
        var track = el('div', 'bar-track');
        var fill = el('div', 'bar-fill');
        fill.style.width = (largest > 0 ? (slice.amount / largest) * 100 : 0) + '%';
        fill.style.background = slice.color || 'var(--tp-accent)';
        track.appendChild(fill);
        row.appendChild(track);

        legend.appendChild(row);
      });

      modes.querySelectorAll('[data-breakdown]').forEach(function (button) {
        button.setAttribute('aria-pressed',
          String(button.getAttribute('data-breakdown') === mode));
      });
    }

    modes.addEventListener('click', function (event) {
      var button = event.target.closest('[data-breakdown]');
      if (button) draw(button.getAttribute('data-breakdown'));
    });

    draw('category');

    panel.appendChild(card);
    return panel;
  }

  /**
   * `keys` may name more than one, space separated. The day timeline answers
   * to both "weather" and "itinerary" because it holds both, and which of its
   * two halves is visible is then decided per part — see show().
   */
  function section(keys, heading) {
    var panel = el('section', 'panel');
    panel.setAttribute('data-panel', keys);
    var title = el('h2', 'panel-heading', heading);
    panel.appendChild(title);
    return panel;
  }

  // ── panel toggle ──────────────────────────────────────

  // Same order the panels are mounted in below: what was packed, then what
  // the days look like, then what it cost.
  var PANELS = [
    { key: 'all', label: 'Show All' },
    { key: 'destinations', label: 'Destinations' },
    { key: 'checklist', label: 'Checklist' },
    { key: 'weather', label: 'Weather Forecast' },
    { key: 'itinerary', label: 'Itinerary' },
    { key: 'budget', label: 'Budget' }
  ];

  var ALL = 'all';

  /**
   * The same pill buttons as before, but a multi-select: the sections combine,
   * so a click toggles one in or out rather than replacing the selection.
   * Pressed state is aria-pressed, which is what a toggle button should carry
   * and what the themes already style.
   *
   * "Show All" is exclusive with the rest — pressing it clears the others, and
   * pressing another clears it. Turn everything off and it comes back, because
   * a page showing nothing at all is not a state worth being able to reach.
   */
  function toggleBar() {
    var bar = el('div', 'panel-toggle');
    bar.appendChild(el('span', 'panel-toggle-label', 'Show'));

    PANELS.forEach(function (panel) {
      var button = el('button', 'panel-btn', panel.label);
      button.type = 'button';
      button.setAttribute('data-show', panel.key);
      bar.appendChild(button);
    });

    bar.addEventListener('click', function (event) {
      var button = event.target.closest('[data-show]');
      if (!button) return;
      var key = button.getAttribute('data-show');
      if (key === ALL) { show([ALL]); return; }

      // Toggle this section against whatever is already on. Dropping the last
      // one leaves nothing chosen, which normalise() reads as All.
      var current = pressedKeys();
      show(current.indexOf(key) === -1
        ? current.concat([key])
        : current.filter(function (other) { return other !== key; }));
    });

    return bar;
  }

  /** The sections currently on, read back off the buttons. Never includes All. */
  function pressedKeys() {
    return Array.prototype.slice
      .call(document.querySelectorAll('.panel-btn[data-show]'))
      .filter(function (button) {
        return button.getAttribute('aria-pressed') === 'true'
          && button.getAttribute('data-show') !== ALL;
      })
      .map(function (button) { return button.getAttribute('data-show'); });
  }

  /**
   * The selection the page will actually honour.
   *
   * "All" wins over anything alongside it, and an empty choice means
   * everything rather than nothing — which is also what makes a stale or
   * hand-edited ?show= harmless.
   */
  function normalise(keys) {
    var known = keys.filter(function (key) {
      return PANELS.some(function (panel) { return panel.key === key; });
    });
    var sections = known.filter(function (key) { return key !== ALL; });
    return (known.indexOf(ALL) !== -1 || sections.length === 0) ? [ALL] : sections;
  }

  /**
   * Applies a selection. `keys` is a list; one entry is the common case.
   *
   * Everything downstream asks "is this key selected", so a panel that answers
   * to several keys and a part inside it are the same question at two levels.
   */
  function show(keys) {
    var chosen = normalise(keys);
    var showAll = chosen.indexOf(ALL) !== -1;
    var wanted = function (key) { return showAll || chosen.indexOf(key) !== -1; };

    document.querySelectorAll('.panel').forEach(function (panel) {
      // A panel can answer to several keys — the day timeline holds both the
      // weather and the itinerary — so this is a membership test.
      var keysOfPanel = (panel.getAttribute('data-panel') || '').split(' ');
      panel.hidden = !keysOfPanel.some(wanted);
    });

    // Within a panel that holds both, show only the halves asked for.
    document.querySelectorAll('[data-part]').forEach(function (part) {
      part.hidden = !wanted(part.getAttribute('data-part'));
    });

    // A day whose only remaining content was just hidden would otherwise leave
    // its date heading stranded over nothing.
    document.querySelectorAll('.day').forEach(function (day) {
      var visible = false;
      day.querySelectorAll('[data-part]').forEach(function (part) {
        if (!part.hidden) visible = true;
      });
      day.hidden = !visible;
    });

    // Scoped to [data-show]: the budget panel's own breakdown switch reuses
    // the same button styling, and an unscoped .panel-btn sweep would clear
    // its pressed state every time a panel was shown.
    document.querySelectorAll('.panel-btn[data-show]').forEach(function (button) {
      var key = button.getAttribute('data-show');
      button.setAttribute('aria-pressed', String(
        key === ALL ? showAll : (!showAll && chosen.indexOf(key) !== -1)));
    });

    // "🌤 Weather Forecast" rather than "🗓 Itinerary" when the weather is the
    // only half of that panel on screen. The alternative text rides on the
    // heading itself, so there is no second place listing what each key means.
    document.querySelectorAll('.panel-heading[data-heading-weather]').forEach(function (title) {
      if (!title.getAttribute('data-heading-default')) {
        title.setAttribute('data-heading-default', title.textContent);
      }
      var weatherOnly = !showAll && wanted('weather') && !wanted('itinerary');
      title.textContent = weatherOnly
        ? title.getAttribute('data-heading-weather')
        : title.getAttribute('data-heading-default');
    });

    // ?show= keeps a filtered view shareable, the way the main site's trip
    // pages do with ?show-all=true. Comma separated, and a single value still
    // reads correctly — every link shared before this was one.
    try {
      var url = new URL(window.location.href);
      if (showAll) url.searchParams.delete('show');
      else url.searchParams.set('show', chosen.join(','));
      window.history.replaceState({}, '', url);
    } catch (e) { /* file:// has no usable URL API — harmless */ }
  }

  // ── weather, looked up in the reader's browser ────────
  //
  // A published page has no API behind it and may be opened months after it
  // was rendered, so the reading cannot be baked in at publish time — it would
  // ship a forecast guaranteed to be stale. Each reader's own browser asks
  // Open-Meteo instead, which also means the load scales with readers rather
  // than concentrating on one server.
  //
  // Same two endpoints as the planner, for the same reason: /forecast only
  // serves about a fortnight either side of today and answers 400 for the
  // whole call outside it, so anything further out comes from the climate
  // model and is labelled "typical" rather than shown as a forecast.
  var FORECAST_URL = 'https://api.open-meteo.com/v1/forecast';
  var CLIMATE_URL = 'https://climate-api.open-meteo.com/v1/climate';
  var CLIMATE_MODEL = 'MRI_AGCM3_2_S';
  var HORIZON_DAYS = 14;

  var WMO = [[0,'☀️','Clear'],[1,'🌤','Mainly clear'],[2,'⛅️','Partly cloudy'],
             [3,'☁️','Overcast'],[45,'🌫','Fog'],[48,'🌫','Freezing fog'],
             [51,'🌦','Light drizzle'],[53,'🌦','Drizzle'],[55,'🌧','Heavy drizzle'],
             [61,'🌦','Light rain'],[63,'🌧','Rain'],[65,'🌧','Heavy rain'],
             [71,'🌨','Light snow'],[73,'🌨','Snow'],[75,'❄️','Heavy snow'],
             [80,'🌦','Light showers'],[81,'🌧','Showers'],[82,'⛈','Violent showers'],
             [95,'⛈','Thunderstorm'],[96,'⛈','Thunderstorm with hail'],
             [99,'⛈','Thunderstorm with hail']];

  var RAIN = [[20,'⛈','Very wet'],[10,'🌧','Wet'],[4,'🌧','Rain likely'],
              [1,'🌦','Showers likely'],[0.1,'🌤','Mostly dry'],[0,'☀️','Dry']];

  function conditionOf(code, rain) {
    var i;
    if (code != null) {
      var match = WMO[0];
      for (i = 0; i < WMO.length; i++) if (code >= WMO[i][0]) match = WMO[i];
      return { icon: match[1], label: match[2] };
    }
    if (rain != null) {
      for (i = 0; i < RAIN.length; i++) if (rain >= RAIN[i][0]) {
        return { icon: RAIN[i][1], label: RAIN[i][2] };
      }
    }
    return { icon: '', label: '' };
  }

  function daysAhead(iso) {
    var parts = String(iso).slice(0, 10).split('-');
    var date = new Date(+parts[0], +parts[1] - 1, +parts[2]);
    var today = new Date();
    today.setHours(0, 0, 0, 0);
    return Math.round((date - today) / 86400000);
  }

  function loadWeather() {
    if (!pending.length || typeof fetch !== 'function') return;

    // One request per endpoint, never one per card. Open-Meteo throttles
    // bursts, and a page with three weeks of days would otherwise fire dozens
    // of calls the moment it opened.
    var groups = { forecast: [], climate: [] };
    pending.forEach(function (slot) {
      var ahead = daysAhead(slot.date);
      groups[ahead > HORIZON_DAYS || ahead < -85 ? 'climate' : 'forecast'].push(slot);
    });

    Object.keys(groups).forEach(function (kind) {
      var slots = groups[kind];
      if (!slots.length) return;

      // De-duplicated coordinates: the same place appears on every day of a
      // stay, and asking once covers all of them.
      var byPoint = {};
      slots.forEach(function (slot) {
        var key = slot.lat.toFixed(4) + ',' + slot.lon.toFixed(4);
        (byPoint[key] = byPoint[key] || { lat: slot.lat, lon: slot.lon, slots: [] })
          .slots.push(slot);
      });
      var keys = Object.keys(byPoint);
      var dates = slots.map(function (s) { return String(s.date).slice(0, 10); }).sort();

      var url = (kind === 'forecast' ? FORECAST_URL : CLIMATE_URL)
        + '?latitude=' + keys.map(function (k) { return byPoint[k].lat; }).join(',')
        + '&longitude=' + keys.map(function (k) { return byPoint[k].lon; }).join(',')
        + '&daily=' + (kind === 'forecast'
            ? 'weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum'
            : 'temperature_2m_max,temperature_2m_min,precipitation_sum')
        + '&start_date=' + dates[0] + '&end_date=' + dates[dates.length - 1]
        + '&timezone=auto'
        + (kind === 'climate' ? '&models=' + CLIMATE_MODEL : '');

      fetch(url).then(function (res) {
        if (!res.ok) throw new Error('lookup failed');
        return res.json();
      }).then(function (body) {
        // A single coordinate answers with a bare object, several with an array.
        var locations = Object.prototype.toString.call(body) === '[object Array]'
          ? body : [body];
        keys.forEach(function (key, index) {
          var daily = (locations[index] || {}).daily;
          if (!daily || !daily.time) return fail(byPoint[key].slots);
          byPoint[key].slots.forEach(function (slot) {
            var at = daily.time.indexOf(String(slot.date).slice(0, 10));
            var max = at < 0 ? null : pick(daily.temperature_2m_max, at);
            var min = at < 0 ? null : pick(daily.temperature_2m_min, at);
            if (max == null && min == null) return fail([slot]);
            render(slot, max, min, pick(daily.precipitation_sum, at),
                   pick(daily.weather_code, at), kind);
          });
        });
      }).catch(function () { fail(slots); });
    });
  }

  function pick(column, index) {
    if (!column || index < 0 || index >= column.length) return null;
    return column[index] == null ? null : column[index];
  }

  function render(slot, max, min, rain, code, kind) {
    var cond = conditionOf(code, rain);
    var line = slot.line;
    line.className = 'weather-line';
    line.textContent = '';

    if (cond.icon) line.appendChild(el('span', 'weather-icon', cond.icon));
    var temps = max == null ? Math.round(min) + '°'
      : (min == null ? Math.round(max) + '°'
        : Math.round(max) + '° / ' + Math.round(min) + '°');
    line.appendChild(el('span', 'weather-temps', temps));
    if (cond.label) line.appendChild(el('span', 'weather-condition', cond.label));
    // "Typical" is the one that matters: a climate projection is not a
    // forecast, and a reader packing a bag needs to be told which it is.
    line.appendChild(el('span',
      kind === 'climate' ? 'weather-badge weather-badge-typical' : 'weather-badge',
      kind === 'climate' ? 'Typical' : 'Forecast'));
  }

  function fail(slots) {
    slots.forEach(function (slot) {
      slot.line.className = 'weather-line weather-line-muted';
      slot.line.textContent = daysAhead(slot.date) > HORIZON_DAYS
        ? '📅 Forecast not yet open'
        : '📅 Weather unavailable';
    });
  }

  // ── mount ─────────────────────────────────────────────

  var themes = themeBar();
  if (themes) root.appendChild(themes);
  root.appendChild(header());
  root.appendChild(toggleBar());
  root.appendChild(destinationsPanel());
  root.appendChild(checklistPanel());
  root.appendChild(itineraryPanel());
  root.appendChild(budgetPanel());

  var footer = el('div', 'footer');
  footer.textContent = trip.publishedAt
    ? 'Published ' + new Date(trip.publishedAt).toLocaleString()
    : 'Published trip plan';
  root.appendChild(footer);

  var requested = [];
  try {
    requested = (new URL(window.location.href).searchParams.get('show') || '')
      .split(',').map(function (key) { return key.trim(); }).filter(Boolean);
  } catch (e) { /* ignore */ }
  // normalise() inside show() drops anything unknown and falls back to All, so
  // a stale or hand-edited ?show= can never leave the page blank.
  show(requested);

  // Last, so a slow or blocked lookup cannot delay the page rendering.
  loadWeather();
})();
