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
      if (destination.nights) {
        name.appendChild(el('span', 'nights-badge', destination.nights + 'N'));
      }
      card.appendChild(name);

      var meta = [];
      if (destination.country) meta.push(destination.country);
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

  function itineraryPanel() {
    var days = list(trip.days);
    var panel = section('itinerary', '🗓 Itinerary');
    if (!days.length) {
      panel.appendChild(el('p', 'empty', 'Nothing planned yet.'));
      return panel;
    }

    days.forEach(function (day) {
      var block = el('div', 'day');
      block.appendChild(el('h3', 'day-date', longDate(day.date)));

      var card = el('div', 'card');
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
      if (item.destination) body.appendChild(el('span', 'chip', item.destination));
      if (item.categoryLabel) body.appendChild(el('span', 'chip', item.categoryLabel));
      if (item.note) body.appendChild(el('span', 'check-note', item.note));

      row.appendChild(body);
      card.appendChild(row);
    });

    panel.appendChild(card);
    return panel;
  }

  function budgetPanel() {
    var budget = trip.budget || {};
    var categories = list(budget.byCategory);
    var lines = list(budget.items);
    var panel = section('budget', '💰 Budget');

    if (!lines.length) {
      panel.appendChild(el('p', 'empty', 'No costs recorded yet.'));
      return panel;
    }

    var summary = el('div', 'card');
    summary.appendChild(el('div', 'budget-total', money(budget.total, budget.displayCurrency)));
    summary.appendChild(el('div', 'budget-total-label', 'Total, in ' + (budget.displayCurrency || '')));

    var largest = categories.reduce(function (max, c) {
      return Math.max(max, Number(c.amount) || 0);
    }, 0);

    categories.forEach(function (category) {
      var amount = Number(category.amount) || 0;
      var row = el('div', 'bar-row');

      var head = el('div', 'bar-head');
      head.appendChild(el('span', null, (category.icon || '') + ' ' + category.label));
      head.appendChild(el('span', null, money(amount, budget.displayCurrency)));
      row.appendChild(head);

      var track = el('div', 'bar-track');
      var fill = el('div', 'bar-fill');
      fill.style.width = (largest > 0 ? (amount / largest) * 100 : 0) + '%';
      fill.style.background = category.color || 'var(--tp-accent)';
      track.appendChild(fill);
      row.appendChild(track);

      summary.appendChild(row);
    });

    if (list(budget.currenciesMissingRates).length) {
      summary.appendChild(el('div', 'warn',
        'Not included in the total — no exchange rate set for: '
        + budget.currenciesMissingRates.join(', ')));
    }

    panel.appendChild(summary);

    var wrap = el('div', 'table-wrap');
    var table = el('table');
    var thead = el('thead');
    var headRow = el('tr');
    ['', 'Description', 'Category', 'Amount', 'In ' + (budget.displayCurrency || ''), 'Date']
      .forEach(function (label, index) {
        var th = el('th', index === 3 || index === 4 ? 'num' : null, label);
        headRow.appendChild(th);
      });
    thead.appendChild(headRow);
    table.appendChild(thead);

    var tbody = el('tbody');
    lines.forEach(function (line) {
      var row = el('tr');
      row.appendChild(el('td', null, line.icon || ''));
      row.appendChild(el('td', 'wrap', line.description || ''));
      row.appendChild(el('td', null, line.categoryLabel || ''));
      row.appendChild(el('td', 'num', money(line.amount, line.currency)));
      row.appendChild(el('td', 'num', line.converted == null ? '—' : money(line.converted)));
      row.appendChild(el('td', null, line.date ? shortDate(line.date) : ''));
      tbody.appendChild(row);
    });
    table.appendChild(tbody);
    wrap.appendChild(table);

    var tableCard = el('div', 'card');
    tableCard.appendChild(wrap);
    panel.appendChild(tableCard);
    return panel;
  }

  function section(key, heading) {
    var panel = el('section', 'panel');
    panel.setAttribute('data-panel', key);
    panel.appendChild(el('h2', 'panel-heading', heading));
    return panel;
  }

  // ── panel toggle ──────────────────────────────────────

  var PANELS = [
    { key: 'all', label: 'Show All' },
    { key: 'destinations', label: 'Destinations' },
    { key: 'itinerary', label: 'Itinerary' },
    { key: 'checklist', label: 'Checklist' },
    { key: 'budget', label: 'Budget' }
  ];

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
      if (button) show(button.getAttribute('data-show'));
    });

    return bar;
  }

  function show(key) {
    // ?show= keeps a filtered view shareable, the way the main site's trip
    // pages do with ?show-all=true.
    document.querySelectorAll('.panel').forEach(function (panel) {
      panel.hidden = key !== 'all' && panel.getAttribute('data-panel') !== key;
    });
    document.querySelectorAll('.panel-btn').forEach(function (button) {
      button.setAttribute('aria-pressed', String(button.getAttribute('data-show') === key));
    });
    try {
      var url = new URL(window.location.href);
      if (key === 'all') url.searchParams.delete('show');
      else url.searchParams.set('show', key);
      window.history.replaceState({}, '', url);
    } catch (e) { /* file:// has no usable URL API — harmless */ }
  }

  // ── mount ─────────────────────────────────────────────

  root.appendChild(header());
  root.appendChild(toggleBar());
  root.appendChild(destinationsPanel());
  root.appendChild(itineraryPanel());
  root.appendChild(checklistPanel());
  root.appendChild(budgetPanel());

  var footer = el('div', 'footer');
  footer.textContent = trip.publishedAt
    ? 'Published ' + new Date(trip.publishedAt).toLocaleString()
    : 'Published trip plan';
  root.appendChild(footer);

  var requested = 'all';
  try {
    requested = new URL(window.location.href).searchParams.get('show') || 'all';
  } catch (e) { /* ignore */ }
  show(PANELS.some(function (p) { return p.key === requested; }) ? requested : 'all');
})();
