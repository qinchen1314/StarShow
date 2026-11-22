const PREFIX = 'starshow.confirmation.';
const LAST_ACCOUNT = `${PREFIX}account`;
const statuses = {
  WAIT_PAY: ['待支付', '订单已生成，请在付款截止前完成付款。'],
  PAID: ['已付款，正在出票', '可在原订单中查看出票进度。'],
  TICKETED: ['已出票', '电子票已可查看。'],
  CLOSED: ['订单已关闭', '可打开原订单查看关闭原因。'],
  REFUNDING: ['退款处理中', '可查看原单的退款进度。'],
  REFUNDED: ['已退款', '可查看原单的退款结果。'],
  FAILED: ['下单未成功', '请刷新确认该笔订单的处理结果。'],
  NOT_FOUND: ['暂无订单', '该场次可以发起购票。'],
};

function recordKey(userId, showId) {
  return `${PREFIX}${userId}.${showId}`;
}

function readRecord(userId, showId) {
  try {
    const key = recordKey(userId, showId);
    const saved = JSON.parse(localStorage.getItem(key)) ?? {};
    const { orderNo, clientRequestId, startedAt } = saved;
    const record = { orderNo, clientRequestId, startedAt };
    if (saved.payload) localStorage.setItem(key, JSON.stringify(record));
    return record;
  } catch {
    return {};
  }
}

function clearRecords(userId) {
  const prefix = `${PREFIX}${userId}.`;
  for (let index = localStorage.length - 1; index >= 0; index -= 1) {
    const key = localStorage.key(index);
    if (key.startsWith(prefix)) localStorage.removeItem(key);
  }
}

function switchAccount(userId) {
  const previous = localStorage.getItem(LAST_ACCOUNT);
  if (previous && previous !== userId) clearRecords(previous);
  localStorage.setItem(LAST_ACCOUNT, userId);
}

function displayAmount(amountCent) {
  const cents = String(amountCent);
  if (!/^\d+$/.test(cents)) return '';
  const decimal = cents.padStart(3, '0');
  return `¥${decimal.slice(0, -2)}.${decimal.slice(-2)}`;
}

function canSubmitOrder(order) {
  return order?.canStartNew === true && (
    order.status === 'NOT_FOUND' && order.nextAction === 'START_ORDER'
    || order.status === 'FAILED' && order.nextAction === 'RETRY_ORDER'
  );
}

async function api(path, token, body) {
  const response = await fetch(`/api/v1${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    cache: 'no-store',
    signal: AbortSignal.timeout(10_000),
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const result = await response.json();
  if (!response.ok || !['00000', '00202'].includes(result.code)) {
    const retry = response.headers.get('Retry-After');
    const retryMs = Number(retry) * 1000 || Math.max(0, Date.parse(retry) - Date.now()) || 0;
    throw Object.assign(new Error(result.message || '服务暂时不可用'), {
      code: result.code, data: result.data, retryAfterMs: retryMs,
    });
  }
  return result.data;
}

function readPurchase(form, showId) {
  const values = new FormData(form);
  const payload = {
    showId,
    tierId: values.get('tierId'),
    qty: Number(values.get('qty')),
    expectedUnitPriceCent: values.get('expectedUnitPriceCent'),
    saleConfigVersion: Number(values.get('saleConfigVersion')),
  };
  const zoneId = values.get('zoneId').trim();
  const layoutVersion = values.get('layoutVersion').trim();
  const seats = values.get('seatIndices').trim();
  if (zoneId || layoutVersion || seats) {
    const indices = seats.split(/[,，]/).map(value => value.trim());
    if (!zoneId || !layoutVersion || indices.length !== payload.qty
        || indices.some(value => !/^\d+$/.test(value) || Number(value) > 2147483647)) {
      throw new Error('请填写分区、布局版本和与购票张数相同的座位序号。');
    }
    Object.assign(payload, { zoneId, layoutVersion, seatIndices: indices.map(Number) });
  }
  return payload;
}

export function bindConfirmationPage({ confirmationStage, pollDelay, paymentClock, SLOW_AFTER, MANUAL_AFTER }) {
  const byId = id => document.getElementById(id);
  const show = (id, visible) => { byId(id).hidden = !visible; };
  const text = (id, value) => {
    const node = byId(id);
    if (node.textContent !== value) node.textContent = value;
  };
  const listen = (id, event, action) => byId(id).addEventListener(event, action);
  let account;
  let active;
  let timer;
  let cursor;
  let listLoading = false;

  function notice(message = '', error = false) {
    text('notice', message);
    byId('notice').toggleAttribute('data-error', error);
    show('notice', Boolean(message));
  }

  function save(ctx) {
    if (!ctx.track || localStorage.getItem(LAST_ACCOUNT) !== ctx.userId) return;
    localStorage.setItem(recordKey(ctx.userId, ctx.showId), JSON.stringify({
      clientRequestId: ctx.clientRequestId, orderNo: ctx.orderNo,
      startedAt: ctx.startedAt,
    }));
    renderRecent();
  }

  function stop() {
    clearTimeout(timer);
    timer = undefined;
  }

  function render(ctx) {
    const { order, orderNo, payment } = ctx;
    const elapsed = Math.max(0, Date.now() - ctx.startedAt);
    const stage = confirmationStage(elapsed);
    let [title, message] = statuses[order?.status] ?? ['确认中', '请求已收到，订单确认中。'];
    let action = '';
    const checking = !order || order.nextAction === 'CHECK_ORDER';
    if (!order || order.status === 'UNKNOWN' && stage === 'initial') {
      [title, message] = ['查询中', ctx.clientRequestId ? '正在查找这次购票请求。' : '正在查找订单记录。'];
    } else if (checking && stage !== 'initial') {
      [title, message] = ['仍在确认', '可离开此页，稍后从我的订单继续查看。'];
    }
    if (checking && stage === 'manual') message += ' 可手动刷新进度。';
    const canSubmit = canSubmitOrder(order);
    const remaining = ctx.paymentChecked && !document.hidden ? paymentClock(order, payment, performance.now()) : null;
    const checkingPayment = order?.status === 'WAIT_PAY' && (!(remaining > 0) || ctx.needsRefresh);
    if (checkingPayment) {
      message = '正在确认原支付单和订单状态。';
    }
    if (payment?.nextAction === 'VIEW_REFUND') [title, message] = ['支付退款处理中', '可查看原付款对应的退款记录。'];
    if (payment?.paymentStatus === 'SUCCESS') [title, message] = ['付款已收到', '正在确认订单和出票进度。'];
    if (remaining > 0 && !ctx.needsRefresh) action = payment ? '模拟付款' : '进入付款';
    else if (order?.nextAction === 'VIEW_TICKETS') action = '查看电子票';
    else if (order?.nextAction === 'VIEW_REFUND' || payment?.nextAction === 'VIEW_REFUND') action = '查看退款';
    else if (order?.nextAction === 'VIEW_ORDER') action = '查看订单';
    else if (ctx.payload && order?.status === 'SUBMITTING') action = '用原参数重试';

    const amount = displayAmount(payment?.amountCent ?? ctx.amountCent);
    const restoreFocus = !canSubmit && byId('purchase').contains(document.activeElement);
    text('scene', ctx.showTitle ?? ctx.showId);
    text('order-title', title);
    text('order-message', message);
    text('order-number', orderNo ?? '');
    text('order-amount', amount);
    show('number-row', Boolean(orderNo));
    show('amount-row', Boolean(amount));
    show('reference', true);
    show('purchase', canSubmit);
    show('refresh', Boolean(order));
    show('primary-action', Boolean(action));
    text('primary-action', action);
    text('refresh', checkingPayment ? '查询支付结果' : stage === 'manual' && checking ? '手动刷新' : '刷新进度');
    byId('refresh').disabled = ctx.busy;
    byId('primary-action').disabled = ctx.busy;
    byId('submit-order').disabled = ctx.busy;
    text('submit-order', ctx.busy ? '正在处理…' : '提交购票');
    show('countdown', remaining > 0 && !ctx.needsRefresh);
    if (remaining > 0) {
      const seconds = Math.ceil(remaining / 1000);
      text('deadline-label', payment ? '付款剩余时间' : '支付窗口剩余');
      text('remaining', `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`);
    }
    if (restoreFocus) byId('order-title').focus();
  }

  function schedule(ctx) {
    stop();
    if (active !== ctx || document.hidden || ctx.busy) return;
    const elapsed = Math.max(0, Date.now() - ctx.startedAt);
    if (ctx.order?.nextAction === 'CHECK_ORDER') {
      const stage = confirmationStage(elapsed);
      if (stage === 'manual') return;
      const boundary = (stage === 'initial' ? SLOW_AFTER : MANUAL_AFTER) - elapsed;
      const delay = ctx.pollAt - performance.now();
      timer = setTimeout(() => {
        if (document.hidden || active !== ctx) return;
        const newStage = confirmationStage(Date.now() - ctx.startedAt);
        if (newStage === 'manual') { render(ctx); return; }
        if (newStage !== stage) {
          ctx.pollAt = Math.max(ctx.pollAt, performance.now() + pollDelay(SLOW_AFTER, ctx.attempt, ctx.order.retryAfterMs));
          render(ctx);
          schedule(ctx);
          return;
        }
        void refresh(ctx);
      }, Math.max(0, Math.min(delay, boundary)));
    } else if (ctx.order?.status === 'WAIT_PAY') {
      const remaining = ctx.paymentChecked ? paymentClock(ctx.order, ctx.payment, performance.now()) : null;
      if (remaining > 0) {
        timer = setTimeout(() => {
          if (document.hidden || active !== ctx) return;
          render(ctx);
          if (paymentClock(ctx.order, ctx.payment, performance.now()) === 0) void refresh(ctx);
          else schedule(ctx);
        }, Math.min(250, remaining));
      } else {
        timer = setTimeout(() => { if (!document.hidden && active === ctx) void refresh(ctx); }, 5_000);
      }
    }
  }

  function update(ctx, order) {
    ctx.order = { ...order, receivedAt: performance.now() };
    if (order.amountCent != null) ctx.amountCent = order.amountCent;
    if (order.orderNo) {
      ctx.orderNo = order.orderNo;
      save(ctx);
    }
    if (ctx.order.nextAction === 'CHECK_ORDER') {
      const delay = pollDelay(Date.now() - ctx.startedAt, ctx.attempt, order.retryAfterMs ?? order.pollIntervalMs);
      ctx.pollAt = performance.now() + (delay ?? 0);
      ctx.attempt += 1;
    }
  }

  async function lookupOrder(ctx) {
    const query = `?showId=${encodeURIComponent(ctx.showId)}`;
    if (ctx.orderNo) {
      try {
        return await api(`/orders/${encodeURIComponent(ctx.orderNo)}/status${query}`, ctx.token);
      } catch (error) {
        if (error.code !== 'A0304') throw error;
        if (!ctx.clientRequestId) return { status: 'UNKNOWN', nextAction: 'CHECK_ORDER' };
      }
    }
    if (ctx.clientRequestId) {
      return api(`/seckill/requests/${encodeURIComponent(ctx.clientRequestId)}${query}`, ctx.token);
    }
    return api(`/orders/current${query}`, ctx.token);
  }

  async function readPayment(ctx) {
    try {
      const payment = await api(`/payments/order/${encodeURIComponent(ctx.orderNo)}`, ctx.token);
      return { ...payment, receivedAt: performance.now() };
    } catch (error) {
      if (error.code !== 'A0401') throw error;
      return null;
    }
  }

  async function readOrderAmount(ctx) {
    if (ctx.amountCent != null || ctx.payment?.amountCent != null || ctx.amountPending) return;
    ctx.amountPending = true;
    try {
      const order = await api(`/orders/${encodeURIComponent(ctx.orderNo)}`, ctx.token);
      if (active === ctx) {
        ctx.amountCent = order.amountCent;
        render(ctx);
      }
    } catch {
      // Keep the confirmed order state visible if the optional amount lookup is unavailable.
    } finally {
      ctx.amountPending = false;
    }
  }

  async function readShowTitle(ctx) {
    try {
      const detail = await api(`/shows/${encodeURIComponent(ctx.showId)}`, ctx.token);
      if (active === ctx && detail?.title) {
        ctx.showTitle = detail.title;
        render(ctx);
      }
    } catch {
      // The scene ID still identifies the recovery context if event details are unavailable.
    }
  }

  async function refresh(ctx) {
    if (!ctx || active !== ctx || ctx.busy || document.hidden) return;
    stop();
    ctx.busy = true;
    ctx.needsRefresh = true;
    render(ctx);
    try {
      let result = await lookupOrder(ctx);
      if (active !== ctx) return;
      if (ctx.changedOffer && !ctx.orderNo && result.status === 'UNKNOWN') {
        const current = await api(`/orders/current?showId=${encodeURIComponent(ctx.showId)}`, ctx.token);
        if (active !== ctx) return;
        if (current.canStartNew && current.status === 'NOT_FOUND') {
          result = current;
        }
      }
      ctx.payment = null;
      ctx.paymentChecked = false;
      update(ctx, result);
      if (result.status === 'WAIT_PAY' && result.orderNo && !document.hidden) {
        ctx.payment = await readPayment(ctx);
        ctx.paymentChecked = true;
        if (active === ctx) void readOrderAmount(ctx);
      }
      ctx.needsRefresh = false;
    } catch (error) {
      if (active !== ctx) return;
      if (['A0101', 'A0102'].includes(error.code)) { reset('登录已失效，请重新登录。'); return; }
      if (!ctx.paymentChecked && ctx.order?.status === 'WAIT_PAY') {
        notice('支付状态暂时无法确认，请稍后刷新。');
      } else {
        update(ctx, { status: 'UNKNOWN', nextAction: 'CHECK_ORDER', retryAfterMs: error.retryAfterMs });
      }
    } finally {
      ctx.busy = false;
      if (active === ctx) {
        if (ctx.resumeOnIdle && !document.hidden) { ctx.resumeOnIdle = false; void refresh(ctx); }
        else { render(ctx); schedule(ctx); }
      }
    }
  }

  function open(showId, historicOrderNo, amountCent) {
    stop();
    notice();
    show('details', false);
    const track = !historicOrderNo;
    const { orderNo, clientRequestId, startedAt } = track
      ? readRecord(account.userId, showId) : { orderNo: historicOrderNo };
    const payload = active?.clientRequestId === clientRequestId ? active?.payload : undefined;
    active = { ...account, orderNo, clientRequestId, payload, showId, track, amountCent, startedAt: startedAt ?? Date.now(), attempt: 0 };
    byId('lookup').elements.showId.value = showId;
    byId('order-form').reset();
    show('history', false);
    show('lookup-section', false);
    show('order-view', true);
    void refresh(active);
    void readShowTitle(active);
    byId('order-title').focus();
  }

  async function submit(ctx, payload, retry = false) {
    stop();
    ctx.busy = true;
    if (!retry) notice();
    render(ctx);
    let sent = false;
    try {
      const { showId, saleConfigVersion } = payload;
      const { seckillToken } = await api('/seckill/token', ctx.token, { showId, saleConfigVersion });
      if (active !== ctx) return;
      if (!retry) {
        Object.assign(ctx, { clientRequestId: crypto.randomUUID(), orderNo: null, payload,
          amountCent: undefined, changedOffer: false, startedAt: Date.now(), attempt: 0 });
        save(ctx);
      }
      sent = true;
      const result = await api('/seckill/orders', ctx.token, { ...payload, clientRequestId: ctx.clientRequestId, seckillToken });
      if (result.orderNo) { ctx.orderNo = result.orderNo; save(ctx); }
      if (active === ctx && result.status === 'PENDING_CONFIRM') update(ctx, { ...result, nextAction: 'CHECK_ORDER' });
    } catch (error) {
      if (active !== ctx) return;
      if (['A0101', 'A0102'].includes(error.code)) { reset('登录已失效，请重新登录。'); return; }
      if (error.data?.orderNo) { ctx.orderNo = error.data.orderNo; save(ctx); }
      if (['A0204', 'A0205', 'A0308'].includes(error.code)) {
        if (sent && !retry) ctx.changedOffer = true;
        const price = error.data?.currentUnitPriceCent;
        notice(price ? `单价从 ${payload.expectedUnitPriceCent} 分变为 ${price} 分。确认可以重试后，请核对最新价格与版本。` : error.message);
      } else if (error.code) notice(error.message);
      else if (sent) update(ctx, { status: 'UNKNOWN', nextAction: 'CHECK_ORDER' });
      else notice('暂时无法提交，请稍后重试。', true);
    } finally {
      ctx.busy = false;
      if (active === ctx) {
        if (ctx.order?.status === 'PENDING_CONFIRM' && !ctx.resumeOnIdle) { render(ctx); schedule(ctx); }
        else { ctx.resumeOnIdle = false; void refresh(ctx); }
      }
    }
  }

  async function primaryAction(ctx) {
    if (!ctx || ctx.busy || ctx.needsRefresh || document.hidden) return;
    const { order, orderNo, token } = ctx;
    if (ctx.payload && order.status === 'SUBMITTING') { await submit(ctx, ctx.payload, true); return; }
    stop();
    ctx.busy = true;
    render(ctx);
    let revisit = false;
    try {
      if (order.status === 'WAIT_PAY' && ctx.paymentChecked && paymentClock(order, ctx.payment, performance.now()) > 0) {
        const existing = await readPayment(ctx);
        if (active !== ctx) return;
        if (ctx.payment && existing?.payNo === ctx.payment.payNo && !document.hidden
            && paymentClock(order, existing, performance.now()) > 0) {
          await api(`/payments/mock/pay/${encodeURIComponent(existing.payNo)}`, token, {});
          revisit = true;
        } else {
          ctx.payment = existing;
          if (!existing && !document.hidden && paymentClock(order, null, performance.now()) > 0) {
            const created = await api('/payments', token, { orderNo });
            ctx.payment = { ...created, receivedAt: performance.now() };
          }
        }
      } else if (order.nextAction === 'VIEW_TICKETS') {
        const view = await api(`/tickets/order/${encodeURIComponent(orderNo)}`, token);
        if (active === ctx) {
          showDetails(view.tickets.flatMap(ticket => [['电子票', ticket.ticketNo], ['座位', ticket.seatInfo]]));
        }
      } else if (order.nextAction === 'VIEW_REFUND' || ctx.payment?.nextAction === 'VIEW_REFUND') {
        const refund = await api(`/refunds/order/${encodeURIComponent(orderNo)}`, token);
        if (active === ctx) showDetails([['退款单号', refund.refundNo], ['退款状态', refund.refundStatus ?? refund.status]]);
      } else if (order.nextAction === 'VIEW_ORDER') {
        const detail = await api(`/orders/${encodeURIComponent(orderNo)}`, token);
        if (active === ctx) showDetails([['订单状态', statuses[detail.status]?.[0] ?? detail.status], ['订单金额', displayAmount(detail.amountCent) || null], ['关闭原因', detail.closeReason]]);
      }
    } catch (error) {
      if (active === ctx) { notice(error.code ? error.message : '暂时无法确认，请稍后刷新。'); revisit = order.status === 'WAIT_PAY'; }
    } finally {
      ctx.busy = false;
      if (active === ctx) {
        if (revisit || ctx.resumeOnIdle) { ctx.resumeOnIdle = false; void refresh(ctx); }
        else { render(ctx); schedule(ctx); }
      }
    }
  }

  function showDetails(rows) {
    const list = document.createElement('dl');
    list.className = 'summary';
    for (const [key, value] of rows) {
      if (value == null) continue;
      const term = document.createElement('dt');
      term.textContent = key;
      const definition = document.createElement('dd');
      definition.textContent = value;
      const row = document.createElement('div');
      row.append(term, definition);
      list.append(row);
    }
    byId('details').replaceChildren(list);
    show('details', true);
  }

  function orderLink(list, label, select) {
    const item = document.createElement('li');
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = label;
    button.addEventListener('click', select);
    item.append(button);
    list.append(item);
  }

  function renderRecent() {
    if (!account) return;
    const prefix = `${PREFIX}${account.userId}.`;
    const list = byId('recent-requests');
    const records = Object.keys(localStorage).filter(key => key.startsWith(prefix))
      .map(key => key.slice(prefix.length))
      .filter(showId => /^[1-9]\d*$/.test(showId))
      .map(showId => ({ showId, ...readRecord(account.userId, showId) }))
      .filter(record => record.clientRequestId || record.orderNo)
      .sort((a, b) => b.startedAt - a.startedAt);
    const ids = records.map(({ showId }) => showId).join(',');
    show('recent-section', records.length > 0);
    if (list.dataset.shows === ids) return;
    list.dataset.shows = ids;
    list.replaceChildren();
    for (const { showId } of records) orderLink(list, `继续查看场次 ${showId}`, () => open(showId));
  }

  async function orders(append = false) {
    if (!account || listLoading) return;
    const session = account;
    listLoading = true;
    byId('order-list').setAttribute('aria-busy', 'true');
    byId('load-orders').disabled = true;
    byId('more-orders').disabled = true;
    try {
      const page = await api(`/orders?size=10${append ? `&cursor=${encodeURIComponent(cursor)}` : ''}`, session.token);
      if (account !== session) return;
      cursor = page.nextCursor;
      const list = byId('order-list');
      if (!append) list.replaceChildren();
      for (const order of page.items) {
        orderLink(list, `${statuses[order.status]?.[0] ?? order.status} · ${order.orderNo}`,
          () => open(order.showId, order.orderNo, order.amountCent));
      }
      if (!page.items.length && !append) {
        const item = document.createElement('li');
        item.textContent = '暂无已落库订单，可按场次找回确认中的订单。';
        list.append(item);
      }
      show('more-orders', page.hasMore);
      text('load-orders', '刷新列表');
    } catch (error) {
      if (account === session) notice(error.code ? error.message : '暂时无法读取订单记录。');
    } finally {
      if (account === session) {
        listLoading = false;
        byId('order-list').setAttribute('aria-busy', 'false');
        byId('load-orders').disabled = false;
        byId('more-orders').disabled = false;
      }
    }
  }

  function reset(message) {
    stop();
    account = active = cursor = undefined;
    listLoading = false;
    for (const id of ['lookup', 'order-form', 'login']) byId(id).reset();
    for (const id of ['recent-requests', 'order-list', 'details', 'remaining', 'order-number', 'order-amount', 'scene', 'account-name']) text(id, '');
    delete byId('recent-requests').dataset.shows;
    for (const id of ['account', 'workspace', 'order-view', 'reference', 'number-row', 'amount-row', 'purchase', 'refresh',
      'primary-action', 'more-orders', 'details', 'countdown', 'recent-section']) show(id, false);
    show('history', true);
    show('lookup-section', true);
    byId('order-list').setAttribute('aria-busy', 'false');
    byId('load-orders').disabled = false;
    byId('more-orders').disabled = false;
    text('load-orders', '加载订单');
    text('order-title', '查找订单');
    show('sign-in', true);
    notice(message);
    byId('login').elements.phone.focus();
  }

  listen('login', 'submit', async event => {
    event.preventDefault();
    const form = event.currentTarget;
    const button = form.querySelector('button');
    const payload = Object.fromEntries(new FormData(form));
    form.elements.password.value = '';
    notice();
    button.disabled = true;
    button.textContent = '正在登录…';
    try {
      const login = await api('/auth/login', null, payload);
      switchAccount(login.userId);
      account = { userId: login.userId, token: login.token };
      form.reset();
      text('account-name', login.nickname ?? '已登录');
      show('sign-in', false);
      show('account', true);
      show('workspace', true);
      renderRecent();
      byId('list-title').focus();
      void orders();
    } catch (error) {
      notice(error.code ? error.message : '暂时无法登录，请稍后重试。', true);
      form.elements.password.focus();
    } finally {
      button.disabled = false;
      button.textContent = '登录';
    }
  });
  listen('logout', 'click', () => {
    const session = account;
    clearRecords(session.userId);
    reset('已退出此页。');
    void api('/auth/logout', session.token, {}).catch(() => {});
  });
  listen('lookup', 'submit', event => {
    event.preventDefault();
    open(new FormData(event.currentTarget).get('showId'));
  });
  listen('order-form', 'submit', event => {
    event.preventDefault();
    const ctx = active;
    if (!ctx || ctx.busy || !canSubmitOrder(ctx.order)) return;
    try { void submit(ctx, readPurchase(event.currentTarget, ctx.showId)); }
    catch (error) {
      notice(error.message, true);
      byId('order-form').querySelector('details').open = true;
      byId('order-form').elements.zoneId.focus();
    }
  });
  listen('primary-action', 'click', () => { void primaryAction(active); });
  listen('refresh', 'click', () => { notice(); void refresh(active); });
  listen('view-orders', 'click', () => {
    stop();
    active = undefined;
    show('order-view', false);
    show('purchase', false);
    show('history', true);
    show('lookup-section', true);
    notice();
    byId('list-title').focus();
    void orders();
  });
  listen('load-orders', 'click', () => { void orders(); });
  listen('more-orders', 'click', () => { void orders(true); });
  document.addEventListener('visibilitychange', () => {
    stop();
    if (!active) return;
    if (document.hidden) { active.needsRefresh = true; render(active); }
    else if (active.busy) active.resumeOnIdle = true;
    else void refresh(active);
  });
  window.addEventListener('storage', event => {
    if (event.key === LAST_ACCOUNT && account && event.newValue !== account.userId) reset('账号已切换，请重新登录。');
  });
}
