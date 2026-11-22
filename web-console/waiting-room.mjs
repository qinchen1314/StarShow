const el = id => document.getElementById(id);
const showPattern = /^[1-9]\d{0,18}$/;
const presentation = {
  WAITING: ['仍在排队', '已保留你的候场位置，页面会按建议间隔刷新。'],
  EXPIRED: ['请重新加入候场', '开售版本或候场会话已变化。请确认开售时间后继续。'],
  ENDED: ['售票已结束', '本场候场已经关闭。'],
  RETRY: ['仍在等待候场服务', '候场暂不可用，服务恢复后可以继续查询。'],
  LOGIN: ['请登录', '使用同一账号登录即可恢复原来的位置。'],
  VERIFY: ['需要实名认证', '请完成实名认证，再回到此页加入候场。'],
  NOT_AVAILABLE: ['该场次未开放候场', '请核对场次 ID 或查看演出详情。'],
};
const storagePrefix = 'starshow:waiting-room:';
const storageKey = id => storagePrefix + id;
let jwt;
let room;

function storedToken(id) {
  return sessionStorage.getItem(storageKey(id));
}

function rememberToken(id, value) {
  if (value) sessionStorage.setItem(storageKey(id), value);
  else sessionStorage.removeItem(storageKey(id));
}

function clearWaitingRoomTokens() {
  for (let index = sessionStorage.length - 1; index >= 0; index--) {
    const key = sessionStorage.key(index);
    if (key?.startsWith(storagePrefix)) sessionStorage.removeItem(key);
  }
}

function retryDelay(header, result) {
  const value = header?.trim();
  const requested = Math.max(0, Number(result?.retryAfterMs) || 0);
  const retryHeader = /^\d+$/.test(value) ? Number(value) * 1000 : Math.max(0, Date.parse(value) - Date.now()) || 0;
  return Math.max(requested, retryHeader) || 2000;
}

async function api(path, { signal, jwt, readToken, body } = {}) {
  const response = await fetch('/api/v1' + path, {
    method: body ? 'POST' : 'GET',
    cache: 'no-store',
    signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(11000)]) : AbortSignal.timeout(11000),
    headers: {
      Accept: 'application/json',
      ...(body && { 'Content-Type': 'application/json' }),
      ...(jwt && { Authorization: 'Bearer ' + jwt }),
      ...(readToken && { 'X-Queue-Read-Token': readToken }),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const result = await response.json().catch(() => null);
  if (response.ok && result?.code === '00000') return result.data;
  throw Object.assign(new Error(result?.message || '请求暂不可用'), {
    code: result?.code, data: result?.data, status: response.status,
    retry: retryDelay(response.headers.get('Retry-After'), result?.data),
  });
}

function draw(page) {
  const [title, hint] = presentation[page.state];
  el('state').textContent = title;
  el('message').textContent = page.message || hint;
  el('sale').hidden = !page.saleStart;
  if (page.saleStart) {
    el('sale').textContent = '开售 ' + new Date(page.saleStart).toLocaleString('zh-CN', { hour12: false });
  }
  const backingOff = performance.now() < page.next;
  el('login').hidden = Boolean(jwt) || !['LOGIN', 'EXPIRED', 'VERIFY'].includes(page.state);
  el('join').hidden = !jwt || !['LOGIN', 'EXPIRED', 'VERIFY', 'RETRY'].includes(page.state);
  el('join').disabled = page.busy || backingOff;
  el('refresh').hidden = !['WAITING', 'RETRY'].includes(page.state);
  el('refresh').disabled = page.busy || backingOff;
}

function schedule(page) {
  clearTimeout(page.timer);
  if (document.hidden || page !== room || page.busy) return;
  const delay = Math.max(0, page.next - performance.now());
  if (delay > 0 || page.state === 'WAITING' || page.state === 'RETRY') {
    page.timer = setTimeout(() => {
      if (page !== room) return;
      draw(page);
      if (page.state === 'WAITING' || page.state === 'RETRY') void request(page, page.retryJoin);
    }, Math.min(Math.ceil(delay), 2147483647));
  }
}

function accept(page, data) {
  page.state = data.queueStatus;
  page.version = data.saleConfigVersion;
  page.saleStart = data.saleStartTime;
  page.next = performance.now() + (Number(data.retryAfterMs) || 0);
  page.retryJoin = false;
  page.message = '';
  if (data.queueReadToken) rememberToken(page.id, data.queueReadToken);
  if (page.state !== 'WAITING') rememberToken(page.id, null);
}

async function request(page, join) {
  if (page !== room || page.busy || performance.now() < page.next || (join && !jwt)) return;
  const readToken = join ? null : storedToken(page.id);
  if (!join && !readToken && !jwt) return;
  page.busy = true;
  draw(page);
  const path = '/shows/' + page.id + '/queue' + (join ? '' : '/me');
  try {
    const result = await api(path, {
      signal: page.controller.signal,
      jwt: readToken ? null : jwt,
      readToken,
      body: join ? (page.version ? { saleConfigVersion: page.version } : {}) : undefined,
    });
    if (page === room) accept(page, result);
  } catch (error) {
    if (page !== room) return;
    if (error.status === 401) {
      rememberToken(page.id, null);
      if (!readToken) jwt = null;
      page.state = 'LOGIN';
      page.message = '候场会话需要重新验证，原位置可通过本人账号找回。';
    } else if (error.code === 'A0205') {
      rememberToken(page.id, null);
      page.version = error.data?.saleConfigVersion;
      page.saleStart = error.data?.saleStartTime;
      page.state = 'EXPIRED';
      page.message = '';
    } else if (error.code === 'A0104') {
      page.state = 'VERIFY';
      page.message = '';
    } else if (error.code === 'A0201' || error.status === 404) {
      rememberToken(page.id, null);
      page.state = 'NOT_AVAILABLE';
      page.message = '';
    } else {
      page.state = 'RETRY';
      page.retryJoin = join;
      page.next = performance.now() + (error.retry ?? 2000);
      page.message = error.status === 429 ? '操作太频繁，请等待后刷新。' : '';
    }
  } finally {
    if (page === room) {
      page.busy = false;
      draw(page);
      schedule(page);
    }
  }
}

function open(id) {
  if (!showPattern.test(id)) return;
  if (room?.id === id) {
    void request(room, room.retryJoin);
    return;
  }
  room?.controller.abort();
  clearTimeout(room?.timer);
  room = { id, state: 'LOGIN', controller: new AbortController(), next: 0, message: '' };
  el('room').hidden = false;
  el('login-message').textContent = '';
  draw(room);
  if (storedToken(id) || jwt) void request(room, false);
}

el('show-form').addEventListener('submit', event => {
  event.preventDefault();
  open(el('show-id').value.trim());
});
el('login').addEventListener('submit', async event => {
  event.preventDefault();
  const page = room;
  const form = event.currentTarget;
  const submit = form.querySelector('button');
  const body = Object.fromEntries(new FormData(form));
  form.reset();
  submit.disabled = true;
  try {
    const result = await api('/auth/login', { signal: page.controller.signal, body });
    if (page !== room) return;
    jwt = result.token;
    clearWaitingRoomTokens();
    el('login-message').textContent = '';
    await request(page, true);
  } catch (error) {
    if (page === room) el('login-message').textContent = error.message;
  } finally {
    submit.disabled = false;
  }
});
el('join').addEventListener('click', () => void request(room, true));
el('refresh').addEventListener('click', () => void request(room, room.retryJoin));
document.addEventListener('visibilitychange', () => { if (room) schedule(room); });

const initialId = new URLSearchParams(location.search).get('showId');
if (initialId && showPattern.test(initialId)) {
  el('show-id').value = initialId;
  open(initialId);
}
