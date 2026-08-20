/*
 * OpenMinis Remote DSH adapter
 *
 * The layout/interaction model follows DeepSeek Harness rc.8 source while
 * this thin client talks only to the authenticated Android Remote API.  The
 * key rule is the same as DSH ChatView: stable message/tool keys, then patch
 * only the changed tail node from the event stream.
 */
(() => {
  'use strict';

  const $ = (selector, root) => (root || document).querySelector(selector);
  const $$ = (selector, root) => Array.from((root || document).querySelectorAll(selector));
  const FLOW_LIMIT = 2000;
  const THINKING_LEVELS = [
    ['off', '关闭思考'],
    ['low', '低'],
    ['medium', '中'],
    ['high', '高'],
    ['xhigh', '极高'],
    ['max', 'Max'],
    ['ultra', 'Ultra'],
  ];

  const state = {
    sessionId: null,
    sessions: [],
    messages: [],
    status: null,
    nodeById: new Map(),
    // A session-scoped, server-to-browser WebSocket.  It carries ordered
    // append-only events rather than polling / replacing a whole transcript.
    live: null,
    liveReconnectTimer: null,
    liveReconnectAttempt: 0,
    lastEventSeq: 0,
    eventBaselineKnown: false,
    eventGapRecovering: false,
    housekeeping: null,
    interventionTimer: null,
    agentRefreshTimer: null,
    usageRefreshTimer: null,
    lastAgentRefresh: 0,
    lastUsageRefresh: 0,
    running: false,
    thinkingLevel: 'off',
    modelEntries: [],
    modelMenuPane: 'root',
    detailsOpen: false,
    detailsView: 'task',
    selectedTool: null,
    agent: { goal: {}, todos: [], plan: {}, deliverables: [], jobs: [] },
    question: null,
    approval: null,
    attachFiles: [],
    sessionMenuFor: null,
    workspaceTab: 'files',
    workspacePath: '/var/minis/workspace',
    workspaceFiles: [],
    workspaceFile: null,
    workspaceShell: '',
    controlView: 'models',
    controlCache: {},
    feedback: new Map(),
    searchTimer: null,
  };

  function esc(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, (c) => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
    })[c]);
  }

  function text(value) {
    if (value == null) return '';
    if (typeof value === 'string') return value;
    try { return JSON.stringify(value); } catch (_) { return String(value); }
  }

  function brief(value, limit) {
    const raw = text(value).replace(/\s+/g, ' ').trim();
    const max = limit || 88;
    return raw.length > max ? raw.slice(0, max - 1) + '…' : raw;
  }

  function formatBytes(value) {
    const n = Number(value || 0);
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
    return (n / (1024 * 1024)).toFixed(1) + ' MB';
  }

  function formatTokens(value) {
    const n = Number(value || 0);
    if (n < 1000) return n ? String(n) : '';
    if (n < 1000000) return (n / 1000).toFixed(n < 10000 ? 1 : 0) + 'k';
    return (n / 1000000).toFixed(1) + 'M';
  }

  function formatWhen(value) {
    if (!value) return '';
    try {
      return new Date(Number(value)).toLocaleString('zh-CN', {
        month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit',
      });
    } catch (_) {
      return '';
    }
  }

  function markdown(value) {
    const raw = text(value);
    if (window.MD && typeof window.MD.render === 'function') return window.MD.render(raw);
    return esc(raw).replace(/\n/g, '<br>');
  }

  function toast(message, kind) {
    const wrap = $('#toasts');
    if (!wrap) return;
    const el = document.createElement('div');
    el.className = 'toast' + (kind === 'error' ? ' error' : '');
    el.textContent = message;
    wrap.appendChild(el);
    requestAnimationFrame(() => el.classList.add('show'));
    setTimeout(() => {
      el.classList.remove('show');
      setTimeout(() => el.remove(), 180);
    }, 3200);
  }

  async function api(path, options) {
    const opt = options || {};
    const headers = Object.assign({}, opt.headers || {});
    if (opt.body && !headers['Content-Type']) headers['Content-Type'] = 'application/json';
    const response = await fetch(path, Object.assign({}, opt, { headers, credentials: 'same-origin' }));
    let body = {};
    try { body = await response.json(); } catch (_) {}
    if (response.status === 401 && path !== '/api/auth/login' && path !== '/api/auth/status') {
      showLogin();
      throw new Error('登录已失效');
    }
    if (!response.ok) throw new Error(body.error || body.output || ('HTTP ' + response.status));
    return body;
  }

  async function rpc(method, params) {
    const body = await api('/api/rpc', {
      method: 'POST',
      body: JSON.stringify({
        jsonrpc: '2.0',
        id: Date.now(),
        method,
        params: params || {},
      }),
    });
    if (body.error) throw new Error(body.error.message || ('RPC ' + body.error.code));
    return body.result;
  }

  function setConnection(mode, label) {
    const dot = $('#serverDot');
    const value = $('#serverState');
    if (!dot || !value) return;
    dot.classList.toggle('is-reconnecting', mode === 'reconnecting');
    dot.classList.toggle('is-offline', mode === 'offline');
    value.textContent = label || (mode === 'online' ? '已连接到手机' : mode === 'reconnecting' ? '正在恢复实时连接…' : '连接已中断');
  }

  function clearTimedWork() {
    clearInterval(state.housekeeping);
    clearInterval(state.interventionTimer);
    clearTimeout(state.agentRefreshTimer);
    clearTimeout(state.usageRefreshTimer);
    state.housekeeping = null;
    state.interventionTimer = null;
    state.agentRefreshTimer = null;
    state.usageRefreshTimer = null;
  }

  function closeLiveStream() {
    clearTimeout(state.liveReconnectTimer);
    state.liveReconnectTimer = null;
    if (state.live) {
      state.live.onopen = null;
      state.live.onmessage = null;
      state.live.onerror = null;
      state.live.onclose = null;
      state.live.close();
    }
    state.live = null;
    state.liveReconnectAttempt = 0;
  }

  function showLogin() {
    closeLiveStream();
    clearTimedWork();
    $('#app').classList.add('hidden');
    $('#login').classList.remove('hidden');
    $('#passwordInput').value = '';
    $('#passwordInput').focus();
  }

  async function showApp() {
    $('#login').classList.add('hidden');
    $('#app').classList.remove('hidden');
    setConnection('online');
    await refreshSessions(true);
    if (state.sessions.length && !state.sessionId) {
      await selectSession(state.sessions[0].id);
    } else if (!state.sessions.length) {
      renderSessions();
      renderBlankConversation();
    }
    clearInterval(state.housekeeping);
    state.housekeeping = setInterval(() => {
      if (!document.hidden) {
        refreshSessions(false).catch(() => {});
        scheduleAgentRefresh();
        scheduleUsageRefresh();
      }
    }, 7000);
  }

  async function login() {
    $('#loginError').textContent = '';
    try {
      await api('/api/auth/login', {
        method: 'POST',
        body: JSON.stringify({
          username: $('#usernameInput').value.trim(),
          password: $('#passwordInput').value,
        }),
      });
      $('#passwordInput').value = '';
      await showApp();
    } catch (error) {
      $('#loginError').textContent = '登录失败：' + error.message;
    }
  }

  async function logout() {
    try { await api('/api/auth/logout', { method: 'POST', body: '{}' }); } catch (_) {}
    showLogin();
  }

  async function boot() {
    try {
      const auth = await api('/api/auth/status');
      if (auth.authenticated) {
        if (auth.username) $('#usernameInput').value = auth.username;
        await showApp();
      } else {
        showLogin();
      }
    } catch (_) {
      showLogin();
    }
  }

  /* ── sessions/sidebar ─────────────────────────────────────────────── */

  function sessionMatches(session, query) {
    const haystack = [
      session.title, session.lastMessagePreview, session.modelName, session.modelId,
    ].join(' ').toLowerCase();
    return haystack.includes(query.toLowerCase());
  }

  function renderSessions() {
    const root = $('#sessions');
    if (!root) return;
    const query = ($('#searchInput').value || '').trim();
    const sessions = query ? state.sessions.filter((item) => sessionMatches(item, query)) : state.sessions;
    root.innerHTML = sessions.length ? sessions.map((session) => {
      const active = session.id === state.sessionId;
      const title = session.title || '新会话';
      const meta = session.modelName || session.modelId || '未选择模型';
      return '<div class="session-row' + (active ? ' active' : '') + '" role="listitem" data-session-id="' + esc(session.id) + '">' +
        '<span class="' + (session.isRunning ? 'session-running' : 'session-idle') + '"></span>' +
        '<span class="session-copy"><strong>' + esc(title) + '</strong><span>' + esc(meta) + '</span></span>' +
        '<button class="session-more" data-session-more="' + esc(session.id) + '" aria-label="会话操作">•••</button>' +
      '</div>';
    }).join('') : '<div class="sessions-empty">' + (query ? '没有匹配的会话。' : '还没有会话。创建一个会话开始。') + '</div>';
  }

  async function refreshSessions(selectFirst) {
    const result = await api('/api/sessions?limit=200');
    state.sessions = result.sessions || [];
    const exists = state.sessions.some((session) => session.id === state.sessionId);
    if (state.sessionId && !exists) {
      closeLiveStream();
      state.sessionId = null;
      state.messages = [];
      state.nodeById.clear();
    }
    renderSessions();
    if ((selectFirst || !state.sessionId) && state.sessions.length && !state.sessionId) {
      await selectSession(state.sessions[0].id);
    } else {
      updateHeaderFromSession();
    }
  }

  function updateHeaderFromSession() {
    const session = state.sessions.find((item) => item.id === state.sessionId);
    const title = state.status && state.status.title ? state.status.title : (session && session.title ? session.title : '新会话');
    const model = state.status && state.status.modelName ? state.status.modelName : (session && (session.modelName || session.modelId));
    $('#sessionTitle').textContent = title || '新会话';
    $('#modelLabel').textContent = model || '选择模型';
    $('#thinkingLabel').textContent = state.thinkingLevel && state.thinkingLevel !== 'off'
      ? '· ' + thinkingLabel(state.thinkingLevel) : '';
    $('#workspaceSession').textContent = title || '';
    renderSessions();
  }

  function openSessionMenu(id, anchor) {
    if (!id || !anchor) return;
    state.sessionMenuFor = id;
    const menu = $('#sessionMenu');
    const rect = anchor.getBoundingClientRect();
    menu.classList.remove('hidden');
    menu.style.left = Math.min(rect.right - 180, window.innerWidth - 194) + 'px';
    menu.style.top = Math.min(rect.bottom + 4, window.innerHeight - 140) + 'px';
  }

  function closeSessionMenu() {
    $('#sessionMenu').classList.add('hidden');
    state.sessionMenuFor = null;
  }

  async function newSession() {
    try {
      const response = await api('/api/session/new', { method: 'POST', body: '{}' });
      await refreshSessions(false);
      if (response.sessionId) await selectSession(response.sessionId);
    } catch (error) {
      toast('新建会话失败：' + error.message, 'error');
    }
  }

  async function renameSession() {
    const id = state.sessionMenuFor;
    const session = state.sessions.find((item) => item.id === id);
    const next = window.prompt('新的会话名称', (session && session.title) || '');
    if (next == null || !next.trim()) return;
    await api('/api/session/title', {
      method: 'POST',
      body: JSON.stringify({ sessionId: id, title: next.trim() }),
    });
    await refreshSessions(false);
    if (id === state.sessionId) $('#sessionTitle').textContent = next.trim();
  }

  async function deleteSession() {
    const id = state.sessionMenuFor;
    const session = state.sessions.find((item) => item.id === id);
    if (!id || !window.confirm('删除会话「' + ((session && session.title) || id) + '」？此操作不可撤销。')) return;
    await api('/api/session/delete', {
      method: 'POST',
      body: JSON.stringify({ sessionId: id }),
    });
    if (id === state.sessionId) {
      closeLiveStream();
      state.sessionId = null;
      state.messages = [];
      state.nodeById.clear();
      $('#chatFlow').innerHTML = '';
    }
    await refreshSessions(true);
  }

  async function selectSession(id) {
    if (!id) return;
    if (state.sessionId === id && state.live) return;
    closeLiveStream();
    state.sessionId = id;
    state.messages = [];
    state.status = null;
    state.nodeById.clear();
    state.selectedTool = null;
    state.question = null;
    state.approval = null;
    state.lastEventSeq = 0;
    state.eventBaselineKnown = false;
    state.eventGapRecovering = false;
    state.thinkingLevel = sessionStorage.getItem('openminis.thinking.' + id) || 'off';
    $('#chatFlow').innerHTML = '';
    $('#takeover').innerHTML = '';
    updateHeaderFromSession();
    $('#app').classList.remove('mobile-sidebar-open');
    await hydrateConversation();
    await refreshAgentData();
    await refreshUsage();
    openLiveStream(id);
    clearInterval(state.interventionTimer);
    state.interventionTimer = setInterval(() => {
      if (!document.hidden) refreshTakeover().catch(() => {});
    }, 900);
    $('#prompt').focus();
  }

  async function hydrateConversation() {
    if (!state.sessionId) return;
    try {
      const id = state.sessionId;
      const results = await Promise.all([
        api('/api/messages?sessionId=' + encodeURIComponent(id) + '&limit=' + FLOW_LIMIT + '&includeReasoning=true'),
        api('/api/session/status?sessionId=' + encodeURIComponent(id)),
      ]);
      if (state.sessionId !== id) return;
      applySnapshot({
        messages: results[0].messages || [],
        status: results[1] || {},
        // Newer event-aware servers return this waterline with the snapshot.
        // It is deliberately optional while old Remote builds are upgraded.
        lastSeq: results[0].lastSeq || results[0].eventSeq || results[1].lastSeq || results[1].eventSeq,
      });
    } catch (error) {
      toast('加载会话失败：' + error.message, 'error');
    }
  }

  /* ── typed session-event bridge ──────────────────────────────────────
   *
   * This follows the transport shape used by DeepSeek Harness: history is a
   * one-time snapshot and a downlink delivers `{ type: 'session/event',
   * sessionId, event }` frames.  The server may either send that frame
   * directly or wrap it in DSH's `server-request` envelope.  No client event
   * is sent over this socket: mutations keep using authenticated HTTP/RPC.
   */

  function eventSocketUrl(id) {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const query = new URLSearchParams({
      sessionId: id,
      includeReasoning: 'true',
      // Ask a freshly upgraded server to give us a baseline frame when the
      // REST snapshot did not yet expose an event waterline.
      snapshot: state.eventBaselineKnown ? '0' : '1',
    });
    if (state.lastEventSeq > 0) query.set('afterSeq', String(state.lastEventSeq));
    return protocol + '//' + window.location.host + '/api/events/session?' + query.toString();
  }

  function scheduleLiveReconnect(id) {
    if (!id || state.sessionId !== id || state.liveReconnectTimer || document.hidden) return;
    state.liveReconnectAttempt = Math.min(state.liveReconnectAttempt + 1, 8);
    const delay = Math.min(12000, 420 * Math.pow(1.65, state.liveReconnectAttempt - 1));
    state.liveReconnectTimer = setTimeout(() => {
      state.liveReconnectTimer = null;
      openLiveStream(id);
    }, delay);
  }

  function unpackEventFrame(frame) {
    if (!frame || typeof frame !== 'object') return null;
    // DSH WebSocket carrier: { type: 'server-request', rpcId, method, payload }.
    if (frame.type === 'server-request' && frame.payload && typeof frame.payload === 'object') return frame.payload;
    // Accept a future lightweight envelope without teaching the view a second protocol.
    if (frame.payload && typeof frame.payload === 'object' && frame.payload.type) return frame.payload;
    return frame;
  }

  function numericSeq(value) {
    const seq = Number(value);
    return Number.isSafeInteger(seq) && seq >= 0 ? seq : null;
  }

  function snapshotSeq(payload) {
    if (!payload || typeof payload !== 'object') return null;
    return numericSeq(payload.lastSeq != null ? payload.lastSeq
      : payload.eventSeq != null ? payload.eventSeq
        : payload.seq);
  }

  function noteSnapshotWaterline(payload) {
    const seq = snapshotSeq(payload);
    if (seq == null) return;
    state.lastEventSeq = Math.max(state.lastEventSeq, seq);
    state.eventBaselineKnown = true;
  }

  function openLiveStream(id) {
    if (!window.WebSocket || !id || state.sessionId !== id) return;
    if (state.live && (state.live.readyState === WebSocket.OPEN || state.live.readyState === WebSocket.CONNECTING)) return;
    const socket = new WebSocket(eventSocketUrl(id));
    state.live = socket;
    socket.onopen = () => {
      if (state.live !== socket || state.sessionId !== id) return;
      state.liveReconnectAttempt = 0;
      setConnection('online', '实时事件通道已连接');
    };
    socket.onmessage = (wire) => {
      if (state.live !== socket || state.sessionId !== id) return;
      try {
        const frame = unpackEventFrame(JSON.parse(String(wire.data || 'null')));
        handleEventFrame(frame, id);
      } catch (_) {
        // A malformed frame must not tear down a healthy stream.  The next
        // ordered event still provides an opportunity to recover the tail.
      }
    };
    socket.onerror = () => {
      // Browsers always follow an error with close.  Reconnect there so one
      // failed opening does not create duplicate timers.
    };
    socket.onclose = () => {
      if (state.live !== socket || state.sessionId !== id) return;
      state.live = null;
      setConnection('reconnecting');
      scheduleLiveReconnect(id);
    };
  }

  function recoverEventGap(id, incomingSeq) {
    if (state.eventGapRecovering || state.sessionId !== id) return;
    state.eventGapRecovering = true;
    setConnection('reconnecting', '正在补齐会话事件…');
    closeLiveStream();
    (async () => {
      try {
        // A regular REST history only contains persisted messages; it can be
        // behind a provider token/tool tail.  Deliberately discard the old
        // cursor so the next socket opens with snapshot=1 and receives the
        // server's atomic, event-sourced snapshot + waterline.  Using an
        // observed sequence as a synthetic REST watermark would skip exactly
        // the unpersisted delta that caused this recovery.
        state.lastEventSeq = 0;
        state.eventBaselineKnown = false;
        await hydrateConversation();
      } finally {
        state.eventGapRecovering = false;
        if (state.sessionId === id) openLiveStream(id);
      }
    })();
  }

  function handleEventFrame(frame, id) {
    if (!frame || typeof frame !== 'object') return;
    if (frame.sessionId && String(frame.sessionId) !== String(id)) return;
    if (frame.type === 'stream/error') {
      setConnection('reconnecting', '实时通道需要恢复');
      return;
    }
    if (frame.type === 'session/snapshot') {
      const snapshot = frame.snapshot || frame.data || frame;
      applySnapshot(snapshot);
      noteSnapshotWaterline(frame);
      noteSnapshotWaterline(snapshot);
      return;
    }
    if (frame.type === 'session/subscribed') {
      const lastSeq = numericSeq(frame.lastSeq);
      // In the native protocol this control frame either follows an atomic
      // snapshot or tells an old client that it is now live-only.  Preserve a
      // known REST waterline; only establish a compatibility baseline when
      // no waterline exists at all.
      if (!state.eventBaselineKnown && lastSeq != null) {
        state.lastEventSeq = lastSeq;
        state.eventBaselineKnown = true;
      }
      if (frame.reset || frame.truncated) recoverEventGap(id, lastSeq == null ? state.lastEventSeq + 1 : lastSeq + 1);
      return;
    }
    // Compatibility with the first native event bridge.  The final endpoint
    // uses `session/subscribed` / `session/snapshot`; accepting this control
    // frame lets a mixed-version phone avoid treating its first live event as
    // a gap during an upgrade.
    if (frame.type === 'session/ready') {
      const ready = eventObject(frame.data);
      const latestSeq = numericSeq(ready.latestSeq);
      if (!state.eventBaselineKnown && latestSeq != null) {
        state.lastEventSeq = latestSeq;
        state.eventBaselineKnown = true;
      }
      return;
    }
    if (frame.type === 'session/reset') {
      const reset = eventObject(frame.data);
      const latestSeq = numericSeq(reset.latestSeq);
      recoverEventGap(id, (latestSeq == null ? state.lastEventSeq : latestSeq) + 1);
      return;
    }
    if (frame.type !== 'session/event' || !frame.event || typeof frame.event !== 'object') {
      handleControlEvent(frame);
      return;
    }
    const event = frame.event;
    const seq = numericSeq(event.seq);
    if (seq != null) {
      if (seq <= state.lastEventSeq) return;
      if (state.lastEventSeq > 0 && seq > state.lastEventSeq + 1) {
        recoverEventGap(id, seq);
        return;
      }
      state.lastEventSeq = seq;
      state.eventBaselineKnown = true;
    }
    applySessionEvent(event, frame.view);
  }

  function eventObject(value) {
    return value && typeof value === 'object' && !Array.isArray(value) ? value : {};
  }

  function copyDefined(target, source, keys) {
    keys.forEach((key) => {
      if (source[key] !== undefined) target[key] = source[key];
    });
    return target;
  }

  function projectContentBlocks(value) {
    const projected = { content: '', reasoning: '', toolCalls: [] };
    if (typeof value === 'string') {
      projected.content = value;
      return projected;
    }
    if (!Array.isArray(value)) return projected;
    value.forEach((block, index) => {
      const item = eventObject(block);
      const kind = String(item.type || item.kind || '').toLowerCase();
      if (kind === 'text') {
        projected.content += text(item.text != null ? item.text : item.value);
        return;
      }
      if (kind === 'reasoning' || kind === 'thinking') {
        projected.reasoning += text(item.text != null ? item.text : item.value);
        return;
      }
      if (kind === 'tool-call' || kind === 'tool_use' || kind === 'tool-use') {
        const callId = item.callId || item.toolUseId || item.id || ('block-' + index);
        projected.toolCalls.push({
          id: callId,
          toolUseId: callId,
          callId,
          name: item.name || item.toolName || 'tool',
          toolName: item.toolName || item.name || 'tool',
          input: item.input != null ? item.input : (item.arguments != null ? item.arguments : item.argsRaw),
          arguments: item.arguments != null ? item.arguments : item.argsRaw,
        });
      }
    });
    return projected;
  }

  function messagePayload(data, fallbackRole, fallbackId) {
    const envelope = eventObject(data);
    const nested = eventObject(envelope.message);
    const raw = Object.keys(nested).length ? nested : envelope;
    const contentValue = raw.content !== undefined ? raw.content : envelope.content;
    const blocks = projectContentBlocks(contentValue);
    const result = {};
    // `messageId` is the live-node identity supplied by the native event
    // bridge.  On a DSH `assistant/message` it deliberately wins over the
    // persisted message id so the partial node does not remount on settle.
    const id = envelope.messageId || raw.messageId || raw.id || fallbackId;
    if (id != null && id !== '') result.id = String(id);
    const role = raw.role || envelope.role || fallbackRole;
    if (role) result.role = String(role);
    if (typeof contentValue === 'string') result.content = contentValue;
    else if (blocks.content) result.content = blocks.content;
    const reasoning = raw.reasoningContent !== undefined ? raw.reasoningContent
      : raw.reasoning !== undefined ? raw.reasoning
        : envelope.reasoningContent !== undefined ? envelope.reasoningContent
          : envelope.reasoning;
    if (reasoning !== undefined) result.reasoningContent = text(reasoning);
    else if (blocks.reasoning) result.reasoningContent = blocks.reasoning;
    copyDefined(result, raw, ['attachments', 'toolCalls', 'toolResults', 'liveBlocks', 'error', 'isStreaming', 'createdAt']);
    copyDefined(result, envelope, ['attachments', 'toolCalls', 'toolResults', 'liveBlocks', 'error', 'isStreaming', 'createdAt']);
    if ((!Array.isArray(result.toolCalls) || !result.toolCalls.length) && blocks.toolCalls.length) result.toolCalls = blocks.toolCalls;
    return result;
  }

  function mergeMessage(payload, position) {
    if (!payload || !payload.id) return null;
    const id = String(payload.id);
    const index = state.messages.findIndex((item) => String(item.id) === id);
    if (index === -1) {
      const message = Object.assign({ id, role: payload.role || 'assistant', content: '' }, payload);
      const at = Number(position);
      if (Number.isInteger(at) && at >= 0 && at < state.messages.length) state.messages.splice(at, 0, message);
      else state.messages.push(message);
      return message;
    }
    const message = state.messages[index];
    Object.keys(payload).forEach((key) => {
      if (payload[key] !== undefined) message[key] = payload[key];
    });
    return message;
  }

  function eventMessageId(data, fallback) {
    const source = eventObject(data);
    const nested = eventObject(source.message);
    const id = source.messageId || source.assistantMessageId || nested.id || source.id;
    if (id != null && id !== '') return String(id);
    if (source.turn != null && source.step != null) return 'stream-' + source.turn + '-' + source.step;
    return fallback || null;
  }

  function assistantStreamId(data) {
    const source = eventObject(data);
    if (source.messageId != null && source.messageId !== '') return String(source.messageId);
    if (source.assistantMessageId != null && source.assistantMessageId !== '') return String(source.assistantMessageId);
    if (source.turn != null && source.step != null) return 'stream-' + source.turn + '-' + source.step;
    return eventMessageId(source);
  }

  function ensureAssistantMessage(id) {
    const key = id || ('stream-local-' + Date.now());
    return mergeMessage({ id: key, role: 'assistant', content: '', isStreaming: true });
  }

  function lastAssistantMessageId() {
    for (let index = state.messages.length - 1; index >= 0; index -= 1) {
      if (state.messages[index].role === 'assistant') return String(state.messages[index].id || '');
    }
    return '';
  }

  function publishPatchedMessage(message, options) {
    if (!message || !isVisibleMessage(message)) return;
    const opt = options || {};
    const reader = $('#conversationScroll');
    const atBottom = reader.scrollHeight - reader.scrollTop - reader.clientHeight < 90;
    const root = $('#chatFlow');
    const key = String(message.id);
    let node = state.nodeById.get(key);
    if (!node) {
      node = makeMessageNode(message, key);
      // Session events are append-only.  A replayed older event only updates
      // its existing keyed node; it never reconstructs the conversation.
      root.appendChild(node);
    }
    const resultMap = resultsByCall(state.messages);
    applyMessageNode(node, message, lastAssistantMessageId(), resultMap);
    $('#app').dataset.phase = state.messages.some(isVisibleMessage) ? 'active' : 'blank';
    if (atBottom || opt.forceScroll) reader.scrollTop = reader.scrollHeight;
    if (opt.activity) renderWorkspaceActivity();
    if (opt.details && state.detailsOpen && state.detailsView !== 'task') renderDetails();
    updateBackToBottom();
  }

  function removePatchedMessage(id) {
    const key = String(id || '');
    const index = state.messages.findIndex((item) => String(item.id) === key);
    if (index >= 0) state.messages.splice(index, 1);
    const node = state.nodeById.get(key);
    if (node) node.remove();
    state.nodeById.delete(key);
    $('#app').dataset.phase = state.messages.some(isVisibleMessage) ? 'active' : 'blank';
  }

  function toolIdentity(call, fallback) {
    const source = eventObject(call);
    const id = source.toolUseId || source.callId || source.id || fallback;
    return id == null || id === '' ? null : String(id);
  }

  function toolCallPayload(data) {
    const source = eventObject(data);
    const raw = eventObject(source.call);
    const toolCall = eventObject(source.toolCall);
    const tool = eventObject(source.tool);
    const candidate = Object.keys(raw).length ? raw : (Object.keys(toolCall).length ? toolCall : tool);
    const id = toolIdentity(candidate, source.callId || source.toolUseId || source.id);
    if (!id) return null;
    const call = Object.assign({}, candidate, { id });
    call.toolUseId = call.toolUseId || call.callId || id;
    call.callId = call.callId || call.toolUseId || id;
    call.name = call.name || call.toolName || source.name || source.toolName || (typeof source.tool === 'string' ? source.tool : 'tool');
    call.toolName = call.toolName || call.name;
    if (call.input === undefined) call.input = call.toolArgs !== undefined ? call.toolArgs
      : call.arguments !== undefined ? call.arguments
        : source.input !== undefined ? source.input
          : source.toolArgs !== undefined ? source.toolArgs : source.arguments;
    if (source.toolStatus !== undefined && call.toolStatus === undefined) call.toolStatus = source.toolStatus;
    if (source.status !== undefined && call.toolStatus === undefined) call.toolStatus = source.status;
    if (source.state !== undefined && call.toolStatus === undefined) call.toolStatus = source.state;
    return call;
  }

  function toolResultPayload(data) {
    const source = eventObject(data);
    const raw = eventObject(source.result);
    const message = eventObject(source.message);
    const result = Object.assign({}, Object.keys(raw).length ? raw : message);
    const resultBlock = Array.isArray(result.content)
      ? result.content.find((item) => eventObject(item).type === 'tool-result') : null;
    const block = eventObject(resultBlock);
    // A canonical DSH tool/result carries a *message* id, while its call
    // correlation lives at message.source.callId / block.toolCallId.  Never
    // use that message id as the tool row key.
    const sourceMeta = eventObject(result.source);
    const id = result.toolUseId || result.callId || block.toolCallId || sourceMeta.callId
      || source.callId || source.toolUseId || source.id || result.id;
    if (!id) return null;
    result.id = result.id || id;
    result.toolUseId = result.toolUseId || result.callId || block.toolCallId || sourceMeta.callId || id;
    result.callId = result.callId || result.toolUseId || id;
    if (result.output === undefined && source.output !== undefined) result.output = source.output;
    if (result.output === undefined && block.content !== undefined) {
      const projected = projectContentBlocks(block.content);
      result.output = projected.content || text(block.content);
    }
    if (result.output === undefined && result.content !== undefined) {
      const projected = projectContentBlocks(result.content);
      result.output = projected.content || text(result.content);
    }
    if (result.error === undefined && source.error !== undefined) result.error = source.error;
    if (result.toolStatus === undefined && source.status !== undefined) result.toolStatus = source.status;
    if (result.isError === undefined && block.isError !== undefined) result.isError = block.isError;
    if (result.success === undefined && result.isError !== undefined) result.success = !result.isError;
    if (result.toolStatus === undefined && source.toolStatus !== undefined) result.toolStatus = source.toolStatus;
    if (result.success === undefined && source.success !== undefined) result.success = source.success;
    return result;
  }

  function mergeToolCall(message, call) {
    if (!message || !call) return null;
    const id = toolIdentity(call);
    if (!id) return null;
    const calls = Array.isArray(message.toolCalls) ? message.toolCalls.slice() : [];
    const index = calls.findIndex((item) => toolIdentity(item) === id);
    if (index < 0) calls.push(call);
    else calls[index] = Object.assign({}, calls[index], call);
    message.toolCalls = calls;
    return calls[index < 0 ? calls.length - 1 : index];
  }

  function mergeToolResult(message, result) {
    if (!message || !result) return null;
    const id = toolIdentity(result);
    if (!id) return null;
    const results = Array.isArray(message.toolResults) ? message.toolResults.slice() : [];
    const index = results.findIndex((item) => toolIdentity(item) === id);
    if (index < 0) results.push(result);
    else results[index] = Object.assign({}, results[index], result);
    message.toolResults = results;
    return results[index < 0 ? results.length - 1 : index];
  }

  function applyRunState(data, running) {
    const source = eventObject(data);
    const nested = eventObject(source.status);
    const next = Object.assign({}, state.status || {}, nested, source);
    delete next.status;
    if (running !== undefined) next.isRunning = !!running;
    else if (typeof next.running === 'boolean' && typeof next.isRunning !== 'boolean') next.isRunning = next.running;
    state.status = next;
    state.running = !!next.isRunning;
    if (next.thinkingLevel) {
      state.thinkingLevel = String(next.thinkingLevel).toLowerCase();
      sessionStorage.setItem('openminis.thinking.' + state.sessionId, state.thinkingLevel);
    }
    updateHeaderFromSession();
    updateRunChrome();
  }

  function streamBlocksFor(message) {
    if (!Array.isArray(message._streamBlocks)) message._streamBlocks = [];
    return message._streamBlocks;
  }

  function projectStreamBlocks(message) {
    const blocks = streamBlocksFor(message);
    let content = '';
    let reasoning = '';
    const calls = [];
    blocks.forEach((block, index) => {
      if (!block) return;
      if (block.type === 'text') content += text(block.text);
      if (block.type === 'reasoning') reasoning += text(block.text);
      if (block.type === 'tool-call') {
        const id = String(block.id || ('stream-tool-' + index));
        calls.push({
          id,
          toolUseId: id,
          callId: id,
          name: block.name || 'tool',
          toolName: block.name || 'tool',
          arguments: block.arguments || '',
          toolStatus: 'STREAMING',
        });
      }
    });
    message.content = content;
    message.reasoningContent = reasoning;
    if (calls.length) message.toolCalls = calls;
  }

  function applyDshChunk(data) {
    const source = eventObject(data);
    const chunk = eventObject(source.chunk);
    if (!chunk.type) return;
    const message = ensureAssistantMessage(assistantStreamId(source));
    const blocks = streamBlocksFor(message);
    const index = Number.isInteger(Number(chunk.index)) ? Number(chunk.index)
      : (chunk.type === 'reasoning-delta' ? 1 : 0);
    if (chunk.type === 'block-start') {
      blocks[index] = { type: chunk.blockType || 'other' };
      message.isStreaming = true;
      return;
    }
    if (chunk.type === 'text-delta') {
      const previous = eventObject(blocks[index]);
      blocks[index] = { type: 'text', text: text(previous.text) + text(chunk.text) };
      projectStreamBlocks(message);
      message.isStreaming = true;
      publishPatchedMessage(message);
      return;
    }
    if (chunk.type === 'reasoning-delta') {
      const previous = eventObject(blocks[index]);
      blocks[index] = { type: 'reasoning', text: text(previous.text) + text(chunk.text) };
      projectStreamBlocks(message);
      message.isStreaming = true;
      publishPatchedMessage(message);
      return;
    }
    if (chunk.type === 'tool-call-delta' || chunk.type === 'tool-input-delta') {
      const previous = eventObject(blocks[index]);
      blocks[index] = {
        type: 'tool-call',
        id: chunk.toolUseId || chunk.id || previous.id || ('stream-tool-' + index),
        name: chunk.name || previous.name || 'tool',
        arguments: text(previous.arguments) + text(chunk.argumentsDelta != null ? chunk.argumentsDelta : chunk.text),
      };
      projectStreamBlocks(message);
      message.isStreaming = true;
      publishPatchedMessage(message, { activity: true, details: true });
      return;
    }
    if (chunk.type === 'tool-output-delta' || chunk.type === 'tool-result-delta') {
      const id = String(chunk.toolUseId || chunk.id || ('stream-tool-' + index));
      const priorCall = (Array.isArray(message.toolCalls) ? message.toolCalls : []).find((item) => toolIdentity(item) === id) || {};
      mergeToolCall(message, Object.assign({}, priorCall, {
        id,
        toolUseId: id,
        callId: id,
        name: chunk.name || priorCall.name || priorCall.toolName || 'tool',
        toolName: chunk.name || priorCall.toolName || priorCall.name || 'tool',
        toolStatus: 'RUNNING',
      }));
      const priorResult = (Array.isArray(message.toolResults) ? message.toolResults : []).find((item) => toolIdentity(item) === id) || {};
      mergeToolResult(message, Object.assign({}, priorResult, {
        id,
        toolUseId: id,
        callId: id,
        name: priorCall.name || priorCall.toolName || 'tool',
        toolName: priorCall.toolName || priorCall.name || 'tool',
        output: text(priorResult.output) + text(chunk.text),
        toolStatus: 'RUNNING',
      }));
      message.isStreaming = true;
      publishPatchedMessage(message, { activity: true, details: true });
      return;
    }
    if (chunk.type === 'block-end') {
      const finalBlock = eventObject(chunk.block);
      if (finalBlock.type) blocks[index] = finalBlock;
      projectStreamBlocks(message);
      message.isStreaming = true;
      publishPatchedMessage(message, { activity: finalBlock.type === 'tool-call', details: finalBlock.type === 'tool-call' });
    }
  }

  function applySessionEvent(event, view) {
    const type = String(event.type || '');
    const data = eventObject(event.data);
    if (type === 'message.created' || type === 'message.updated') {
      const message = mergeMessage(messagePayload(data, data.role || 'assistant', eventMessageId(data)), data.position);
      if (message) publishPatchedMessage(message, { forceScroll: type === 'message.created' && message.role === 'user' });
      return;
    }
    if (type === 'message.deleted') {
      removePatchedMessage(eventMessageId(data));
      return;
    }
    if (type === 'assistant.delta') {
      const message = ensureAssistantMessage(eventMessageId(data));
      const delta = data.delta !== undefined ? data.delta : (data.textDelta !== undefined ? data.textDelta : data.text);
      const reasoning = data.reasoningDelta !== undefined ? data.reasoningDelta : data.reasoning;
      if (delta !== undefined) message.content = contentOf(message) + text(delta);
      if (reasoning !== undefined) message.reasoningContent = text(message.reasoningContent) + text(reasoning);
      message.isStreaming = true;
      publishPatchedMessage(message);
      return;
    }
    if (type === 'assistant.replace' || type === 'assistant/replace') {
      const id = assistantStreamId(data);
      const message = mergeMessage(messagePayload(data, 'assistant', id)) || ensureAssistantMessage(id);
      if (data.content !== undefined) message.content = text(data.content);
      if (data.reasoning !== undefined || data.reasoningContent !== undefined) message.reasoningContent = text(data.reasoningContent !== undefined ? data.reasoningContent : data.reasoning);
      message.isStreaming = data.isStreaming === undefined ? true : !!data.isStreaming;
      publishPatchedMessage(message, { activity: !!data.toolCalls || !!data.toolResults, details: !!data.toolCalls || !!data.toolResults });
      return;
    }
    if (type === 'message.settled') {
      const id = eventMessageId(data);
      const message = mergeMessage(messagePayload(data, 'assistant', id)) || ensureAssistantMessage(id);
      if (data.content !== undefined) message.content = text(data.content);
      if (data.reasoning !== undefined || data.reasoningContent !== undefined) message.reasoningContent = text(data.reasoningContent !== undefined ? data.reasoningContent : data.reasoning);
      message.isStreaming = false;
      publishPatchedMessage(message, { activity: true, details: true });
      scheduleAgentRefresh();
      scheduleUsageRefresh();
      return;
    }
    if (type === 'tool.started' || type === 'tool.updated' || type === 'tool.completed' || type === 'tool/status') {
      const message = ensureAssistantMessage(assistantStreamId(data));
      const call = toolCallPayload(data);
      if (call) {
        if (type === 'tool.started' && !call.toolStatus) call.toolStatus = 'RUNNING';
        if (type === 'tool.completed' && !call.toolStatus) call.toolStatus = data.success === false || data.error ? 'FAILED' : 'SUCCESS';
        mergeToolCall(message, call);
      }
      if (type !== 'tool.started') {
        const result = toolResultPayload(data);
        if (result) mergeToolResult(message, result);
      }
      message.isStreaming = type !== 'tool.completed' || state.running;
      publishPatchedMessage(message, { activity: true, details: true });
      return;
    }
    // Canonical DSH session log vocabulary maps directly onto the same local
    // projection.  Keeping this path lets the view consume a future raw DSH
    // bridge without inventing a second token protocol.
    if (type === 'assistant/chunk') {
      applyDshChunk(data);
      return;
    }
    if (type === 'user/message' || type === 'assistant/message') {
      const role = type === 'user/message' ? 'user' : 'assistant';
      const stableId = role === 'assistant' ? assistantStreamId(data) : eventMessageId(data);
      const payload = messagePayload(data, role, stableId);
      if (role === 'assistant' && stableId) {
        // Raw DSH events carry turn/step; that is the stable partial node key.
        payload.id = stableId;
      }
      const message = mergeMessage(payload);
      if (message) {
        message.isStreaming = false;
        publishPatchedMessage(message, { forceScroll: role === 'user', activity: role === 'assistant', details: role === 'assistant' });
      }
      return;
    }
    if (type === 'tool/call' || type === 'tool/result') {
      const message = ensureAssistantMessage(assistantStreamId(data));
      if (type === 'tool/call') {
        const call = toolCallPayload(data);
        if (call) mergeToolCall(message, call);
      } else {
        const result = toolResultPayload(data);
        if (result) {
          const calls = Array.isArray(message.toolCalls) ? message.toolCalls : [];
          if (!calls.some((item) => toolIdentity(item) === toolIdentity(result))) {
            mergeToolCall(message, {
              id: toolIdentity(result), toolUseId: toolIdentity(result), callId: toolIdentity(result),
              name: data.name || 'tool', toolName: data.name || 'tool', toolStatus: result.success === false ? 'FAILED' : 'SUCCESS',
            });
          }
          mergeToolResult(message, result);
        }
      }
      publishPatchedMessage(message, { activity: true, details: true });
      return;
    }
    if (type === 'run.started' || type === 'turn/start') {
      applyRunState(data, true);
      return;
    }
    if (type === 'run.status' || type === 'turn/status') {
      applyRunState(data);
      return;
    }
    if (type === 'run.completed' || type === 'turn/end') {
      applyRunState(data, false);
      scheduleAgentRefresh();
      scheduleUsageRefresh();
      return;
    }
    if (type === 'session/title') {
      applyRunState({ title: data.title });
      return;
    }
    if (type === 'todo/write') {
      state.agent.todos = Array.isArray(data.todos) ? data.todos : state.agent.todos;
      if (state.detailsOpen && state.detailsView === 'task' && !$('#detailsContent :focus')) renderDetails();
      return;
    }
    if (type === 'goal/change' || type === 'plan/mode' || type === 'approval/asked' || type === 'approval/decided') {
      scheduleAgentRefresh();
      if (type.indexOf('approval/') === 0) refreshTakeover().catch(() => {});
      return;
    }
    // Unknown ignorable events still advance seq in handleEventFrame.  They
    // must not trigger a transcript reload, otherwise an extension event can
    // make a long running turn flicker.
    if (view) scheduleAgentRefresh();
  }

  function handleControlEvent(frame) {
    const type = String(frame.type || '');
    if (type === 'host/session-status') {
      applyRunState(frame, !!frame.running);
      return;
    }
    if (type === 'session/projection' && frame.key === 'title') {
      applyRunState({ title: frame.value });
      return;
    }
    if (type === 'session/jobs') {
      state.agent.jobs = Array.isArray(frame.jobs) ? frame.jobs : [];
      if (state.detailsOpen && state.detailsView === 'task' && !$('#detailsContent :focus')) renderDetails();
      return;
    }
    if (type === 'question/requested' || type === 'approval/requested') {
      refreshTakeover().catch(() => {});
      return;
    }
    if (type === 'question/resolved' || type === 'approval/resolved') {
      refreshTakeover().catch(() => {});
    }
  }

  function applySnapshot(payload) {
    if (!payload || !state.sessionId) return;
    noteSnapshotWaterline(payload);
    // A retained event tail only knows model/thinking after it has observed a
    // turn.  Keep the just-hydrated durable session metadata when an idle
    // snapshot therefore omits those optional fields; never let a sparse
    // event projection erase a real model label in the header.
    const status = Object.assign({}, state.status || {}, eventObject(payload.status));
    if (!status.modelName && payload.modelName) status.modelName = payload.modelName;
    if (!status.thinkingLevel && payload.thinkingLevel) status.thinkingLevel = payload.thinkingLevel;
    state.status = status;
    state.running = !!status.isRunning;
    if (payload.thinkingLevel) {
      state.thinkingLevel = String(payload.thinkingLevel).toLowerCase();
      sessionStorage.setItem('openminis.thinking.' + state.sessionId, state.thinkingLevel);
    }
    const messages = Array.isArray(payload.messages) ? payload.messages : [];
    reconcileMessages(messages);
    updateHeaderFromSession();
    updateRunChrome();
    scheduleAgentRefresh();
    scheduleUsageRefresh();
  }

  function updateRunChrome() {
    $('#stopButton').classList.toggle('hidden', !state.running);
    $('#sendButton').classList.toggle('hidden', state.running);
    $('#composerStatus').textContent = state.running ? '手机 Agent 正在生成，实时更新中' : '与手机上的会话实时同步';
    $('#prompt').disabled = !!state.question || !!state.approval;
  }

  function renderBlankConversation() {
    state.messages = [];
    state.nodeById.clear();
    $('#chatFlow').innerHTML = '';
    $('#app').dataset.phase = 'blank';
    updateHeaderFromSession();
    updateRunChrome();
  }

  function contentOf(message) {
    if (message && message.content != null) return text(message.content);
    if (!message || !Array.isArray(message.parts)) return '';
    return message.parts.filter((part) => part && part.type === 'text')
      .map((part) => text(part.value == null ? part.text : part.value)).join('');
  }

  function liveCalls(message) {
    if (Array.isArray(message.toolCalls) && message.toolCalls.length) return message.toolCalls;
    if (!Array.isArray(message.liveBlocks)) return [];
    return message.liveBlocks.filter((block) => block && block.kind === 'tool_use').map((block) => ({
      id: block.id,
      toolUseId: block.id,
      name: block.toolName,
      toolName: block.toolName,
      description: block.toolTitle,
      input: block.toolArgs,
      toolStatus: block.toolStatus,
      durationMs: block.durationMs,
      raw: block,
    }));
  }

  function liveResults(message) {
    const persisted = Array.isArray(message.toolResults) ? message.toolResults : [];
    if (persisted.length) return persisted;
    if (!Array.isArray(message.liveBlocks)) return [];
    return message.liveBlocks.filter((block) => block && block.kind === 'tool_use' && block.toolStatus)
      .filter((block) => ['SUCCESS', 'FAILED', 'TIMEOUT', 'CANCELLED'].includes(String(block.toolStatus)))
      .map((block) => ({
        id: block.id,
        toolUseId: block.id,
        name: block.toolName,
        toolName: block.toolName,
        output: block.content,
        success: String(block.toolStatus) === 'SUCCESS',
        toolStatus: block.toolStatus,
        raw: block,
      }));
  }

  function resultsByCall(messages) {
    const result = new Map();
    messages.forEach((message) => {
      liveResults(message).forEach((item) => {
        const id = item.toolUseId || item.id || item.callId;
        if (id) result.set(String(id), item);
      });
    });
    return result;
  }

  function isVisibleMessage(message) {
    if (!message) return false;
    if (message.role === 'user' || message.role === 'assistant') return true;
    return !!contentOf(message) || liveCalls(message).length > 0 || liveResults(message).length > 0;
  }

  function messageKey(message, index) {
    return String(message.id || ('ephemeral-' + index + '-' + (message.role || 'unknown')));
  }

  function makeMessageNode(message, key) {
    const node = document.createElement('article');
    node.className = 'flow-item message-node ' + (message.role === 'user' ? 'user' : 'assistant');
    node.dataset.messageId = key;
    if (message.role === 'user') {
      node.innerHTML = '<div class="user-stack"><div class="user-bubble" data-message-body=""></div><div class="message-attachments" data-attachments=""></div></div>';
    } else {
      node.innerHTML = '<div class="assistant-stack">' +
        '<details class="reasoning-row hidden" data-reasoning=""><summary>思考过程</summary><div class="reasoning-body"></div></details>' +
        '<div class="assistant-content" data-message-body=""></div>' +
        '<div class="tool-stack" data-tools=""></div>' +
        '<div class="message-error hidden" data-message-error=""></div>' +
        '<div class="assistant-meta"><button class="message-action" data-message-action="copy">复制</button><button class="message-action" data-message-action="retry">重试</button><button class="message-action" data-feedback="up">有用</button><button class="message-action" data-feedback="down">不满意</button></div>' +
      '</div>';
    }
    state.nodeById.set(key, node);
    return node;
  }

  function updateAttachments(node, attachments) {
    const root = $('[data-attachments]', node);
    if (!root) return;
    const list = Array.isArray(attachments) ? attachments : [];
    const sig = list.map((item) => typeof item === 'string' ? item : (item.name || item.fileName || '')).join('\u0000');
    if (root.dataset.sig === sig) return;
    root.dataset.sig = sig;
    root.innerHTML = list.map((item) => {
      const name = typeof item === 'string' ? item : (item.name || item.fileName || '附件');
      return '<span class="attachment-tag">⌁ ' + esc(name) + '</span>';
    }).join('');
  }

  function updateReasoning(node, value) {
    const detail = $('[data-reasoning]', node);
    if (!detail) return;
    const reasoning = text(value).trim();
    if (!reasoning) {
      detail.classList.add('hidden');
      return;
    }
    const body = $('.reasoning-body', detail);
    if (body.dataset.sig !== reasoning) {
      body.textContent = reasoning;
      body.dataset.sig = reasoning;
    }
    detail.classList.remove('hidden');
  }

  function toolTitle(call) {
    const name = call.description || call.toolTitle;
    if (name) return name;
    const raw = call.name || call.toolName || 'tool';
    const labels = {
      shell_execute: '运行终端命令',
      file_read: '读取文件',
      file_write: '写入文件',
      file_edit: '编辑文件',
      browser_use: '浏览器操作',
      web_search: '搜索网页',
      ask_user_question: '等待用户回答',
      todo_write: '更新待办',
    };
    return labels[raw] || raw.replace(/[_-]+/g, ' ');
  }

  function toolStatus(call, result) {
    const raw = String((call && call.toolStatus) || (result && result.toolStatus) || '').toUpperCase();
    if (raw === 'RUNNING' || raw === 'STREAMING' || raw === 'PENDING') return 'running';
    if (raw === 'FAILED' || raw === 'TIMEOUT' || raw === 'CANCELLED') return 'failed';
    if (result && (result.success === false || result.isError)) return 'failed';
    return result || raw === 'SUCCESS' ? 'done' : (state.running ? 'running' : 'done');
  }

  function toolStatusText(status, result) {
    if (status === 'running') return '进行中';
    if (status === 'failed') return result && (result.error || result.output) ? '失败' : '已停止';
    return '完成';
  }

  function syncTools(node, message, resultMap) {
    const root = $('[data-tools]', node);
    if (!root) return;
    const calls = liveCalls(message);
    const existing = new Map();
    Array.from(root.children).forEach((item) => existing.set(item.dataset.callId, item));
    calls.forEach((call, index) => {
      const id = String(call.toolUseId || call.id || call.callId || ('call-' + index));
      const result = resultMap.get(id);
      let row = existing.get(id);
      if (!row) {
        row = document.createElement('button');
        row.type = 'button';
        row.className = 'tool-row';
        row.dataset.callId = id;
        row.innerHTML = '<span class="tool-row-dot"></span><span class="tool-row-copy"><strong></strong><span></span></span><span class="tool-row-state"></span>';
      }
      const status = toolStatus(call, result);
      row.dataset.running = String(status === 'running');
      row.classList.toggle('selected', state.selectedTool && state.selectedTool.id === id);
      const dot = $('.tool-row-dot', row);
      dot.className = 'tool-row-dot ' + (status === 'running' ? 'running' : status === 'failed' ? 'failed' : '');
      const title = toolTitle(call);
      const summary = brief(call.input || call.toolArgs || call.arguments || (result && result.output) || '', 84);
      const titleEl = $('.tool-row-copy strong', row);
      const summaryEl = $('.tool-row-copy span', row);
      const stateEl = $('.tool-row-state', row);
      titleEl.textContent = title;
      summaryEl.textContent = summary || (status === 'running' ? '等待执行…' : '');
      stateEl.textContent = toolStatusText(status, result);
      row._tool = { id, call, result, status, messageId: message.id || '' };
      root.appendChild(row);
      existing.delete(id);
    });
    existing.forEach((row) => row.remove());
    root.classList.toggle('hidden', calls.length === 0);
  }

  function setAssistantBody(node, message, isLive) {
    const body = $('[data-message-body]', node);
    const value = contentOf(message);
    const signature = value + '\u0000' + String(isLive);
    if (body.dataset.signature === signature) return;
    body.dataset.signature = signature;
    body.classList.toggle('is-live', isLive);
    if (isLive) {
      // Text deltas preserve this body and cursor node.  The only mutation per
      // token is a Text node update, matching DSH's partial accumulator rather
      // than replacing the visible transcript subtree.
      let liveText = $('[data-live-text]', body);
      let cursor = $('.stream-cursor', body);
      if (!liveText || !cursor) {
        body.replaceChildren();
        liveText = document.createElement('span');
        liveText.dataset.liveText = 'true';
        cursor = document.createElement('span');
        cursor.className = 'stream-cursor';
        cursor.setAttribute('aria-hidden', 'true');
        body.appendChild(liveText);
        body.appendChild(cursor);
      }
      if (liveText.textContent !== value) liveText.textContent = value;
    } else {
      body.innerHTML = value ? markdown(value) : '';
    }
  }

  function updateFeedback(node, messageId) {
    const active = state.feedback.get(messageId);
    $$('[data-feedback]', node).forEach((button) => button.classList.toggle('active', button.dataset.feedback === active));
  }

  function applyMessageNode(node, message, lastAssistantId, resultMap) {
    if (message.role === 'user') {
      const body = $('[data-message-body]', node);
      const value = contentOf(message);
      if (body.dataset.sig !== value) {
        body.textContent = value;
        body.dataset.sig = value;
      }
      updateAttachments(node, message.attachments);
      return;
    }
    const id = String(message.id || '');
    const isLive = !!message.isStreaming || (state.running && id && id === lastAssistantId);
    updateReasoning(node, message.reasoningContent);
    setAssistantBody(node, message, isLive);
    syncTools(node, message, resultMap);
    const error = $('[data-message-error]', node);
    if (message.error) {
      error.textContent = text(message.error);
      error.classList.remove('hidden');
    } else {
      error.classList.add('hidden');
    }
    updateFeedback(node, id);
  }

  function reconcileMessages(rawMessages) {
    const reader = $('#conversationScroll');
    const atBottom = reader.scrollHeight - reader.scrollTop - reader.clientHeight < 90;
    const root = $('#chatFlow');
    const messages = rawMessages.filter(isVisibleMessage);
    const resultMap = resultsByCall(messages);
    const assistantIds = messages.filter((item) => item.role === 'assistant').map((item, index) => messageKey(item, index));
    const lastAssistantId = assistantIds.length ? assistantIds[assistantIds.length - 1] : '';
    const wanted = new Set();
    let cursor = root.firstChild;
    messages.forEach((message, index) => {
      const key = messageKey(message, index);
      wanted.add(key);
      let node = state.nodeById.get(key);
      if (!node) node = makeMessageNode(message, key);
      if (node !== cursor) root.insertBefore(node, cursor || null);
      applyMessageNode(node, message, lastAssistantId, resultMap);
      cursor = node.nextSibling;
    });
    Array.from(root.children).forEach((node) => {
      if (!wanted.has(node.dataset.messageId)) {
        state.nodeById.delete(node.dataset.messageId);
        node.remove();
      }
    });
    state.messages = messages;
    $('#app').dataset.phase = messages.length ? 'active' : 'blank';
    if (atBottom || (messages.length && messages[messages.length - 1].role === 'user')) {
      reader.scrollTop = reader.scrollHeight;
    }
    renderWorkspaceActivity();
    if (state.detailsOpen && state.detailsView !== 'task') renderDetails();
  }

  function updateBackToBottom() {
    const reader = $('#conversationScroll');
    $('#backToBottom').classList.toggle('hidden', reader.scrollHeight - reader.scrollTop - reader.clientHeight < 120);
  }

  /* ── composer, model selection, send/cancel ────────────────────────── */

  function autoGrow() {
    const input = $('#prompt');
    input.style.height = 'auto';
    input.style.height = Math.min(input.scrollHeight, 200) + 'px';
  }

  function thinkingLabel(level) {
    const found = THINKING_LEVELS.find((item) => item[0] === String(level).toLowerCase());
    return found ? found[1] : String(level);
  }

  function renderAttachmentChips() {
    const root = $('#attachmentChips');
    const files = state.attachFiles;
    if (!files.length) {
      root.classList.add('hidden');
      root.innerHTML = '';
      return;
    }
    root.classList.remove('hidden');
    root.innerHTML = files.map((file, index) => '<span class="attachment-chip"><span>⌁ ' + esc(file.name) + '</span><button data-attachment-remove="' + index + '" aria-label="移除附件">×</button></span>').join('');
  }

  function fileToAttachment(file) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve({
        name: file.name,
        data: String(reader.result).split(',')[1] || '',
        mime: file.type || 'application/octet-stream',
      });
      reader.onerror = reject;
      reader.readAsDataURL(file);
    });
  }

  async function sendPrompt() {
    const input = $('#prompt');
    const prompt = input.value.trim();
    if (!prompt || state.question || state.approval || state.running) return;
    $('#sendButton').disabled = true;
    try {
      const attachments = [];
      for (const file of state.attachFiles) attachments.push(await fileToAttachment(file));
      const body = {
        prompt,
        wait: false,
        thinkingLevel: state.thinkingLevel,
      };
      if (state.sessionId) body.sessionId = state.sessionId;
      if (attachments.length) body.attachments = attachments;
      const response = await api('/api/prompt', { method: 'POST', body: JSON.stringify(body) });
      input.value = '';
      autoGrow();
      state.attachFiles = [];
      renderAttachmentChips();
      if (!state.sessionId && response.sessionId) {
        await refreshSessions(false);
        await selectSession(response.sessionId);
      } else {
        await hydrateConversation();
      }
    } catch (error) {
      toast(error.message || '发送失败', 'error');
    } finally {
      $('#sendButton').disabled = false;
    }
  }

  async function cancelPrompt() {
    if (!state.sessionId) return;
    try {
      await api('/api/cancel', {
        method: 'POST',
        body: JSON.stringify({ sessionId: state.sessionId }),
      });
      $('#composerStatus').textContent = '正在停止手机 Agent…';
    } catch (error) {
      toast('停止失败：' + error.message, 'error');
    }
  }

  async function loadModelDirectory() {
    const result = await api('/api/models');
    state.modelEntries = result.entries || result.models || [];
    return result;
  }

  function visibleModel() {
    const session = state.sessions.find((item) => item.id === state.sessionId);
    const currentName = (state.status && state.status.modelName) || (session && (session.modelName || session.modelId)) || '';
    return state.modelEntries.find((entry) => entry.modelName === currentName || entry.modelId === currentName) || null;
  }

  function renderModelMenu() {
    const root = $('#modelMenu');
    const current = visibleModel();
    const currentName = (state.status && state.status.modelName) || (current && current.modelName) || '选择模型';
    if (state.modelMenuPane === 'root') {
      root.innerHTML =
        '<button class="model-menu-cell" data-model-menu="models"><span>模型</span><span class="value">' + esc(currentName) + '</span><span class="right">›</span></button>' +
        '<button class="model-menu-cell" data-model-menu="thinking"><span>思考强度</span><span class="value">' + esc(thinkingLabel(state.thinkingLevel)) + '</span><span class="right">›</span></button>';
      return;
    }
    if (state.modelMenuPane === 'thinking') {
      root.innerHTML = '<button class="model-menu-back" data-model-menu="root">‹ <span>返回</span></button>' +
        '<div class="model-menu-scroll">' + THINKING_LEVELS.map((item) =>
          '<button class="model-menu-option' + (state.thinkingLevel === item[0] ? ' selected' : '') + '" data-thinking="' + item[0] + '">' +
            '<span class="option-copy"><strong>' + esc(item[1]) + '</strong><small>' + (item[0] === 'off' ? '关闭推理' : '本会话的真实 Android 思考强度') + '</small></span>' +
            '<span class="check">' + (state.thinkingLevel === item[0] ? '✓' : '') + '</span></button>'
        ).join('') + '</div>';
      return;
    }
    const groups = new Map();
    state.modelEntries.forEach((entry) => {
      const name = entry.providerInstanceName || entry.providerName || entry.providerType || '模型';
      if (!groups.has(name)) groups.set(name, []);
      groups.get(name).push(entry);
    });
    root.innerHTML = '<button class="model-menu-back" data-model-menu="root">‹ <span>返回</span></button><div class="model-menu-scroll">' +
      Array.from(groups.entries()).map((group) =>
        '<section><div class="model-group-title">' + esc(group[0]) + '</div>' + group[1].map((entry) => {
          const selected = !!current && current.id === entry.id;
          const sub = [entry.modelId, entry.supportsReasoning ? '支持思考' : ''].filter(Boolean).join(' · ');
          return '<button class="model-menu-option' + (selected ? ' selected' : '') + '" data-model-entry="' + esc(entry.id) + '">' +
            '<span class="option-copy"><strong>' + esc(entry.modelName || entry.displayName || entry.id) + '</strong><small>' + esc(sub) + '</small></span><span class="check">' + (selected ? '✓' : '') + '</span></button>';
        }).join('') + '</section>'
      ).join('') + '</div>';
  }

  async function toggleModelMenu() {
    const menu = $('#modelMenu');
    if (!menu.classList.contains('hidden')) {
      closeModelMenu();
      return;
    }
    $('#modelButton').setAttribute('aria-expanded', 'true');
    menu.classList.remove('hidden');
    menu.innerHTML = '<div class="model-menu-empty">加载模型目录…</div>';
    try {
      await loadModelDirectory();
      state.modelMenuPane = 'root';
      renderModelMenu();
    } catch (error) {
      menu.innerHTML = '<div class="model-menu-empty">加载失败：' + esc(error.message) + '</div>';
    }
  }

  function closeModelMenu() {
    $('#modelMenu').classList.add('hidden');
    $('#modelButton').setAttribute('aria-expanded', 'false');
  }

  async function selectModel(entryId) {
    if (!state.sessionId) {
      toast('先创建或选择一个会话，再绑定模型。', 'error');
      return;
    }
    const button = $$('[data-model-entry]', $('#modelMenu')).find((item) => item.dataset.modelEntry === entryId);
    if (button) button.disabled = true;
    try {
      const response = await api('/api/session/model', {
        method: 'POST',
        body: JSON.stringify({ sessionId: state.sessionId, modelEntryId: entryId }),
      });
      if (response.thinkingLevel) {
        state.thinkingLevel = String(response.thinkingLevel).toLowerCase();
        sessionStorage.setItem('openminis.thinking.' + state.sessionId, state.thinkingLevel);
      }
      closeModelMenu();
      await refreshSessions(false);
      updateHeaderFromSession();
    } catch (error) {
      toast('切换模型失败：' + error.message, 'error');
      if (button) button.disabled = false;
    }
  }

  async function selectThinking(level) {
    const normalized = String(level).toLowerCase();
    if (!state.sessionId) {
      state.thinkingLevel = normalized;
      updateHeaderFromSession();
      renderModelMenu();
      return;
    }
    try {
      const response = await api('/api/session/thinking', {
        method: 'POST',
        body: JSON.stringify({ sessionId: state.sessionId, thinkingLevel: normalized }),
      });
      state.thinkingLevel = String(response.thinkingLevel || normalized).toLowerCase();
      sessionStorage.setItem('openminis.thinking.' + state.sessionId, state.thinkingLevel);
      updateHeaderFromSession();
      renderModelMenu();
    } catch (error) {
      toast('设置思考强度失败：' + error.message, 'error');
    }
  }

  async function compactConversation() {
    if (!state.sessionId) return;
    if (!window.confirm('压缩当前上下文？之前的对话会由摘要替代。')) return;
    const button = $('#compactBtn');
    button.disabled = true;
    button.textContent = '压缩中…';
    try {
      await api('/api/compact', {
        method: 'POST',
        body: JSON.stringify({ sessionId: state.sessionId, includesBoundary: true }),
      });
      await hydrateConversation();
    } catch (error) {
      toast('压缩失败：' + error.message, 'error');
    } finally {
      button.disabled = false;
      button.textContent = '压缩';
    }
  }

  /* ── questions/approval takeover ───────────────────────────────────── */

  async function refreshTakeover() {
    if (!state.sessionId) return;
    const pair = await Promise.all([
      rpc('chat.question.pending', { sessionId: state.sessionId }).catch(() => ({ questions: [] })),
      rpc('agent.approval.list', { sessionId: state.sessionId }).catch(() => ({ approvals: [] })),
    ]);
    const question = (pair[0].questions || [])[0] || null;
    const approval = (pair[1].approvals || [])[0] || null;
    const changed = (question && (!state.question || question.id !== state.question.id)) ||
      (approval && (!state.approval || approval.id !== state.approval.id)) ||
      (!question && state.question) || (!approval && state.approval);
    state.question = question;
    state.approval = approval;
    if (changed) renderTakeover();
    updateRunChrome();
  }

  function renderTakeover() {
    const root = $('#takeover');
    const question = state.question;
    const approval = state.approval;
    if (approval) {
      root.innerHTML = '<section class="takeover-card"><div class="takeover-head"><strong>需要操作批准</strong><button class="message-action" data-takeover="deny">拒绝</button></div><p class="takeover-copy">手机 Agent 希望执行一次可能影响文件或系统的操作。请核对后决定。</p><pre class="inspector-pre">' + esc((approval.tool || 'tool') + '\n' + (approval.summary || '')) + '</pre><div class="takeover-actions"><button class="button button-secondary" data-takeover="deny">拒绝</button><button class="button button-primary" data-takeover="allow">仅此一次允许</button></div></section>';
      return;
    }
    if (!question) {
      root.innerHTML = '';
      return;
    }
    const options = (question.options || []).map((item, index) =>
      '<label class="takeover-option"><input type="' + (question.multiple ? 'checkbox' : 'radio') + '" name="question_' + esc(question.id) + '" value="' + esc(item.value) + '"><span>' + esc(item.label || item.value) + (item.recommended ? '（推荐）' : '') + '</span></label>'
    ).join('');
    root.innerHTML = '<section class="takeover-card"><div class="takeover-head"><strong>Agent 正在等待你的回答</strong><button class="message-action" data-takeover="skip">跳过</button></div><p class="takeover-copy">' + esc(question.prompt || '') + '</p>' +
      (options ? '<div class="takeover-options">' + options + '</div>' : '') +
      (question.allowCustom === false ? '' : '<textarea id="questionCustom" class="takeover-custom" placeholder="补充自己的回答…"></textarea>') +
      '<div class="takeover-actions"><button class="button button-primary" data-takeover="answer">提交回答</button></div></section>';
  }

  async function answerQuestion(skip) {
    const question = state.question;
    if (!question) return;
    // Avoid CSS.escape: older Android WebViews can receive an otherwise valid
    // question id but do not expose that helper.  Filtering live controls is
    // safer and keeps the takeover answer path usable on those devices.
    const selected = $$('input:checked', $('#takeover'))
      .filter((input) => input.name === 'question_' + question.id)
      .map((input) => input.value);
    const custom = ($('#questionCustom') && $('#questionCustom').value.trim()) || '';
    if (!skip && !selected.length && !custom) {
      toast('选择一个答案或填写自定义回答。', 'error');
      return;
    }
    await rpc('chat.question.answer', {
      questionId: question.id,
      skipped: !!skip,
      selected,
      custom,
    });
    state.question = null;
    renderTakeover();
  }

  async function answerApproval(allowed) {
    const approval = state.approval;
    if (!approval) return;
    await rpc('agent.approval.answer', { approvalId: approval.id, allowed: !!allowed });
    state.approval = null;
    renderTakeover();
  }

  /* ── task/detail panels ────────────────────────────────────────────── */

  function scheduleAgentRefresh() {
    if (!state.sessionId || state.agentRefreshTimer) return;
    const wait = Math.max(0, 1200 - (Date.now() - state.lastAgentRefresh));
    state.agentRefreshTimer = setTimeout(() => {
      state.agentRefreshTimer = null;
      refreshAgentData().catch(() => {});
    }, wait);
  }

  async function refreshAgentData() {
    if (!state.sessionId) return;
    const id = state.sessionId;
    const result = await Promise.all([
      rpc('agent.goal.get', { sessionId: id }).catch(() => ({})),
      rpc('agent.todo.get', { sessionId: id }).catch(() => ({ items: [] })),
      rpc('agent.plan.get', { sessionId: id }).catch(() => ({})),
      rpc('agent.deliverables.list', { sessionId: id }).catch(() => ({ files: [] })),
      rpc('agent.jobs.list').catch(() => ({ jobs: [] })),
    ]);
    if (state.sessionId !== id) return;
    state.agent = {
      goal: result[0] || {},
      todos: (result[1] && result[1].items) || [],
      plan: result[2] || {},
      deliverables: (result[3] && result[3].files) || [],
      jobs: (result[4] && result[4].jobs) || [],
    };
    state.lastAgentRefresh = Date.now();
    const planMode = state.agent.plan.mode === 'plan';
    $('#planButton').setAttribute('aria-pressed', String(planMode));
    $('#planButton').textContent = planMode ? '计划中' : '计划';
    $('#prompt').placeholder = planMode ? '描述任务，让 Minis 先制定计划…' : '给 Minis 发消息…';
    if (state.detailsOpen && state.detailsView === 'task') renderDetails();
    if (!$('#workspaceLayer').classList.contains('hidden') && state.workspaceTab === 'deliverables') renderWorkspace();
  }

  function renderDetails() {
    const content = $('#detailsContent');
    const title = $('#detailsTitle');
    const view = state.detailsView;
    $$('.details-tab').forEach((button) => button.classList.toggle('active', button.dataset.view === view));
    if (view === 'task') {
      title.textContent = '任务';
      const agent = state.agent;
      const completed = agent.todos.filter((item) => item.status === 'completed').length;
      content.innerHTML = '<div class="mission-overview">' +
        '<section class="detail-section"><div class="detail-section-head"><strong>当前目标</strong><small>' + (agent.goal.active ? '进行中' : '未启用') + '</small></div><div class="detail-section-body"><textarea id="detailGoal" class="detail-textarea" placeholder="写下这次任务的最终目标…">' + esc(agent.goal.text || '') + '</textarea><div class="detail-actions"><button class="button button-secondary" data-detail-action="goal-toggle">' + (agent.goal.active ? '暂停' : '启用') + '</button><button class="button button-primary" data-detail-action="goal-save">保存</button></div></div></section>' +
        '<section class="detail-section"><div class="detail-section-head"><strong>计划</strong><small>' + (agent.plan.mode === 'plan' ? '计划模式' : '普通模式') + '</small></div><div class="detail-section-body"><textarea id="detailPlan" class="detail-textarea" placeholder="执行计划…">' + esc(agent.plan.plan || '') + '</textarea><div class="detail-actions"><button class="button button-secondary" data-detail-action="plan-toggle">' + (agent.plan.mode === 'plan' ? '退出计划模式' : '进入计划模式') + '</button><button class="button button-primary" data-detail-action="plan-save">保存</button></div></div></section>' +
        '<section class="detail-section"><div class="detail-section-head"><strong>待办</strong><small>' + completed + ' / ' + agent.todos.length + ' 已完成</small></div><div class="detail-section-body"><div id="detailTodos" class="todo-list">' + renderTodoLines(agent.todos) + '</div><div class="detail-actions"><button class="button button-secondary" data-detail-action="todo-add">添加</button><button class="button button-primary" data-detail-action="todo-save">同步待办</button></div></div></section>' +
        renderDeliverableDetail(agent.deliverables) +
      '</div>';
      return;
    }
    if (view === 'trajectory') {
      title.textContent = '执行轨迹';
      const tools = collectTools();
      content.innerHTML = tools.length ? '<div class="trajectory-list">' + tools.map((item) => {
        return '<button class="trajectory-row" data-trajectory-call="' + esc(item.id) + '"><span class="dot ' + (item.status === 'running' ? 'running' : item.status === 'failed' ? 'failed' : '') + '"></span><span><strong>' + esc(toolTitle(item.call)) + '</strong><small>' + esc(brief(item.call.input || item.call.toolArgs || '', 56)) + '</small></span><small>' + esc(toolStatusText(item.status, item.result)) + '</small></button>';
      }).join('') + '</div>' : '<div class="detail-empty">这个会话还没有可展示的工具执行记录。</div>';
      return;
    }
    title.textContent = '检查器';
    if (!state.selectedTool) {
      content.innerHTML = '<div class="detail-empty">选择会话里的任意工具行，即可查看输入、输出、状态和重试入口。</div>';
      return;
    }
    const selected = state.selectedTool;
    const output = selected.result && (selected.result.output || selected.result.error || selected.result.content);
    content.innerHTML = '<div class="mission-overview"><section class="detail-section"><div class="detail-section-head"><strong>' + esc(toolTitle(selected.call)) + '</strong><small>' + esc(toolStatusText(selected.status, selected.result)) + '</small></div><div class="detail-section-body"><p>' + esc(brief(selected.call.description || selected.call.toolName || '', 160)) + '</p><p class="details-kicker">INPUT</p><pre class="inspector-pre">' + esc(text(selected.call.input || selected.call.toolArgs || selected.call.arguments || '{}')) + '</pre>' + (output ? '<p class="details-kicker" style="margin-top:10px">OUTPUT</p><pre class="inspector-pre">' + esc(text(output)) + '</pre>' : '') + '<div class="detail-actions"><button class="button button-secondary" data-detail-action="rerun-tool">从此工具重试</button></div></div></section></div>';
  }

  function renderTodoLines(items) {
    if (!items.length) return '<div class="detail-empty">暂无待办。添加一项，让 Agent 与网页共用同一份任务清单。</div>';
    return items.map((item, index) => '<label class="todo-line ' + (item.status === 'completed' ? 'done' : item.status === 'in_progress' ? 'doing' : '') + '" data-todo-id="' + esc(item.id || '') + '"><button type="button" data-todo-cycle>✓</button><span contenteditable="true" data-todo-title>' + esc(item.title || '') + '</span><input type="hidden" data-todo-status value="' + esc(item.status || 'pending') + '"><button type="button" class="message-action" data-todo-remove="' + index + '">×</button></label>').join('');
  }

  function renderDeliverableDetail(files) {
    if (!files.length) return '';
    return '<section class="detail-section"><div class="detail-section-head"><strong>产出文件</strong><small>' + files.length + ' 个</small></div><div class="detail-section-body">' + files.slice(0, 12).map((file) => '<button class="message-action" data-deliverable="' + esc(file.path || '') + '">' + esc((file.path || '').split('/').pop() || file.path || '文件') + '</button>').join('') + '</div></section>';
  }

  function collectTools() {
    const resultMap = resultsByCall(state.messages);
    const all = [];
    state.messages.forEach((message) => liveCalls(message).forEach((call, index) => {
      const id = String(call.toolUseId || call.id || call.callId || (message.id + '-' + index));
      const result = resultMap.get(id);
      all.push({ id, call, result, status: toolStatus(call, result), messageId: message.id || '' });
    }));
    return all;
  }

  function openDetails(view) {
    state.detailsOpen = true;
    state.detailsView = view || state.detailsView || 'task';
    $('#app').dataset.detailsOpen = 'true';
    $('#detailsPanel').setAttribute('aria-hidden', 'false');
    $('#detailsButton').setAttribute('aria-pressed', 'true');
    renderDetails();
  }

  function closeDetails() {
    state.detailsOpen = false;
    $('#app').dataset.detailsOpen = 'false';
    $('#detailsPanel').setAttribute('aria-hidden', 'true');
    $('#detailsButton').setAttribute('aria-pressed', 'false');
  }

  async function saveDetailTodos() {
    const root = $('#detailTodos');
    const items = $$('.todo-line', root).map((row, index) => ({
      id: row.dataset.todoId || ('web-' + Date.now() + '-' + index),
      title: ($('[data-todo-title]', row).textContent || '').trim(),
      status: $('[data-todo-status]', row).value || 'pending',
    })).filter((item) => item.title);
    await rpc('agent.todo.replace', { sessionId: state.sessionId, items });
    await refreshAgentData();
  }

  async function togglePlanMode() {
    const plan = state.agent.plan || {};
    await rpc('agent.plan.set', {
      sessionId: state.sessionId,
      mode: plan.mode === 'plan' ? 'off' : 'plan',
      plan: plan.plan || '',
    });
    await refreshAgentData();
    toast(plan.mode === 'plan' ? '已退出计划模式' : '已进入计划模式');
  }

  /* ── usage ─────────────────────────────────────────────────────────── */

  function scheduleUsageRefresh() {
    if (!state.sessionId || state.usageRefreshTimer) return;
    const wait = Math.max(0, 9000 - (Date.now() - state.lastUsageRefresh));
    state.usageRefreshTimer = setTimeout(() => {
      state.usageRefreshTimer = null;
      refreshUsage().catch(() => {});
    }, wait);
  }

  async function refreshUsage() {
    if (!state.sessionId) return;
    const id = state.sessionId;
    try {
      const result = await api('/api/usage?sessionId=' + encodeURIComponent(id));
      if (state.sessionId !== id) return;
      const totals = result.totals || result;
      const total = Number(totals.inputTokens || 0) + Number(totals.outputTokens || 0) +
        Number(totals.reasoningTokens || 0) + Number(totals.cacheReadTokens || 0);
      $('#usagePill').textContent = total ? formatTokens(total) + ' tok' : '';
      state.lastUsageRefresh = Date.now();
    } catch (_) {
      $('#usagePill').textContent = '';
    }
  }

  /* ── workspace ─────────────────────────────────────────────────────── */

  function openWorkspace(tab) {
    if (!state.sessionId) {
      toast('先选择一个会话，才能打开它在手机上的工作区。', 'error');
      return;
    }
    if (tab) state.workspaceTab = tab;
    $('#workspaceLayer').classList.remove('hidden');
    $('#workspaceLayer').setAttribute('aria-hidden', 'false');
    $$('.workspace-nav').forEach((button) => button.classList.toggle('active', button.dataset.workspace === state.workspaceTab));
    renderWorkspace();
    if (state.workspaceTab === 'files') loadWorkspaceFiles(state.workspacePath).catch(() => {});
    if (state.workspaceTab === 'deliverables') refreshAgentData().catch(() => {});
  }

  function closeWorkspace() {
    $('#workspaceLayer').classList.add('hidden');
    $('#workspaceLayer').setAttribute('aria-hidden', 'true');
  }

  function renderWorkspace() {
    const root = $('#workspaceContent');
    if (state.workspaceTab === 'files') {
      root.innerHTML = '<div class="workspace-toolbar"><button class="icon-button" data-workspace-action="up" title="上级目录">←</button><input id="workspacePathInput" class="workspace-input" value="' + esc(state.workspacePath) + '"><button class="button button-secondary" data-workspace-action="refresh">刷新</button></div><div id="workspaceFiles" class="workspace-file-list"></div>' + renderWorkspaceEditor();
      renderWorkspaceFiles();
      return;
    }
    if (state.workspaceTab === 'terminal') {
      root.innerHTML = '<pre id="terminalOutput" class="terminal-output">' + esc(state.workspaceShell || '$ 选择一个命令开始\n') + '</pre><div class="terminal-command"><textarea id="terminalCommand" placeholder="例如：pwd && ls -la"></textarea><button id="terminalRun" class="button button-primary">运行</button></div>';
      return;
    }
    const files = state.agent.deliverables || [];
    root.innerHTML = files.length ? '<div class="workspace-file-list">' + files.map((file) => '<button class="workspace-file-row" data-workspace-deliverable="' + esc(file.path || '') + '"><span>□</span><strong>' + esc((file.path || '').split('/').pop() || file.path || '文件') + '</strong><small>' + esc(file.path || '') + '</small></button>').join('') + '</div>' : '<div class="workspace-empty">这个会话还没有登记产出文件。Agent 写入的文件会显示在这里。</div>';
  }

  function renderWorkspaceEditor() {
    const file = state.workspaceFile;
    if (!file) return '';
    return '<div class="workspace-editor"><div class="workspace-editor-head"><span>' + esc(file.path) + '</span><span><button class="message-action" data-workspace-action="close-file">关闭</button><button class="message-action" data-workspace-action="save-file">保存</button></span></div><textarea id="workspaceEditor" spellcheck="false">' + esc(file.content || '') + '</textarea></div>';
  }

  function renderWorkspaceFiles() {
    const root = $('#workspaceFiles');
    if (!root) return;
    const files = state.workspaceFiles || [];
    root.innerHTML = files.length ? files.map((file) => '<button class="workspace-file-row" data-workspace-file="' + esc(file.path) + '" data-directory="' + String(!!file.directory) + '"><span>' + (file.directory ? '▸' : '·') + '</span><strong>' + esc(file.name) + '</strong><small>' + (file.directory ? '' : formatBytes(file.size)) + '</small></button>').join('') : '<div class="workspace-empty">这个目录为空。</div>';
  }

  async function loadWorkspaceFiles(path) {
    if (!state.sessionId) return;
    state.workspacePath = path || state.workspacePath;
    try {
      const response = await api('/api/files?sessionId=' + encodeURIComponent(state.sessionId) + '&path=' + encodeURIComponent(state.workspacePath));
      state.workspacePath = response.path || state.workspacePath;
      state.workspaceFiles = response.items || [];
      if (state.workspaceTab === 'files') renderWorkspace();
    } catch (error) {
      if (state.workspacePath !== '/' && /not a directory|no such file|not found/i.test(error.message || '')) {
        return loadWorkspaceFiles('/');
      }
      toast('读取文件失败：' + error.message, 'error');
    }
  }

  async function openWorkspaceFile(path) {
    try {
      const response = await api('/api/file?sessionId=' + encodeURIComponent(state.sessionId) + '&path=' + encodeURIComponent(path));
      state.workspaceFile = {
        path,
        content: response.content || '',
        revision: response.sha256 || null,
      };
      state.workspaceTab = 'files';
      renderWorkspace();
    } catch (error) {
      toast('打开文件失败：' + error.message, 'error');
    }
  }

  async function saveWorkspaceFile() {
    const file = state.workspaceFile;
    if (!file) return;
    const editor = $('#workspaceEditor');
    try {
      const response = await api('/api/file', {
        method: 'PUT',
        body: JSON.stringify({
          sessionId: state.sessionId,
          path: file.path,
          content: editor.value,
          expectedSha256: file.revision,
        }),
      });
      state.workspaceFile.content = editor.value;
      state.workspaceFile.revision = response.sha256 || file.revision;
      toast('文件已保存');
      await loadWorkspaceFiles(state.workspacePath);
    } catch (error) {
      toast('保存失败：' + error.message, 'error');
    }
  }

  async function runWorkspaceShell() {
    const input = $('#terminalCommand');
    const command = input.value.trim();
    if (!command) return;
    const button = $('#terminalRun');
    button.disabled = true;
    state.workspaceShell += '$ ' + command + '\n';
    $('#terminalOutput').textContent = state.workspaceShell;
    try {
      const response = await api('/api/shell', {
        method: 'POST',
        body: JSON.stringify({ sessionId: state.sessionId, command }),
      });
      state.workspaceShell += (response.output || '') + '\n[exit ' + response.exitCode + ', ' + response.durationMs + ' ms]\n';
      if (response.fullOutputPath) state.workspaceShell += '[full: ' + response.fullOutputPath + ']\n';
      input.value = '';
    } catch (error) {
      state.workspaceShell += 'ERROR: ' + error.message + '\n';
    } finally {
      button.disabled = false;
      const output = $('#terminalOutput');
      output.textContent = state.workspaceShell;
      output.scrollTop = output.scrollHeight;
    }
  }

  function renderWorkspaceActivity() {
    const root = $('#workspaceActivityBody');
    if (!root) return;
    const tools = collectTools().slice(-24).reverse();
    root.innerHTML = tools.length ? tools.map((item) => '<button class="trajectory-row" data-trajectory-call="' + esc(item.id) + '"><span class="dot ' + (item.status === 'running' ? 'running' : item.status === 'failed' ? 'failed' : '') + '"></span><span><strong>' + esc(toolTitle(item.call)) + '</strong><small>' + esc(brief(item.call.input || item.call.toolArgs || '', 42)) + '</small></span><small>' + esc(toolStatusText(item.status, item.result)) + '</small></button>').join('') : '<div class="detail-empty">工具执行会显示在这里。</div>';
  }

  /* ── control center: every view calls the Android source of truth ──── */

  function openControl(view) {
    state.controlView = view || state.controlView || 'models';
    $('#controlLayer').classList.remove('hidden');
    $('#controlLayer').setAttribute('aria-hidden', 'false');
    $$('.control-nav-item').forEach((button) => button.classList.toggle('active', button.dataset.control === state.controlView));
    renderControl().catch((error) => {
      $('#controlContent').innerHTML = '<div class="control-page"><div class="control-status error">' + esc(error.message) + '</div></div>';
    });
  }

  function closeControl() {
    $('#controlLayer').classList.add('hidden');
    $('#controlLayer').setAttribute('aria-hidden', 'true');
  }

  function controlHead(title, copy) {
    return '<div class="control-page-head"><div><h2>' + esc(title) + '</h2><p>' + esc(copy) + '</p></div><button class="button button-secondary" data-control-action="refresh">刷新</button></div>';
  }

  async function renderControl() {
    const root = $('#controlContent');
    root.innerHTML = '<div class="control-page"><div class="control-status">正在读取手机端配置…</div></div>';
    if (state.controlView === 'models') return renderControlModels();
    if (state.controlView === 'skills') return renderControlSkills();
    if (state.controlView === 'memory') return renderControlMemory();
    if (state.controlView === 'mcp') return renderControlMcp();
    if (state.controlView === 'scheduled') return renderControlScheduled();
    return renderControlSettings();
  }

  async function renderControlModels() {
    const response = await api('/api/models');
    state.controlCache.models = response;
    const entries = response.entries || [];
    const groups = response.groups || [];
    $('#controlContent').innerHTML = '<div class="control-page">' + controlHead('模型与 Agent', '模型目录、路由组和当前会话绑定均来自手机端配置。') +
      '<div class="control-grid"><section class="control-card"><div class="control-card-head"><strong>当前会话模型</strong><button class="button button-secondary" data-control-action="open-model">在输入框中选择</button></div><p>' + esc((state.status && state.status.modelName) || '选择会话后显示') + ' · 思考 ' + esc(thinkingLabel(state.thinkingLevel)) + '</p></section>' +
      '<section class="control-card"><div class="control-card-head"><strong>可用模型</strong><small>' + entries.length + ' 个</small></div><div>' + entries.map((entry) => '<div class="control-list-row"><div><strong>' + esc(entry.modelName || entry.id) + '</strong><span>' + esc((entry.providerInstanceName || entry.providerType || '') + ' · ' + (entry.modelId || '')) + '</span></div><span class="control-pill' + (entry.supportsReasoning ? ' ok' : '') + '">' + (entry.supportsReasoning ? '思考' : '标准') + '</span></div>').join('') + '</div></section>' +
      '<section class="control-card"><div class="control-card-head"><strong>路由组</strong><small>' + groups.length + ' 个</small></div><div>' + groups.map((group) => '<div class="control-list-row"><div><strong>' + esc(group.name || group.id) + '</strong><span>' + esc(group.strategy || '') + ' · ' + ((group.memberEntryIds || []).length) + ' 个成员</span></div><span class="control-pill' + (group.isDefault ? ' ok' : '') + '">' + (group.isDefault ? '默认' : '组') + '</span></div>').join('') + '</div></section></div></div>';
  }

  async function renderControlSkills() {
    const response = await rpc('skills.list');
    const skills = response.skills || response.items || [];
    state.controlCache.skills = skills;
    $('#controlContent').innerHTML = '<div class="control-page">' + controlHead('技能', '安装、启停和删除直接作用于手机上的技能仓库。') + '<div class="control-grid">' +
      (skills.length ? skills.map((skill) => controlManagedCard('skill', skill.id, skill.name || skill.id, skill.description || '', skill.isEnabled !== false, [skill.version ? 'v' + skill.version : '', '使用 ' + (skill.useCount || 0) + ' 次'].filter(Boolean).join(' · '))).join('') : '<div class="control-card"><p>还没有安装技能。请在手机 App 中导入技能包。</p></div>') +
      '</div></div>';
  }

  async function renderControlMcp() {
    const response = await rpc('mcp.list');
    const servers = response.servers || response.items || [];
    state.controlCache.mcp = servers;
    $('#controlContent').innerHTML = '<div class="control-page">' + controlHead('MCP', '网页只显示安全元数据；密钥和环境变量值不会离开手机。') + '<div class="control-grid">' +
      (servers.length ? servers.map((server) => {
        const meta = [server.url ? 'HTTP/SSE' : 'STDIO', server.note || '', server.url || server.command || ''].filter(Boolean).join(' · ');
        return controlManagedCard('mcp', server.id, server.id, meta, server.enabled !== false, '');
      }).join('') : '<div class="control-card"><p>还没有配置 MCP 服务器。请在手机 App 中添加。</p></div>') +
      '</div></div>';
  }

  function controlManagedCard(kind, id, title, description, enabled, meta) {
    return '<section class="control-card" data-managed-kind="' + esc(kind) + '" data-managed-id="' + esc(id) + '" data-managed-enabled="' + String(enabled) + '"><div class="control-card-head"><strong>' + esc(title) + '</strong><span class="control-pill ' + (enabled ? 'ok' : 'off') + '">' + (enabled ? '已启用' : '已停用') + '</span></div><p>' + esc(description || meta || '无描述') + '</p><div class="control-card-actions"><button class="button button-secondary" data-managed-action="detail">详情</button><button class="button button-secondary" data-managed-action="toggle">' + (enabled ? '停用' : '启用') + '</button><button class="button button-secondary button-danger" data-managed-action="delete">删除</button></div><pre class="hidden" data-managed-detail></pre></section>';
  }

  async function renderControlMemory() {
    const results = await Promise.all([
      rpc('memory.files.list'),
      rpc('memory.globalToggle'),
      rpc('soul.get'),
    ]);
    const files = results[0].files || results[0].items || [];
    const global = !!results[1].enabled;
    const soul = results[2] || {};
    state.controlCache.memory = { files, global, soul, current: null };
    $('#controlContent').innerHTML = '<div class="control-page">' + controlHead('记忆与 SOUL', '这些设置与手机端 Agent 读取同一份记忆文件。') + '<div class="control-grid">' +
      '<section class="control-card"><div class="control-card-head"><strong>全局记忆</strong><span class="control-pill ' + (global ? 'ok' : 'off') + '">' + (global ? '已启用' : '已停用') + '</span></div><p>控制所有会话是否读写记忆文件。</p><div class="control-card-actions"><button class="button button-secondary" data-memory-action="toggle">' + (global ? '停用' : '启用') + '</button></div></section>' +
      '<section class="control-card"><div class="control-card-head"><strong>SOUL.md</strong></div><div class="control-form"><label>名称<input id="soulName" class="control-input" value="' + esc(soul.name || '') + '"></label><div class="form-row"><label>语言<input id="soulLang" class="control-input" value="' + esc(soul.lang || '') + '"></label><label>风格<input id="soulStyle" class="control-input" value="' + esc(soul.style || '') + '"></label></div><label>正文<textarea id="soulBody" class="control-textarea">' + esc(soul.body || '') + '</textarea></label></div><div class="control-card-actions"><button class="button button-primary" data-memory-action="save-soul">保存 SOUL.md</button></div></section>' +
      '<section class="control-card"><div class="control-card-head"><strong>记忆文件</strong><small>' + files.length + ' 个</small></div><div id="memoryFileRows">' + files.map((file) => '<button class="control-list-row" data-memory-file="' + esc(file.name) + '"><span class="control-pill' + (file.isGlobal ? ' ok' : '') + '">' + (file.isGlobal ? '全局' : '会话') + '</span><div><strong>' + esc(file.name) + '</strong><span>' + esc(file.preview || '') + '</span></div></button>').join('') + '</div></section>' +
      '<section class="control-card"><div class="control-card-head"><strong id="memoryEditTitle">选择一个记忆文件</strong></div><textarea id="memoryEditor" class="control-textarea" placeholder="选择上方文件后编辑…"></textarea><div class="control-card-actions"><button class="button button-secondary button-danger hidden" id="memoryDelete" data-memory-action="delete-file">删除</button><button class="button button-primary" data-memory-action="save-file">保存</button></div></section>' +
      '</div></div>';
  }

  async function renderControlScheduled() {
    const response = await rpc('scheduled.list');
    const tasks = response.tasks || [];
    state.controlCache.scheduled = tasks;
    $('#controlContent').innerHTML = '<div class="control-page">' + controlHead('定时任务', '启停、立即运行和删除会直接操作手机上的任务调度器。') + '<div class="control-grid">' +
      (tasks.length ? tasks.map((task) => '<section class="control-card" data-scheduled-id="' + esc(task.id) + '" data-scheduled-enabled="' + String(!!task.enabled) + '"><div class="control-card-head"><strong>' + esc(task.label || '未命名任务') + '</strong><span class="control-pill ' + (task.enabled ? 'ok' : 'off') + '">' + (task.enabled ? '已启用' : '已停用') + '</span></div><p>' + esc((task.prompt || '') + '\n' + (task.repeatMode || '') + ' ' + String(task.hour == null ? '' : task.hour).padStart(2, '0') + ':' + String(task.minute == null ? '' : task.minute).padStart(2, '0')) + '</p><div class="control-card-actions"><button class="button button-secondary" data-scheduled-action="run">立即运行</button><button class="button button-secondary" data-scheduled-action="toggle">' + (task.enabled ? '停用' : '启用') + '</button><button class="button button-secondary button-danger" data-scheduled-action="delete">删除</button></div></section>').join('') : '<div class="control-card"><p>还没有定时任务。创建任务请在手机 App 的定时任务页面完成。</p></div>') +
      '</div></div>';
  }

  async function renderControlSettings() {
    const settings = await api('/api/settings');
    state.controlCache.settings = settings;
    $('#controlContent').innerHTML = '<div class="control-page">' + controlHead('远程设置', '仅修改 Web Remote 的登录、监听与 Tunnel 设置；模型密钥不会下发到浏览器。') + '<div class="control-grid">' +
      '<section class="control-card"><div class="control-card-head"><strong>登录与监听</strong></div><div class="control-form"><label>用户名<input id="settingsUsername" class="control-input" value="' + esc(settings.username || '') + '"></label><div class="form-row"><label>当前密码<input id="settingsCurrentPassword" type="password" class="control-input" placeholder="修改登录信息时需要"></label><label>新密码<input id="settingsNewPassword" type="password" class="control-input" placeholder="留空则不修改"></label></div><label>端口<input id="settingsPort" type="number" class="control-input" value="' + esc(settings.port || 8765) + '"></label><label class="control-inline"><input id="settingsLan" type="checkbox"' + (settings.lanAccess ? ' checked' : '') + '>允许局域网访问</label></div></section>' +
      '<section class="control-card"><div class="control-card-head"><strong>Cloudflare Tunnel</strong><small>' + esc((settings.tunnel && (settings.tunnel.detail || settings.tunnel.phase)) || '未连接') + '</small></div><div class="control-form"><label class="control-inline"><input id="settingsTunnelEnabled" type="checkbox"' + (settings.cloudflareTunnelEnabled ? ' checked' : '') + '>启用 Tunnel</label><label>公开域名<input id="settingsHostname" class="control-input" value="' + esc(settings.cloudflareHostname || '') + '"></label><label>Tunnel Token<input id="settingsTunnelToken" type="password" class="control-input" placeholder="' + (settings.cloudflareTunnelTokenConfigured ? '已安全保存；留空保持不变' : '粘贴 Tunnel Token') + '"></label></div></section>' +
      '<section class="control-card"><div class="control-card-head"><strong>远程权限</strong></div><div class="control-form"><label>权限预设<select id="settingsPermission" class="control-input"><option value="workspace-write">Workspace Write（默认）</option><option value="danger-full-access">Danger Full Access</option></select></label></div><div class="control-card-actions"><button class="button button-secondary" data-settings-action="permission">保存权限</button><button class="button button-primary" data-settings-action="save">保存设置</button><button id="settingsRestart" class="button button-secondary hidden" data-settings-action="restart">重启 Remote</button></div><div id="settingsStatus" class="control-status"></div></section>' +
      '</div></div>';
    try {
      const permission = await rpc('settings.permissionPreset.get');
      $('#settingsPermission').value = permission.preset || 'workspace-write';
    } catch (_) {}
  }

  /* ── event handlers ────────────────────────────────────────────────── */

  async function handleDetailsClick(event) {
    const target = event.target.closest('button');
    if (!target) return;
    const action = target.dataset.detailAction;
    try {
      if (action === 'goal-save') {
        await rpc('agent.goal.set', { sessionId: state.sessionId, text: $('#detailGoal').value.trim() });
        await refreshAgentData();
      } else if (action === 'goal-toggle') {
        await rpc('agent.goal.setActive', { sessionId: state.sessionId, active: !state.agent.goal.active });
        await refreshAgentData();
      } else if (action === 'plan-save') {
        await rpc('agent.plan.set', { sessionId: state.sessionId, mode: state.agent.plan.mode || 'off', plan: $('#detailPlan').value });
        await refreshAgentData();
      } else if (action === 'plan-toggle') {
        await togglePlanMode();
      } else if (action === 'todo-add') {
        const root = $('#detailTodos');
        root.insertAdjacentHTML('beforeend', '<label class="todo-line" data-todo-id=""><button type="button" data-todo-cycle>✓</button><span contenteditable="true" data-todo-title>新待办</span><input type="hidden" data-todo-status value="pending"><button type="button" class="message-action" data-todo-remove>×</button></label>');
      } else if (action === 'todo-save') {
        await saveDetailTodos();
      } else if (action === 'rerun-tool' && state.selectedTool) {
        await rpc('chat.rerunFromToolBlock', {
          sessionId: state.sessionId,
          assistantMessageId: state.selectedTool.messageId,
          blockId: state.selectedTool.id,
          wait: false,
        });
        toast('已从此工具重新开始');
      }
      const deliverable = target.dataset.deliverable;
      if (deliverable) {
        openWorkspace('files');
        await openWorkspaceFile(deliverable);
      }
    } catch (error) {
      toast(error.message || '操作失败', 'error');
    }
  }

  async function handleControlClick(event) {
    const button = event.target.closest('button');
    if (!button) return;
    const root = $('#controlContent');
    try {
      if (button.dataset.controlAction === 'refresh') return renderControl();
      if (button.dataset.controlAction === 'open-model') {
        closeControl();
        await toggleModelMenu();
        return;
      }
      const managed = button.closest('[data-managed-kind]');
      if (managed) {
        const kind = managed.dataset.managedKind;
        const id = managed.dataset.managedId;
        const action = button.dataset.managedAction;
        if (action === 'toggle') {
          const enabled = managed.dataset.managedEnabled !== 'true';
          await rpc(kind === 'skill' ? 'skills.toggle' : 'mcp.toggle', kind === 'skill'
            ? { skillId: id, enabled } : { serverId: id, enabled });
          return renderControl();
        }
        if (action === 'delete') {
          if (!window.confirm('删除此' + (kind === 'skill' ? '技能' : 'MCP 服务器') + '？此操作不可撤销。')) return;
          await rpc(kind === 'skill' ? 'skills.delete' : 'mcp.delete', kind === 'skill' ? { skillId: id } : { serverId: id });
          return renderControl();
        }
        if (action === 'detail') {
          const detail = $('[data-managed-detail]', managed);
          if (!detail.classList.contains('hidden')) {
            detail.classList.add('hidden');
            return;
          }
          if (kind === 'skill') {
            const data = await rpc('skills.get', { skillId: id });
            detail.textContent = data.body || '(空)';
          } else {
            const server = (state.controlCache.mcp || []).find((item) => item.id === id) || {};
            detail.textContent = JSON.stringify({
              id: server.id, note: server.note, url: server.url, command: server.command,
              args: server.args, envKeys: Object.keys(server.env || {}), headerKeys: Object.keys(server.headers || {}),
            }, null, 2);
          }
          detail.classList.remove('hidden');
          return;
        }
      }
      if (button.dataset.memoryAction === 'toggle') {
        await rpc('memory.setGlobalEnabled', { enabled: !state.controlCache.memory.global });
        return renderControl();
      }
      if (button.dataset.memoryAction === 'save-soul') {
        await rpc('soul.save', {
          name: $('#soulName').value.trim(), lang: $('#soulLang').value.trim(),
          style: $('#soulStyle').value.trim(), body: $('#soulBody').value,
        });
        toast('SOUL.md 已保存');
        return;
      }
      if (button.dataset.memoryAction === 'save-file') {
        const current = state.controlCache.memory.current;
        if (!current) throw new Error('请先选择一个记忆文件');
        await rpc('memory.files.write', { name: current.name, content: $('#memoryEditor').value });
        toast('记忆文件已保存');
        return renderControl();
      }
      if (button.dataset.memoryAction === 'delete-file') {
        const current = state.controlCache.memory.current;
        if (!current || current.isGlobal || !window.confirm('删除这个记忆文件？')) return;
        await rpc('memory.files.delete', { name: current.name });
        return renderControl();
      }
      const scheduled = button.closest('[data-scheduled-id]');
      if (scheduled) {
        const id = scheduled.dataset.scheduledId;
        const action = button.dataset.scheduledAction;
        if (action === 'run') {
          if (!window.confirm('立即运行该任务？')) return;
          await rpc('scheduled.run', { taskId: id });
          toast('已触发后台任务');
        } else if (action === 'toggle') {
          await rpc('scheduled.toggle', { taskId: id, enabled: scheduled.dataset.scheduledEnabled !== 'true' });
          return renderControl();
        } else if (action === 'delete') {
          if (!window.confirm('删除这个定时任务？')) return;
          await rpc('scheduled.delete', { taskId: id });
          return renderControl();
        }
      }
      if (button.dataset.settingsAction === 'permission') {
        const preset = $('#settingsPermission').value;
        if (preset === 'danger-full-access' && !window.confirm('确认启用 Danger Full Access？')) return;
        await rpc('settings.permissionPreset.set', { preset });
        $('#settingsStatus').textContent = '权限预设已保存';
      }
      if (button.dataset.settingsAction === 'save') {
        const body = {
          username: $('#settingsUsername').value.trim(),
          port: Number($('#settingsPort').value),
          lanAccess: $('#settingsLan').checked,
          cloudflareTunnelEnabled: $('#settingsTunnelEnabled').checked,
          cloudflareHostname: $('#settingsHostname').value.trim(),
        };
        const current = $('#settingsCurrentPassword').value;
        const next = $('#settingsNewPassword').value;
        const token = $('#settingsTunnelToken').value.trim();
        if (current) body.currentPassword = current;
        if (next) body.newPassword = next;
        if (token) body.cloudflareTunnelToken = token;
        const result = await api('/api/settings', { method: 'PATCH', body: JSON.stringify(body) });
        $('#settingsStatus').textContent = result.restartRequired ? '已保存，需要重启 Web Remote。' : '已保存。';
        $('#settingsRestart').classList.toggle('hidden', !result.restartRequired);
        if (result.reauthRequired) setTimeout(showLogin, 500);
      }
      if (button.dataset.settingsAction === 'restart') {
        $('#settingsStatus').textContent = '正在重启 Remote…';
        try { await api('/api/settings/restart', { method: 'POST', body: '{}' }); } catch (_) {}
        setTimeout(() => window.location.reload(), 1400);
      }
    } catch (error) {
      const status = $('#settingsStatus');
      if (status) {
        status.textContent = error.message || '操作失败';
        status.classList.add('error');
      } else {
        toast(error.message || '操作失败', 'error');
      }
    }
  }

  function bindEvents() {
    $('#connectBtn').addEventListener('click', login);
    $('#passwordInput').addEventListener('keydown', (event) => { if (event.key === 'Enter') login(); });
    $('#logoutBtn').addEventListener('click', logout);
    $('#newChat').addEventListener('click', newSession);
    $('#sessionsRefresh').addEventListener('click', () => refreshSessions(false).catch((error) => toast(error.message, 'error')));
    $('#searchToggle').addEventListener('click', () => {
      $('#sidebarSearch').classList.remove('hidden');
      $('#searchInput').focus();
    });
    $('#searchClose').addEventListener('click', () => {
      $('#sidebarSearch').classList.add('hidden');
      $('#searchInput').value = '';
      renderSessions();
    });
    $('#searchInput').addEventListener('input', () => {
      renderSessions();
      const query = $('#searchInput').value.trim();
      const results = $('#searchResults');
      if (!query) {
        results.innerHTML = '';
        return;
      }
      clearTimeout(state.searchTimer);
      state.searchTimer = setTimeout(async () => {
        try {
          const response = await rpc('chat.search', { query, limit: 12 });
          if ($('#searchInput').value.trim() !== query) return;
          const rows = response.results || [];
          results.innerHTML = rows.length ? rows.map((item) => '<button class="search-result" data-search-session="' + esc(item.sessionId) + '"><strong>' + esc(item.title || '未命名会话') + '</strong><span>' + esc(item.snippet || '') + '</span></button>').join('') : '<div class="search-empty">没有全文匹配。</div>';
        } catch (_) {
          results.innerHTML = '';
        }
      }, 260);
    });
    $('#sessions').addEventListener('click', (event) => {
      const more = event.target.closest('[data-session-more]');
      if (more) {
        event.stopPropagation();
        openSessionMenu(more.dataset.sessionMore, more);
        return;
      }
      const row = event.target.closest('[data-session-id]');
      if (row) selectSession(row.dataset.sessionId).catch((error) => toast(error.message, 'error'));
    });
    $('#searchResults').addEventListener('click', (event) => {
      const row = event.target.closest('[data-search-session]');
      if (row) selectSession(row.dataset.searchSession).catch((error) => toast(error.message, 'error'));
    });
    $('#sessionMore').addEventListener('click', (event) => openSessionMenu(state.sessionId, event.currentTarget));
    $('#sessionMenu').addEventListener('click', (event) => {
      const action = event.target.dataset.sessionAction;
      if (!action) return;
      if (action === 'rename') renameSession().catch((error) => toast(error.message, 'error'));
      if (action === 'delete') deleteSession().catch((error) => toast(error.message, 'error'));
      closeSessionMenu();
    });

    $('#conversationScroll').addEventListener('scroll', updateBackToBottom, { passive: true });
    $('#backToBottom').addEventListener('click', () => {
      const reader = $('#conversationScroll');
      reader.scrollTop = reader.scrollHeight;
    });
    $('#chatFlow').addEventListener('click', async (event) => {
      const tool = event.target.closest('.tool-row');
      if (tool && tool._tool) {
        state.selectedTool = tool._tool;
        openDetails('inspect');
        reconcileMessages(state.messages);
        return;
      }
      const action = event.target.dataset.messageAction;
      const node = event.target.closest('[data-message-id]');
      if (!action || !node) return;
      const id = node.dataset.messageId;
      const message = state.messages.find((item) => String(item.id) === id);
      if (action === 'copy') {
        const copy = contentOf(message);
        try {
          await navigator.clipboard.writeText(copy);
          toast('已复制');
        } catch (_) {
          const area = document.createElement('textarea');
          area.value = copy;
          document.body.appendChild(area);
          area.select();
          document.execCommand('copy');
          area.remove();
          toast('已复制');
        }
      }
      if (action === 'retry') {
        try {
          await rpc('chat.retry', { sessionId: state.sessionId, messageId: id, wait: false });
          toast('已开始重试');
        } catch (error) { toast(error.message, 'error'); }
      }
      const feedback = event.target.dataset.feedback;
      if (feedback) {
        state.feedback.set(id, feedback);
        updateFeedback(node, id);
        rpc('chat.feedback.put', { messageId: id, kind: feedback }).catch(() => {});
      }
    });

    $('#prompt').addEventListener('input', autoGrow);
    $('#prompt').addEventListener('keydown', (event) => {
      if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault();
        sendPrompt();
      }
    });
    $('#sendButton').addEventListener('click', sendPrompt);
    $('#stopButton').addEventListener('click', cancelPrompt);
    $('#attachButton').addEventListener('click', () => $('#attachmentInput').click());
    $('#attachmentInput').addEventListener('change', (event) => {
      state.attachFiles = Array.from(event.target.files || []);
      event.target.value = '';
      renderAttachmentChips();
    });
    $('#attachmentChips').addEventListener('click', (event) => {
      const index = event.target.dataset.attachmentRemove;
      if (index == null) return;
      state.attachFiles.splice(Number(index), 1);
      renderAttachmentChips();
    });
    $('#modelButton').addEventListener('click', toggleModelMenu);
    $('#modelMenu').addEventListener('click', (event) => {
      const menu = event.target.closest('[data-model-menu]');
      const entry = event.target.closest('[data-model-entry]');
      const thinking = event.target.closest('[data-thinking]');
      if (menu) {
        state.modelMenuPane = menu.dataset.modelMenu;
        renderModelMenu();
      }
      if (entry) selectModel(entry.dataset.modelEntry);
      if (thinking) selectThinking(thinking.dataset.thinking);
    });
    $('#planButton').addEventListener('click', () => {
      if (!state.sessionId) return toast('先选择一个会话。', 'error');
      togglePlanMode().catch((error) => toast(error.message, 'error'));
    });
    $('#commandButton').addEventListener('click', (event) => {
      const menu = $('#commandMenu');
      const rect = event.currentTarget.getBoundingClientRect();
      menu.classList.toggle('hidden');
      menu.style.left = Math.max(8, rect.left) + 'px';
      menu.style.top = Math.max(8, rect.top - 212) + 'px';
    });
    $('#commandMenu').addEventListener('click', (event) => {
      const command = event.target.closest('[data-command]');
      if (!command) return;
      $('#commandMenu').classList.add('hidden');
      if (command.dataset.command === 'new') newSession();
      if (command.dataset.command === 'plan') togglePlanMode().catch((error) => toast(error.message, 'error'));
      if (command.dataset.command === 'workspace') openWorkspace('files');
      if (command.dataset.command === 'details') openDetails('task');
      if (command.dataset.command === 'compact') compactConversation();
    });
    $('#takeover').addEventListener('click', (event) => {
      const action = event.target.dataset.takeover;
      if (!action) return;
      if (action === 'skip') answerQuestion(true).catch((error) => toast(error.message, 'error'));
      if (action === 'answer') answerQuestion(false).catch((error) => toast(error.message, 'error'));
      if (action === 'allow') answerApproval(true).catch((error) => toast(error.message, 'error'));
      if (action === 'deny') answerApproval(false).catch((error) => toast(error.message, 'error'));
    });

    $('#detailsButton').addEventListener('click', () => state.detailsOpen ? closeDetails() : openDetails('task'));
    $('#detailsClose').addEventListener('click', closeDetails);
    $('#detailsTabs').addEventListener('click', (event) => {
      const tab = event.target.closest('[data-view]');
      if (!tab) return;
      state.detailsView = tab.dataset.view;
      renderDetails();
    });
    $('#detailsContent').addEventListener('click', (event) => {
      const todo = event.target.closest('[data-todo-cycle]');
      if (todo) {
        const row = todo.closest('.todo-line');
        const input = $('[data-todo-status]', row);
        const values = ['pending', 'in_progress', 'completed'];
        input.value = values[(values.indexOf(input.value) + 1) % values.length];
        row.className = 'todo-line ' + (input.value === 'completed' ? 'done' : input.value === 'in_progress' ? 'doing' : '');
        return;
      }
      const remove = event.target.closest('[data-todo-remove]');
      if (remove) {
        remove.closest('.todo-line').remove();
        return;
      }
      const trajectory = event.target.closest('[data-trajectory-call]');
      if (trajectory) {
        state.selectedTool = collectTools().find((item) => item.id === trajectory.dataset.trajectoryCall) || null;
        state.detailsView = 'inspect';
        renderDetails();
        return;
      }
      handleDetailsClick(event);
    });
    $('#compactBtn').addEventListener('click', compactConversation);

    $('#workspaceButton').addEventListener('click', () => openWorkspace('files'));
    $('#workspaceTopButton').addEventListener('click', () => openWorkspace('files'));
    $('#workspaceClose').addEventListener('click', closeWorkspace);
    $('#workspaceDetails').addEventListener('click', () => {
      closeWorkspace();
      openDetails('task');
    });
    $('#workspaceRefresh').addEventListener('click', () => {
      if (state.workspaceTab === 'files') loadWorkspaceFiles(state.workspacePath);
      refreshAgentData().catch(() => {});
      renderWorkspaceActivity();
    });
    $$('.workspace-nav').forEach((button) => button.addEventListener('click', () => {
      state.workspaceTab = button.dataset.workspace;
      $$('.workspace-nav').forEach((item) => item.classList.toggle('active', item === button));
      renderWorkspace();
      if (state.workspaceTab === 'files') loadWorkspaceFiles(state.workspacePath).catch(() => {});
      if (state.workspaceTab === 'deliverables') refreshAgentData().catch(() => {});
    }));
    $('#workspaceContent').addEventListener('click', (event) => {
      const action = event.target.dataset.workspaceAction;
      if (action === 'up') {
        const path = state.workspacePath.replace(/\/$/, '').split('/').slice(0, -1).join('/') || '/';
        loadWorkspaceFiles(path);
      }
      if (action === 'refresh') loadWorkspaceFiles($('#workspacePathInput').value);
      if (action === 'close-file') {
        state.workspaceFile = null;
        renderWorkspace();
      }
      if (action === 'save-file') saveWorkspaceFile();
      const file = event.target.closest('[data-workspace-file]');
      if (file) {
        if (file.dataset.directory === 'true') loadWorkspaceFiles(file.dataset.workspaceFile);
        else openWorkspaceFile(file.dataset.workspaceFile);
      }
      const deliverable = event.target.closest('[data-workspace-deliverable]');
      if (deliverable) openWorkspaceFile(deliverable.dataset.workspaceDeliverable);
      if (event.target.id === 'terminalRun') runWorkspaceShell();
    });
    $('#workspaceContent').addEventListener('keydown', (event) => {
      if (event.target.id === 'workspacePathInput' && event.key === 'Enter') loadWorkspaceFiles(event.target.value);
      if (event.target.id === 'terminalCommand' && event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
        event.preventDefault();
        runWorkspaceShell();
      }
    });
    $('#workspaceActivityBody').addEventListener('click', (event) => {
      const row = event.target.closest('[data-trajectory-call]');
      if (!row) return;
      state.selectedTool = collectTools().find((item) => item.id === row.dataset.trajectoryCall) || null;
      closeWorkspace();
      openDetails('inspect');
    });

    $('#controlButton').addEventListener('click', () => openControl('models'));
    $('#controlClose').addEventListener('click', closeControl);
    $('#controlNav').addEventListener('click', (event) => {
      const button = event.target.closest('[data-control]');
      if (!button) return;
      state.controlView = button.dataset.control;
      $$('.control-nav-item').forEach((item) => item.classList.toggle('active', item === button));
      renderControl().catch((error) => toast(error.message, 'error'));
    });
    $('#controlContent').addEventListener('click', (event) => {
      const memoryFile = event.target.closest('[data-memory-file]');
      if (memoryFile) {
        rpc('memory.files.read', { name: memoryFile.dataset.memoryFile }).then((result) => {
          state.controlCache.memory.current = result;
          $('#memoryEditTitle').textContent = (result.name || memoryFile.dataset.memoryFile) + (result.isGlobal ? '（全局）' : '');
          $('#memoryEditor').value = result.content || '';
          $('#memoryDelete').classList.toggle('hidden', !!result.isGlobal);
        }).catch((error) => toast(error.message, 'error'));
        return;
      }
      handleControlClick(event);
    });

    $('#mobileMenu').addEventListener('click', () => $('#app').classList.toggle('mobile-sidebar-open'));
    $('#sidebarCollapse').addEventListener('click', () => $('#app').classList.toggle('sidebar-collapsed'));
    $('#sidebarExpand').addEventListener('click', () => $('#app').classList.remove('sidebar-collapsed'));
    document.addEventListener('click', (event) => {
      if (!event.target.closest('#sessionMenu') && !event.target.closest('[data-session-more]') && event.target !== $('#sessionMore')) closeSessionMenu();
      if (!event.target.closest('#modelSelect')) closeModelMenu();
      if (!event.target.closest('#commandMenu') && !event.target.closest('#commandButton')) $('#commandMenu').classList.add('hidden');
    });
    document.addEventListener('keydown', (event) => {
      if (event.key === 'Escape') {
        closeModelMenu();
        closeSessionMenu();
        $('#commandMenu').classList.add('hidden');
      }
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        newSession();
      }
      if ((event.metaKey || event.ctrlKey) && event.key === '/') {
        event.preventDefault();
        $('#sidebarSearch').classList.remove('hidden');
        $('#searchInput').focus();
      }
    });
    document.addEventListener('visibilitychange', () => {
      if (!document.hidden && state.sessionId && !state.live) openLiveStream(state.sessionId);
    });
  }

  bindEvents();
  boot();
})();
