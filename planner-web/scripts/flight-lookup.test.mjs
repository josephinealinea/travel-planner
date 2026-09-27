import { test } from 'node:test';
import assert from 'node:assert/strict';

const { flightLookup, blankFlight, flightFromRecord } = await import('../js/pages/trip/flight-lookup.js');

const found = { status: 'found', departureTime: '10:00', arrivalDate: '2026-10-02', arrivalTime: '06:00',
  flight: { number: 'KL2842', from: { iata: 'AMS' }, to: { iata: 'LIM' } } };
const form = (o = {}) => ({ ...blankFlight(), startDate: '2026-10-01', startTime: '', endDate: '', endTime: '', flightNumber: 'KL2842', ...o });
const host = (reply) => ({ ...flightLookup(), trip: { id: 't' }, api: { lookupFlight: () => reply } });

test('flightCanLookup needs number and date', () => {
  const h = flightLookup();
  assert.equal(h.flightCanLookup(form()), true);
  assert.equal(h.flightCanLookup(form({ flightNumber: ' ' })), false);
  assert.equal(h.flightCanLookup(form({ startDate: '' })), false);
});

test('lookup fills only blank fields', async () => {
  const f = form({ startTime: '09:00' });
  await host(Promise.resolve(found)).flightLookup(f);
  assert.equal(f.startTime, '09:00');
  assert.equal(f.endDate, '2026-10-02');
  assert.equal(f.endTime, '06:00');
  assert.equal(f.flightStatus, 'found');
  assert.equal(f.flightTouched, true);
});

test('a stale response changes nothing', async () => {
  const f = form();
  let resolve;
  const h = host(new Promise((r) => { resolve = r; }));
  const pending = h.flightLookup(f);
  f.flightNumber = 'KL9';
  h.flightChanged(f, true);
  resolve(found);
  await pending;
  assert.equal(f.flightSnapshot, null);
  assert.equal(f.startTime, '');
  assert.equal(f.flightStatus, 'idle');
});

test('payload has three answers', () => {
  const h = flightLookup();
  assert.deepEqual(h.flightPayload(form(), true), {});
  assert.deepEqual(h.flightPayload(form({ flightNumber: '', flightTouched: true, flightHad: true }), true), { flight: { number: '' } });
  assert.deepEqual(h.flightPayload(form({ flightTouched: true, flightSnapshot: found.flight }), true), { flight: found.flight });
  assert.deepEqual(h.flightPayload(form({ flightTouched: true }), false), {});
});

test('record round trip', () => {
  assert.equal(flightFromRecord({}).flightHad, false);
  assert.equal(flightFromRecord({ flight: found.flight }).flightHad, true);
});
