import { SeatStock, retryDelay } from './seat-stock-model.mjs';

const ui = Object.fromEntries([...document.querySelectorAll('[id]')].map(element => [element.id, element]));
const showIdPattern = /^[1-9]\d{0,18}$/;
const messages = {
  OPEN: ['可以选座', '选择同一排、同一连续区域内相邻的座位。'],
  SOLD_OUT: ['当前票档售罄', '关单或退票可能释放座位，可继续关注余票。'],
  ZONE_FULL: ['本区暂无可选座位', '可切换分区或继续关注余票。'],
  STOCK_UNAVAILABLE: ['余票暂不可用', '已选座位会保留，恢复后可继续。'],
  PREPARING: ['尚未开售', '开售后刷新即可查看可选座位。'],
  ENDED: ['售票已结束', '该场次的座位已停止销售。'],
  UPDATING: ['正在确认座位', '库存与座位图更新后即可继续。'],
};
const zoneLabels = { PREPARING: '未开售', ENDED: '已停售', SOLD_OUT: '售罄',
  STOCK_UNAVAILABLE: '暂不可用', UPDATING: '待确认' };
const seatLabels = { free: '可选', selected: '已选', blocked: '不可选', conflict: '有冲突', released: '重新可选' };
const locationParams = new URLSearchParams(window.location.search);
const showPath = (showId) => `/shows/${showId}`;
const seatLabel = (seat) => `${seat.rowNo} 排 ${seat.colNo} 座`;
const money = (cents) => {
  const amount = BigInt(cents);
  return `¥${amount / 100n}.${String(amount % 100n).padStart(2, '0')}`;
};

let current;
let token;
let submitting = false;
const checkedOrders = new Set();

async function api(path, { signal, body, authorization } = {}) {
  const timeout = AbortSignal.timeout(12000);
  const result = await fetch(`/api/v1${path}`, {
    cache: 'no-store',
    signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
    method: body ? 'POST' : 'GET',
    headers: {
      Accept: 'application/json',
      ...(body && { 'Content-Type': 'application/json' }),
      ...(authorization && { Authorization: `Bearer ${authorization}` }),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const payload = await result.json().catch(() => null);
  if (result.ok && ['00000', '00202'].includes(payload?.code)) return payload.data;
  throw Object.assign(new Error(payload?.message ?? '请求暂不可用，请稍后刷新。'), {
    code: payload?.code, status: result.status, data: payload?.data, retryAfter: result.headers.get('Retry-After'),
  });
}

function button(label) {
  const element = document.createElement('button');
  element.type = 'button';
  element.textContent = label;
  return element;
}

function retryAt(page) {
  return Math.max(page.stockFailure?.at ?? 0, page.zoneFailures.get(page.zone)?.at ?? 0);
}

function readFailure(error, previous) {
  const count = (previous?.count ?? 0) + 1;
  return { count, at: Date.now() + retryDelay(error.retryAfter, count) };
}

function renderZones(page) {
  const focus = document.activeElement?.dataset.zone;
  ui.zones.replaceChildren(...page.zones.map(zone => {
    const element = button(zone.tierName);
    const { state, tier } = page.model.tierState(zone.tierId);
    const hint = document.createElement('small');
    const gated = ['PREPARING', 'ENDED'].includes(state);
    const unavailable = page.stockFailure || (state === 'OPEN' && page.zoneFailures.has(zone.zoneId));
    if (gated) hint.textContent = zoneLabels[state];
    else if (unavailable) hint.textContent = '暂不可用';
    else hint.textContent = state === 'OPEN' ? `余 ${tier.available} 张` : zoneLabels[state];
    element.dataset.zone = zone.zoneId;
    element.setAttribute('aria-pressed', String(zone.zoneId === page.zone));
    element.disabled = submitting;
    element.append(hint);
    return element;
  }));
  if (focus) [...ui.zones.children].find(item => item.dataset.zone === focus)?.focus();
}

function renderSeats(view, selectable) {
  const focus = document.activeElement?.dataset.seat;
  const selected = new Set(view.selected.map(seat => seat.seatIndex));
  const conflicts = new Set(view.conflicts.map(seat => seat.seatIndex));
  const released = new Set(view.released.map(seat => seat.seatIndex));
  const seats = [...(view.map?.seats ?? [])].sort((a, b) => a.rowNo - b.rowNo || a.colNo - b.colNo);
  const rows = [];
  let previous;
  let row;
  let focusTarget;
  for (const seat of seats) {
    if (seat.rowNo !== previous?.rowNo) {
      row = document.createElement('div');
      row.className = 'seat-row';
      const label = document.createElement('span');
      label.className = 'row-label';
      label.textContent = `第 ${seat.rowNo} 排`;
      row.append(label);
      rows.push(row);
    }
    const element = button(String(seat.colNo));
    const active = selected.has(seat.seatIndex);
    const conflict = conflicts.has(seat.seatIndex);
    const enabled = selectable && seat.selectable && !conflict;
    let state = 'blocked';
    if (conflict) state = 'conflict';
    else if (active) state = 'selected';
    else if (enabled) state = released.has(seat.seatIndex) ? 'released' : 'free';
    element.disabled = !enabled;
    element.className = 'seat';
    if (seat.rowNo === previous?.rowNo && seat.segmentId !== previous.segmentId) element.classList.add('aisle');
    element.dataset.seat = seat.seatIndex;
    element.dataset.state = state;
    element.setAttribute('aria-label', `${seatLabel(seat)}，${seatLabels[state]}`);
    element.setAttribute('aria-pressed', String(active));
    if (focus === element.dataset.seat) focusTarget = element;
    row.append(element);
    previous = seat;
  }
  ui.layout.hidden = rows.length === 0;
  ui['seat-rows'].replaceChildren(...rows);
  if (focus !== undefined) (focusTarget && !focusTarget.disabled ? focusTarget : ui.layout.querySelector('.map-scroll')).focus();
}

function selectionIssue(view) {
  const seats = [...view.selected].sort((a, b) => a.colNo - b.colNo);
  if (seats.some((seat, index) => index > 0 && (seat.rowNo !== seats[0].rowNo
      || seat.segmentId !== seats[0].segmentId || seat.colNo !== seats[index - 1].colNo + 1))) {
    return '请在同一排、同一连续区域选择相邻座位。';
  }
  if (view.state === 'OPEN' && seats.length > view.tier.available) return '当前余票不足，请减少所选座位。';
  return '';
}

function render(page) {
  ui.picker.hidden = !page.detail;
  if (!page.detail) return;
  const view = page.model.view(page.zone);
  let state = view.state;
  if ((page.stockFailure || page.zoneFailures.has(page.zone)) && !['ENDED', 'PREPARING'].includes(state)) {
    state = 'STOCK_UNAVAILABLE';
  }
  if (state === 'OPEN' && page.model.version !== page.detail.saleConfigVersion) state = 'UPDATING';
  const [title, hint] = messages[state];
  const waitSeconds = Math.max(0, Math.ceil((retryAt(page) - Date.now()) / 1000));
  ui['stock-state'].textContent = page.zone ? title : '该场次暂无可查询分区';
  ui['stock-hint'].textContent = waitSeconds ? `${hint} ${waitSeconds} 秒后可刷新。` : hint;
  ui['stock-hint'].hidden = !page.zone;
  ui.released.hidden = state !== 'OPEN' || !view.released.length;
  ui.released.textContent = view.released.length
    ? `${view.released.slice(0, 3).map(seatLabel).join('、')}${view.released.length > 3 ? `等 ${view.released.length} 个座位` : ''}重新可选。`
    : '';
  ui.conflict.hidden = !view.conflicts.length && !page.layoutChanged;
  ui.conflict.textContent = page.layoutChanged ? '座位布局已更新，请重新选座。'
    : `${view.conflicts.map(seatLabel).join('、')}不可选，请重新选择这些座位。`;
  ui.refresh.disabled = page.loading || waitSeconds > 0 || submitting;
  ui.refresh.textContent = page.loading ? '刷新中…' : '刷新余票';
  ui.watch.disabled = !page.zone || state === 'ENDED';
  ui.watch.setAttribute('aria-pressed', String(page.watching && state !== 'ENDED'));
  ui.watch.textContent = page.watching && state !== 'ENDED' ? '停止关注余票' : '关注余票';
  ui.selected.textContent = view.selected.length ? view.selected.map(seatLabel).join('、') : '尚未选择座位';
  const zone = page.zones.find(item => item.zoneId === page.zone);
  const issue = selectionIssue(view);
  ui['selection-hint'].textContent = issue || (view.selected.length && zone
    ? `票面合计 ${money(BigInt(zone.priceCent) * BigInt(view.selected.length))}` : '');
  ui['selection-hint'].hidden = !ui['selection-hint'].textContent;
  const activeOrder = checkedOrders.has(page.showId);
  ui['sign-in'].hidden = Boolean(token) || (!view.selected.length && !activeOrder);
  ui['submit-order'].hidden = !token || !view.selected.length || activeOrder;
  ui['submit-order'].disabled = state !== 'OPEN' || page.loading || submitting || Boolean(issue);
  ui['check-order'].hidden = !activeOrder || !token;
  ui['check-order'].disabled = submitting;
  ui['order-status'].hidden = !page.orderMessage;
  ui['order-status'].textContent = page.orderMessage;
  renderZones(page);
  renderSeats(view, state === 'OPEN' && !submitting);
}

function acceptDetail(page, detail) {
  if (detail.saleConfigVersion < page.model.version) return;
  page.model.updateSaleClock(detail);
  page.detail = detail;
  ui['show-title'].textContent = detail.title;
  ui['lookup-status'].textContent = [detail.city, detail.venue].filter(Boolean).join(' · ');
  const versionedZones = new Map();
  for (const tier of detail.tiers) if (tier.zoneId) versionedZones.set(tier.zoneId, tier);
  page.zones = [...versionedZones.values()];
  if (!versionedZones.has(page.zone)) page.zone = page.zones[0]?.zoneId;
  scheduleSaleEnd(page);
}

function scheduleSaleEnd(page) {
  clearTimeout(page.saleTimer);
  const remaining = page.model.endAt - performance.now();
  if (!Number.isFinite(remaining) || remaining <= 0) return;
  page.saleTimer = setTimeout(() => {
    if (page !== current) return;
    render(page);
    schedule(page);
    scheduleSaleEnd(page);
  }, Math.min(Math.ceil(remaining), 2147483647));
}

function schedule(page) {
  clearTimeout(page.timer);
  if (document.hidden) return;
  const wait = retryAt(page) - Date.now();
  const { state } = page.model.view(page.zone);
  if (wait > 0) {
    page.timer = setTimeout(() => {
      if (page !== current) return;
      render(page);
      if (page.watching && state !== 'ENDED') void refresh(page);
    }, Math.min(wait, 2147483647));
  } else if (page.watching && state !== 'ENDED') {
    page.timer = setTimeout(() => void refresh(page), 10000);
  }
}

async function refresh(page) {
  if (page !== current || submitting || page.loading || Date.now() < retryAt(page)) return;
  clearTimeout(page.timer);
  const controller = new AbortController();
  page.request = controller;
  const { signal } = controller;
  const path = showPath(page.showId);
  const beforeVersion = page.model.version;
  page.loading = true;
  render(page);
  let readingZone;
  try {
    if (!page.detail || page.reloadDetail) {
      const detail = await api(path, { signal });
      if (signal.aborted) return;
      acceptDetail(page, detail);
      page.reloadDetail = false;
    }
    if (signal.aborted) return;
    const stock = await api(`${path}/stock`, { signal });
    if (signal.aborted) return;
    const stockResult = page.model.acceptStock(stock);
    if (stockResult === 'STALE') throw new Error('库存快照正在更新。');
    if (stockResult === 'ACCEPTED') page.stockFailure = null;
    if (page.detail.saleConfigVersion < page.model.version) {
      const detail = await api(path, { signal });
      if (signal.aborted) return;
      acceptDetail(page, detail);
    }
    if (signal.aborted) return;
    if (page.zone) {
      readingZone = page.zone;
      const zone = await api(`${path}/zones/${encodeURIComponent(readingZone)}/seats`, { signal });
      if (signal.aborted) return;
      page.model.acceptMap(zone);
      if (page.model.view(readingZone).state === 'STOCK_UNAVAILABLE') {
        throw new Error('该分区余票暂不可用。');
      }
      page.zoneFailures.delete(readingZone);
      readingZone = undefined;
      if (page.detail.saleConfigVersion < page.model.version) {
        const detail = await api(path, { signal });
        if (signal.aborted) return;
        acceptDetail(page, detail);
      }
    }
  } catch (error) {
    if (!signal.aborted && current === page) {
      if (readingZone) {
        page.zoneFailures.set(readingZone, readFailure(error, page.zoneFailures.get(readingZone)));
      } else {
        page.stockFailure = readFailure(error, page.stockFailure);
      }
      if (!page.detail) ui['lookup-status'].textContent = error.message;
    }
  } finally {
    if (!signal.aborted && current === page) {
      page.loading = false;
      if (beforeVersion > 0 && page.model.version > beforeVersion) page.layoutChanged = true;
      render(page);
      schedule(page);
    }
  }
}

function open(showId, zone) {
  if (submitting || !showIdPattern.test(showId)) return;
  if (current?.showId === showId) {
    void refresh(current);
    return;
  }
  current?.request?.abort();
  clearTimeout(current?.timer);
  clearTimeout(current?.saleTimer);
  current = { showId, zone, zones: [], model: new SeatStock(), zoneFailures: new Map() };
  ui['show-title'].textContent = '演出选座';
  ui['lookup-status'].textContent = '查询场次中…';
  void refresh(current);
}

ui.lookup.addEventListener('submit', event => {
  event.preventDefault();
  open(ui['show-id'].value.trim());
});
ui.zones.addEventListener('click', event => {
  const zone = event.target.closest('[data-zone]')?.dataset.zone;
  if (submitting || !zone || current.zone === zone) return;
  current.request?.abort();
  current.loading = false;
  current.zone = zone;
  render(current);
  if (Date.now() >= retryAt(current)) void refresh(current);
  else schedule(current);
});
ui['seat-rows'].addEventListener('click', event => {
  const seat = event.target.closest('[data-seat]');
  if (!seat || seat.disabled) return;
  current.model.toggle(current.zone, Number(seat.dataset.seat));
  current.layoutChanged = false;
  render(current);
});
ui.refresh.addEventListener('click', () => void refresh(current));
ui.watch.addEventListener('click', () => {
  current.watching = !current.watching;
  render(current);
  if (current.watching && Date.now() >= retryAt(current)) void refresh(current);
  else schedule(current);
});
document.addEventListener('visibilitychange', () => {
  if (!current) return;
  if (document.hidden) clearTimeout(current.timer);
  else if (current.watching && current.model.view(current.zone).state !== 'ENDED' && Date.now() >= retryAt(current)) {
    void refresh(current);
  } else {
    render(current);
    schedule(current);
  }
});

ui['sign-in'].addEventListener('submit', async event => {
  event.preventDefault();
  const page = current;
  const form = event.currentTarget;
  const submit = form.querySelector('button');
  const body = Object.fromEntries(new FormData(form));
  form.reset();
  submit.disabled = true;
  try {
    token = (await api('/auth/login', { body })).token;
    page.orderMessage = '已登录，可提交所选座位。';
  } catch (error) {
    page.orderMessage = error.message;
  } finally {
    submit.disabled = false;
    render(current);
  }
});

ui['submit-order'].addEventListener('click', async () => {
  const page = current;
  const view = page.model.view(page.zone);
  const zone = page.zones.find(item => item.zoneId === page.zone);
  if (ui['submit-order'].disabled || view.state !== 'OPEN' || !view.selected.length || !zone) return;
  submitting = true;
  render(page);
  let sent = false;
  let recheck = false;
  try {
    const authorization = token;
    const { showId } = page;
    const { layoutVersion } = view.map;
    const saleConfigVersion = page.model.version;
    const { seckillToken } = await api('/seckill/token', { authorization, body: { showId, saleConfigVersion } });
    const body = {
      showId, tierId: String(zone.tierId), qty: view.selected.length, zoneId: page.zone,
      seatIndices: view.selected.map(seat => seat.seatIndex), layoutVersion: String(layoutVersion),
      saleConfigVersion, expectedUnitPriceCent: String(zone.priceCent), seckillToken,
      clientRequestId: crypto.randomUUID(),
    };
    sent = true;
    const order = await api('/seckill/orders', { authorization, body });
    checkedOrders.add(showId);
    page.orderMessage = order.status === 'PENDING_CONFIRM' || order.status === 'SUBMITTING'
      ? `订单 ${order.orderNo} 正在确认。` : `订单 ${order.orderNo} 已创建。`;
    recheck = true;
  } catch (error) {
    page.orderMessage = error.message;
    if (error.code === 'A0204') {
      const data = error.data;
      if (String(data?.showId) === page.showId && data.zoneId === page.zone
          && String(data.layoutVersion) === String(view.map.layoutVersion)) {
        page.model.markConflict(page.zone, view.map.layoutVersion, data.conflictSeatIndices ?? []);
      }
      recheck = true;
    } else if (error.code === 'A0205') {
      page.model.invalidateLayout();
      page.layoutChanged = true;
      page.reloadDetail = true;
      recheck = true;
    } else if (error.code === 'A0301' || error.code === 'A0202') {
      recheck = true;
    } else if (error.code === 'A0308') {
      page.reloadDetail = true;
      recheck = true;
    } else if (sent && (!error.code || error.status >= 500 || error.code === 'A0302')) {
      checkedOrders.add(page.showId);
      page.orderMessage = '订单结果待确认，请查询本场订单。';
    }
    if (error.status === 401) token = null;
  } finally {
    submitting = false;
    render(page);
    if (recheck) void refresh(page);
    else schedule(page);
  }
});

ui['check-order'].addEventListener('click', async () => {
  const page = current;
  const button = ui['check-order'];
  button.disabled = true;
  try {
    const result = await api(`/orders/current?showId=${page.showId}`, { authorization: token });
    const legacyClear = result.canStartNew === undefined && result.hasOrder === false && result.retryable === true;
    if (result.canStartNew === true || legacyClear) {
      checkedOrders.delete(page.showId);
      page.orderMessage = '本场暂无有效订单，可以重新选择。';
    } else if (result.hasOrder && result.orderNo) {
      const labels = { SUBMITTING: '提交中', PENDING_CONFIRM: '确认中', WAIT_PAY: '待支付',
        PAID: '已支付', TICKETED: '已出票', CLOSED: '已关闭', REFUNDING: '退款中', REFUNDED: '已退款' };
      page.orderMessage = `订单 ${result.orderNo} · ${labels[result.status] ?? '状态待确认'}`;
    } else {
      page.orderMessage = '本场订单仍在确认，请稍后查询。';
    }
  } catch (error) {
    page.orderMessage = error.message;
    if (error.status === 401) token = null;
  } finally {
    if (current === page) render(page);
  }
});

const initialShow = locationParams.get('showId');
if (showIdPattern.test(initialShow ?? '')) {
  ui['show-id'].value = initialShow;
  open(initialShow, locationParams.get('zone'));
}
