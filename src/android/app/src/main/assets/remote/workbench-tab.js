/* Agent 工作台：把手机端的 Agent 状态、计划、待办、后台任务与沙箱状态集中到 Web Remote。 */

const workbenchState = { todos: [] };

function wbMessage(text, isError) {
  const root = $('#workbenchBody');
  if (!root) return;
  let el = root.querySelector('.workbench-message');
  if (!el) {
    el = document.createElement('div');
    el.className = 'workbench-message';
    root.prepend(el);
  }
  el.textContent = text || '';
  el.classList.toggle('error-text', !!isError);
}

function wbStatus(value) {
  const valueText = String(value || 'pending');
  return '<select data-wb-status>' +
    ['pending', 'in_progress', 'completed'].map(s =>
      '<option value="' + s + '"' + (s === valueText ? ' selected' : '') + '>' +
      ({ pending: '待处理', in_progress: '进行中', completed: '已完成' }[s]) +
      '</option>').join('') +
    '</select>';
}

function wbJobCard(job) {
  const status = String(job.status || 'UNKNOWN');
  const running = status === 'RUNNING' || status === 'PENDING';
  const output = String(job.output || job.detail || '').trim();
  return '<div class="job-row" data-job-id="' + esc(job.id) + '">' +
    '<div class="job-status ' + esc(status.toLowerCase()) + '"></div>' +
    '<div class="job-main"><strong>' + esc(job.label || job.kind || '后台任务') + '</strong>' +
    '<span>' + esc(status) + (job.detail ? ' · ' + esc(job.detail) : '') + '</span></div>' +
    (output ? '<button class="mini" data-wb-job="output">输出</button>' : '') +
    (running ? '<button class="mini danger" data-wb-job="cancel">停止</button>' : '') +
    (output ? '<pre class="job-output hidden">' + esc(output) + '</pre>' : '') +
    '</div>';
}

function renderWorkbench(data) {
  const root = $('#workbenchBody');
  if (!root) return;
  const app = data.app || {};
  const sandbox = data.sandbox || {};
  const goal = data.goal || { text: '', active: false };
  const plan = data.plan || { mode: 'off', plan: '' };
  const todos = data.todos || [];
  const jobs = data.jobs || [];
  workbenchState.todos = todos;
  const session = state.sessions.find(s => s.id === state.sessionId);
  root.innerHTML =
    '<div class="workbench-hero"><span class="workbench-kicker">OPENMINIS / ANDROID</span>' +
    '<h2>' + (session ? esc(session.title || '当前会话') : '选择一个会话开始') + '</h2>' +
    '<p>' + (state.running ? 'Agent 正在运行，网页会持续同步进度。' : '手机与网页共用同一会话、任务与工作区。') + '</p></div>' +
    '<div class="workbench-grid workbench-stats">' +
      '<div class="workbench-stat"><span>设备</span><strong>' + esc(app.device || app.platform || 'Android') + '</strong></div>' +
      '<div class="workbench-stat"><span>Sandbox</span><strong>' + esc(sandbox.preset || sandbox.mode || '已连接') + '</strong></div>' +
      '<div class="workbench-stat"><span>后台任务</span><strong>' + jobs.length + '</strong></div>' +
    '</div>' +
    (state.sessionId ? '<div class="workbench-card"><div class="workbench-card-head"><div><strong>目标</strong><span>让 Agent 与网页始终对齐当前任务</span></div><label class="switch-compact"><input id="wbGoalActive" type="checkbox" ' + (goal.active ? 'checked' : '') + '><span>启用</span></label></div>' +
      '<textarea id="wbGoalText" class="workbench-textarea" placeholder="写下本次任务的最终目标…">' + esc(goal.text || '') + '</textarea>' +
      '<div class="btn-row"><button id="wbGoalSave" class="primary">保存目标</button></div></div>' +
      '<div class="workbench-card"><div class="workbench-card-head"><div><strong>计划</strong><span>计划模式会让 Agent 先拟定步骤，再执行</span></div><button id="wbPlanToggle" class="mini">' + (plan.mode === 'plan' ? '退出计划模式' : '进入计划模式') + '</button></div>' +
      '<textarea id="wbPlanText" class="workbench-textarea" placeholder="可在这里编辑给 Agent 的执行计划…">' + esc(plan.plan || '') + '</textarea>' +
      '<div class="btn-row"><button id="wbPlanSave" class="secondary">保存计划</button></div></div>' +
      '<div class="workbench-card"><div class="workbench-card-head"><div><strong>待办</strong><span>和手机端 Agent 共享的任务清单</span></div><button id="wbTodoAdd" class="mini">添加</button></div>' +
      '<div id="wbTodos" class="workbench-todos">' + todos.map((todo, i) =>
        '<div class="workbench-todo" data-wb-index="' + i + '" data-wb-id="' + esc(todo.id || '') + '">' +
          wbStatus(todo.status) + '<input data-wb-title value="' + esc(todo.title || '') + '" placeholder="待办事项">' +
          '<button class="mini danger" data-wb-remove title="删除">×</button></div>').join('') +
        (todos.length ? '' : '<p class="workbench-empty">还没有待办。把复杂任务拆成清晰的下一步。</p>') +
      '</div><div class="btn-row"><button id="wbTodoSave" class="primary">保存待办</button></div></div>' :
      '<div class="workbench-card workbench-empty"><strong>还未选择会话</strong><span>新建或选择一个会话后，就可以从网页管理目标、计划和待办。</span></div>') +
    '<div class="workbench-card"><div class="workbench-card-head"><div><strong>后台任务</strong><span>DeepSeek Harness 风格的长期任务与输出</span></div></div>' +
      '<div class="jobs-list">' + (jobs.length ? jobs.map(wbJobCard).join('') : '<p class="workbench-empty">当前没有后台任务。</p>') + '</div></div>';
  wbMessage('');
}

async function loadWorkbench() {
  const root = $('#workbenchBody');
  if (!root) return;
  root.innerHTML = '<div class="skeleton"></div><div class="skeleton"></div><div class="skeleton"></div>';
  try {
    const shared = await Promise.all([
      rpc('agent.jobs.list'),
      rpc('settings.sandbox.get').catch(() => ({})),
      rpc('debug.appInfo').catch(() => ({})),
    ]);
    const result = { jobs: shared[0]?.jobs || [], sandbox: shared[1], app: shared[2] };
    if (state.sessionId) {
      const sessionState = await Promise.all([
        rpc('agent.goal.get', { sessionId: state.sessionId }),
        rpc('agent.todo.get', { sessionId: state.sessionId }),
        rpc('agent.plan.get', { sessionId: state.sessionId }),
      ]);
      result.goal = sessionState[0];
      result.todos = sessionState[1]?.items || [];
      result.plan = sessionState[2];
    }
    renderWorkbench(result);
  } catch (e) {
    root.innerHTML = '<div class="error">' + esc(e.message) + '</div>';
  }
}

function wbTodoItems() {
  return $$('#wbTodos .workbench-todo').map((row, index) => ({
    id: row.dataset.wbId || ('web-' + Date.now() + '-' + index),
    title: row.querySelector('[data-wb-title]').value.trim(),
    status: row.querySelector('[data-wb-status]').value,
  })).filter(item => item.title);
}

$('#workbenchBody').onclick = async e => {
  const target = e.target;
  try {
    if (target.id === 'wbGoalSave') {
      await rpc('agent.goal.set', { sessionId: state.sessionId, text: $('#wbGoalText').value.trim() });
      await rpc('agent.goal.setActive', { sessionId: state.sessionId, active: $('#wbGoalActive').checked });
      wbMessage('目标已同步到手机 Agent。');
      await loadAgentBars();
    } else if (target.id === 'wbPlanSave') {
      const existing = await rpc('agent.plan.get', { sessionId: state.sessionId });
      await rpc('agent.plan.set', { sessionId: state.sessionId, mode: existing.mode || 'off', plan: $('#wbPlanText').value });
      wbMessage('计划已保存。');
      await loadAgentBars();
    } else if (target.id === 'wbPlanToggle') {
      const plan = await rpc('agent.plan.get', { sessionId: state.sessionId });
      await rpc('agent.plan.set', { sessionId: state.sessionId, mode: plan.mode === 'plan' ? 'off' : 'plan', plan: $('#wbPlanText').value });
      await loadWorkbench();
      await loadAgentBars();
    } else if (target.id === 'wbTodoAdd') {
      const list = $('#wbTodos');
      const i = $$('#wbTodos .workbench-todo').length;
      list.insertAdjacentHTML('beforeend', '<div class="workbench-todo" data-wb-index="' + i + '" data-wb-id=""><select data-wb-status><option value="pending">待处理</option><option value="in_progress">进行中</option><option value="completed">已完成</option></select><input data-wb-title placeholder="待办事项"><button class="mini danger" data-wb-remove title="删除">×</button></div>');
      list.querySelector('.workbench-todo:last-child [data-wb-title]').focus();
    } else if (target.id === 'wbTodoSave') {
      await rpc('agent.todo.replace', { sessionId: state.sessionId, items: wbTodoItems() });
      wbMessage('待办已同步到手机 Agent。');
      await loadAgentBars();
    } else if (target.matches('[data-wb-remove]')) {
      target.closest('.workbench-todo').remove();
    } else if (target.dataset.wbJob === 'output') {
      target.closest('.job-row').querySelector('.job-output').classList.toggle('hidden');
    } else if (target.dataset.wbJob === 'cancel') {
      const id = target.closest('.job-row').dataset.jobId;
      if (!confirm('停止这个后台任务？')) return;
      await rpc('agent.jobs.cancel', { id, reason: 'Cancelled from Web Remote' });
      await loadWorkbench();
    }
  } catch (err) {
    wbMessage(err.message || '操作失败', true);
  }
};

TAB_LOADERS.workbench = loadWorkbench;
$('#workbenchRefresh').onclick = loadWorkbench;
