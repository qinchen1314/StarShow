import { test } from 'node:test';
import assert from 'node:assert/strict';
import { SeatStock, retryDelay } from './seat-stock-model.mjs';

const time = (seconds) => `2026-11-22T20:00:${String(seconds).padStart(2, '0')}+08:00`;
const stock = (version, seconds, state, available, extra = {}) => ({
  saleConfigVersion: version, asOf: time(seconds), stale: false, entryState: state,
  tiers: [{ tierId: '9007199254740993', available, stockState: state }], ...extra,
});
const map = (version, seconds, selectable, stockState = 'OPEN', zoneId = 'A') => ({
  layoutVersion: version, asOf: time(seconds), stockState, tierId: '9007199254740993', zoneId,
  seats: selectable.map((value, seatIndex) => ({
    seatIndex, rowNo: 1, colNo: seatIndex + 1, segmentId: 'L', selectable: value,
  })),
});

test('two clients see the same released seat and reject replayed sold-out snapshots', () => {
  for (const client of [new SeatStock(), new SeatStock()]) {
    client.acceptStock(stock(1, 1, 'SOLD_OUT', 0));
    client.acceptMap(map(1, 1, [false, false], 'SOLD_OUT'));
    assert.equal(client.view('A').state, 'SOLD_OUT');
    client.acceptStock(stock(1, 2, 'OPEN', 1, { stale: true }));
    client.acceptMap(map(1, 2, [false, true]));
    assert.equal(client.view('A').state, 'SOLD_OUT');
    client.acceptStock(stock(1, 3, 'OPEN', 1));
    assert.deepEqual(client.view('A').released.map(seat => seat.seatIndex), [1]);
    client.acceptStock(stock(1, 1, 'SOLD_OUT', 0));
    client.acceptMap(map(1, 1, [false, false], 'SOLD_OUT'));
    assert.equal(client.view('A').state, 'OPEN');
    assert.equal(client.view('A').tier.available, 1);
  }
});

test('the newest version wins across zones and invalidates selection in every old zone', () => {
  const client = new SeatStock();
  client.acceptStock(stock(1, 2, 'OPEN', 2));
  client.acceptMap(map(1, 2, [true, true]));
  client.toggle('A', 0);
  client.acceptMap(map(1, 2, [true], 'OPEN', 'B'));
  client.toggle('B', 0);
  assert.deepEqual(client.view('A').selected.map(seat => seat.seatIndex), [0]);
  client.acceptMap(map(2, 3, [true], 'OPEN', 'B'));
  client.acceptStock(stock(1, 4, 'OPEN', 2));
  client.acceptMap(map(1, 4, [true, true]));
  assert.equal(client.view('A').state, 'UPDATING');
  assert.equal(client.view('B').state, 'UPDATING');
  assert.deepEqual(client.view('B').selected, []);
  client.acceptStock(stock(2, 3, 'OPEN', 1));
  assert.equal(client.view('B').state, 'OPEN');
});

test('partial loss is unavailable, sale gates take precedence and newer occupancy marks only conflicting seats', () => {
  const client = new SeatStock();
  client.acceptStock(stock(1, 1, 'PREPARING', null));
  client.acceptMap(map(1, 1, [true, true]));
  assert.equal(client.view('A').state, 'PREPARING');
  client.acceptStock(stock(1, 2, 'STOCK_UNAVAILABLE', null));
  assert.equal(client.view('A').state, 'STOCK_UNAVAILABLE');
  client.acceptStock(stock(1, 3, 'OPEN', 2));
  client.toggle('A', 0);
  client.toggle('A', 1);
  client.markConflict('A', 1, [1]);
  client.acceptStock(stock(1, 4, 'OPEN', 2));
  assert.deepEqual(client.view('A').selected.map(seat => seat.seatIndex), [0]);
  assert.deepEqual(client.view('A').conflicts.map(seat => seat.seatIndex), [1]);
  client.acceptMap(map(1, 4, [false, false], 'SOLD_OUT'));
  assert.deepEqual(client.view('A').selected.map(seat => seat.seatIndex), [0]);
  client.acceptStock(stock(1, 5, 'OPEN', 1));
  client.acceptMap(map(1, 5, [false, true]));
  assert.deepEqual(client.view('A').conflicts.map(seat => seat.seatIndex), [0]);
  client.acceptStock(stock(1, 6, 'ENDED', null));
  client.acceptMap(map(1, 7, [true, true]));
  assert.equal(client.view('A').state, 'ENDED');
  assert.deepEqual(client.view('A').released, []);
});

test('Retry-After seconds or HTTP date is a floor on bounded exponential backoff', () => {
  const now = Date.parse('2026-11-22T12:00:00Z');
  assert.equal(retryDelay('2', 1, now), 2000);
  assert.equal(retryDelay('Sun, 22 Nov 2026 12:00:45 GMT', 3, now), 45000);
  assert.equal(retryDelay(null, 4, now), 8000);
  assert.equal(retryDelay('broken', 30, now), 30000);
});

test('a delayed release cannot reopen the just-ended sale', () => {
  const client = new SeatStock();
  client.acceptStock(stock(1, 1, 'SOLD_OUT', 0));
  client.acceptMap(map(1, 1, [false], 'SOLD_OUT'));
  client.updateSaleClock({ saleConfigVersion: 1, serverTime: time(1), saleEndTime: time(3) }, performance.now() - 2500);

  client.acceptStock(stock(1, 2, 'OPEN', 1));
  client.acceptMap(map(1, 2, [true]));
  assert.equal(client.view('A').state, 'ENDED');
  assert.deepEqual(client.view('A').released, []);
});

test('reopening after a sellout requires newer stock and zone snapshots', () => {
  const client = new SeatStock();
  client.acceptStock(stock(1, 1, 'OPEN', 1));
  client.acceptMap(map(1, 1, [true]));
  client.acceptStock(stock(1, 2, 'SOLD_OUT', 0));
  client.acceptStock(stock(1, 3, 'OPEN', 1));
  client.acceptMap(map(1, 1, [true]));
  assert.equal(client.view('A').state, 'SOLD_OUT');
  assert.deepEqual(client.view('A').released, []);
  client.acceptMap(map(1, 3, [true]));
  assert.equal(client.view('A').state, 'OPEN');
  assert.deepEqual(client.view('A').released.map(seat => seat.seatIndex), [0]);
});

test('an order layout rejection clears selection and can recover on the same server version', () => {
  const client = new SeatStock();
  client.acceptStock(stock(1, 1, 'OPEN', 1));
  client.acceptMap(map(1, 1, [true]));
  client.toggle('A', 0);
  client.invalidateLayout();
  assert.deepEqual(client.view('A').selected, []);
  assert.equal(client.view('A').state, 'UPDATING');
  client.acceptMap(map(1, 1, [true]));
  assert.equal(client.view('A').state, 'UPDATING');
  client.acceptMap(map(1, 2, [true]));
  assert.equal(client.view('A').state, 'OPEN');
});
