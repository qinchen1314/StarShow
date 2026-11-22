const saleGates = new Set(['ENDED', 'PREPARING']);

function isNewer(current, versionField, snapshot) {
  const version = snapshot[versionField];
  const time = Date.parse(snapshot.asOf);
  if (!Number.isInteger(version) || version < 1 || !Number.isFinite(time)) return false;
  return !current || version > current[versionField]
    || (version === current[versionField] && time > Date.parse(current.asOf));
}

export class SeatStock {
  version = 0;
  endAt = Infinity;
  stock = null;
  stockWatermark = null;
  tierSoldOutAt = new Map();
  zones = new Map();

  observeVersion(version) {
    if (Number.isInteger(version) && version > this.version) {
      this.version = version;
      this.endAt = Infinity;
      this.stock = null;
      this.stockWatermark = null;
      this.tierSoldOutAt.clear();
      this.zones.clear();
    }
  }

  updateSaleClock(detail, receivedAt = performance.now()) {
    const { saleConfigVersion, serverTime, saleEndTime } = detail;
    if (saleConfigVersion < this.version) return;
    this.observeVersion(saleConfigVersion);
    const remaining = Date.parse(saleEndTime) - Date.parse(serverTime);
    if (Number.isFinite(remaining)) this.endAt = Math.min(this.endAt, receivedAt + remaining);
  }

  saleEnded() {
    return performance.now() >= this.endAt || this.stock?.entryState === 'ENDED';
  }

  acceptStock(snapshot) {
    if (snapshot.saleConfigVersion < this.version
        || !isNewer(this.stockWatermark, 'saleConfigVersion', snapshot)) return 'IGNORED';
    this.observeVersion(snapshot.saleConfigVersion);
    this.stockWatermark = snapshot;
    if (snapshot.stale) return 'STALE';
    this.stock = snapshot;
    if (!saleGates.has(snapshot.entryState)) {
      for (const tier of snapshot.tiers) {
        if (tier.stockState === 'SOLD_OUT' && tier.available === 0) {
          this.tierSoldOutAt.set(String(tier.tierId), Date.parse(snapshot.asOf));
        }
      }
    }
    for (const zone of this.zones.values()) this.reconcile(zone);
    return 'ACCEPTED';
  }

  acceptMap(snapshot) {
    const prior = this.zones.get(snapshot.zoneId);
    if (snapshot.layoutVersion < this.version || !isNewer(prior?.map, 'layoutVersion', snapshot)) return false;
    this.observeVersion(snapshot.layoutVersion);
    const zone = this.zones.get(snapshot.zoneId) ?? {
      selected: new Set(), conflicts: new Set(), latestAvailability: null, released: new Set(), soldOutAt: -Infinity,
    };
    zone.map = snapshot;
    if (snapshot.stockState === 'SOLD_OUT') zone.soldOutAt = Date.parse(snapshot.asOf);
    zone.awaitingLayout = false;
    zone.reportedConflicts = new Set();
    this.zones.set(snapshot.zoneId, zone);
    this.reconcile(zone);
    return true;
  }

  tierState(tierId) {
    const tier = this.stock?.tiers.find(item => String(item.tierId) === String(tierId));
    if (this.saleEnded()) return { state: 'ENDED', tier };
    if (saleGates.has(this.stock?.entryState)) return { state: this.stock.entryState, tier };
    if (!this.stock) return { state: 'UPDATING', tier };
    if (tier?.stockState === 'OPEN' && tier.available > 0) return { state: 'OPEN', tier };
    if (tier?.stockState === 'SOLD_OUT' && tier.available === 0) return { state: 'SOLD_OUT', tier };
    return { state: 'STOCK_UNAVAILABLE', tier };
  }

  zoneState(zone) {
    const { map } = zone;
    const { state } = this.tierState(map.tierId);
    if (map.stockState === 'ENDED') return 'ENDED';
    if (saleGates.has(state)) return state;
    if (saleGates.has(map.stockState)) return map.stockState;
    if (state !== 'OPEN') return state;
    if (map.stockState === 'SOLD_OUT') return 'SOLD_OUT';
    if (map.stockState !== 'OPEN') return 'STOCK_UNAVAILABLE';
    if (zone.awaitingLayout) return 'UPDATING';
    const soldOutAt = Math.max(zone.soldOutAt, this.tierSoldOutAt.get(String(map.tierId)) ?? -Infinity);
    if (Date.parse(map.asOf) <= soldOutAt || Date.parse(this.stock.asOf) <= soldOutAt) return 'SOLD_OUT';
    return map.seats.some(seat => seat.selectable) ? 'OPEN' : 'ZONE_FULL';
  }

  reconcile(zone) {
    const state = this.zoneState(zone);
    if (!['OPEN', 'ZONE_FULL', 'SOLD_OUT'].includes(state)) {
      zone.released.clear();
      return;
    }
    if (state === 'SOLD_OUT') {
      zone.latestAvailability = new Set();
      zone.released.clear();
      return;
    }
    const previous = zone.latestAvailability;
    const selectable = new Set(zone.map.seats
      .filter(seat => seat.selectable && !zone.reportedConflicts.has(seat.seatIndex))
      .map(seat => seat.seatIndex));
    for (const index of zone.selected) {
      if (!selectable.has(index)) {
        zone.selected.delete(index);
        zone.conflicts.add(index);
      }
    }
    for (const index of zone.conflicts) if (selectable.has(index)) zone.conflicts.delete(index);
    for (const index of zone.released) if (!selectable.has(index)) zone.released.delete(index);
    if (previous) {
      for (const index of selectable) if (!previous.has(index)) zone.released.add(index);
    }
    zone.latestAvailability = selectable;
  }

  view(zoneId) {
    const zone = this.zones.get(zoneId);
    if (!zone) {
      const gate = this.saleEnded() ? 'ENDED' : this.stock?.entryState;
      return { state: saleGates.has(gate) ? gate : 'UPDATING',
        map: null, tier: null, selected: [], conflicts: [], released: [] };
    }
    const state = this.zoneState(zone);
    const seats = zone.map.seats;
    return {
      state, map: zone.map, tier: this.tierState(zone.map.tierId).tier,
      selected: seats.filter(seat => zone.selected.has(seat.seatIndex)),
      conflicts: seats.filter(seat => zone.conflicts.has(seat.seatIndex)),
      released: state === 'OPEN' ? seats.filter(seat => zone.released.has(seat.seatIndex)) : [],
    };
  }

  toggle(zoneId, index) {
    const zone = this.zones.get(zoneId);
    if (!zone || this.zoneState(zone) !== 'OPEN') return;
    if (zone.selected.delete(index)) return;
    if (zone.selected.size < 10 && !zone.conflicts.has(index)
        && zone.map.seats.some(seat => seat.seatIndex === index && seat.selectable)) {
      zone.selected.add(index);
    }
  }

  markConflict(zoneId, layoutVersion, indices) {
    const zone = this.zones.get(zoneId);
    if (!zone || zone.map.layoutVersion !== layoutVersion) return;
    for (const index of indices) {
      if (zone.selected.delete(index)) {
        zone.conflicts.add(index);
        zone.reportedConflicts.add(index);
        zone.latestAvailability?.delete(index);
        zone.released.delete(index);
      }
    }
  }

  invalidateLayout() {
    for (const zone of this.zones.values()) {
      zone.awaitingLayout = true;
      zone.selected.clear();
      zone.conflicts.clear();
      zone.reportedConflicts.clear();
      zone.released.clear();
      zone.latestAvailability = null;
    }
  }
}

export function retryDelay(header, failureCount, now = Date.now()) {
  const value = header?.trim();
  let minimum = 0;
  if (/^\d+$/.test(value)) minimum = Number(value) * 1000;
  else if (value) minimum = Date.parse(value) - now || 0;
  return Math.max(minimum, Math.min(30000, 1000 * 2 ** Math.max(0, failureCount - 1)));
}
