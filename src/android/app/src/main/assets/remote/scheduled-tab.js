// 定时任务页：列表 / 启停 / 立即运行 / 删除。
// 创建任务仍在本机 App 完成；这里复用 DebugRPC 的 scheduled.* 方法。

const REPEAT_LABELS = { ONCE: '一次', DAILY: '每天', WEEKDAYS: '工作日', CUSTOM: '自定义' };
const WEEKDAY_SHORT = ['日', '一', '二', '三', '四', '五', '六'];

function scheduledMsg(text, isError) {
  const el = $('#scheduledMsg');
  if (!el) return;
  el.textContent = text || '';
  el.classList.toggle('error-text', !!isError);
}

function pad2(n) { return String(n).padStart(2, '0'); }

function fmtTime(hour, minute) { return pad2(hour) + ':' + pad2(minute); }

function fmtDate(ms) {
  if (!ms) return '';
  try { return new Date(ms).toLocaleString('zh-CN', { hour12: false }); } catch { return String(ms); }
}

function customDayList(t) {
  if (Array.isArray(t.customDays)) return t.customDays;
  if (typeof t.customDays === 'string' && t.customDays) {
    return t.customDays.split(',').map(Number).filter(n => n >= 1 && n <= 7);
  }
  return [];
}

function repeatLabel(t) {
  const base = REPEAT_LABELS[t.repeatMode] || t.repeatMode || '';
  if (t.repeatMode === 'CUSTOM') {
    const days = customDayList(t).sort((a, b) => a - b)
      .map(d => WEEKDAY_SHORT[d - 1] || d).join('/');
    return days ? base + ' ' + days : base;
  }
  return base;
}

function targetLabel(mode) {
  if (!mode) return '';
  if (mode === 'NEW_SESSION') return '新会话';
  if (mode.startsWith('APPEND_TO:')) return '续写会话';
  if (mode.startsWith('RERUN:')) return '重跑';
  return mode;
}

function taskCard(t) {
  const enabled = !!t.enabled;
  const tags = [
    '<span class="tag">' + esc(fmtTime(t.hour, t.minute)) + '</span>',
    '<span class="tag">' + esc(repeatLabel(t)) + '</span>',
    '<span class="tag">' + esc(targetLabel(t.targetMode)) + '</span>',
  ];
  if (t.modelId) tags.push('<span class="tag primary">模型 ' + esc(t.modelId) + '</span>');
  if (Array.isArray(t.runHistory) && t.runHistory.length) {
    tags.push('<span class="tag">已执行 ' + t.runHistory.length + ' 次</span>');
  }

  let html = '<div class="card-list" data-task-id="' + esc(t.id) + '" data-enabled="' + (enabled ? '1' : '0') + '">' +
    '<div class="card-head">' +
      '<span class="' + (enabled ? 'dot-on' : 'dot-off') + '"></span>' +
      '<strong>' + esc(t.label || '未命名任务') + '</strong>' +
      '<span class="card-sub">' + tags.join('') + '</span>' +
    '</div>';

  const descBits = [];
  if (t.prompt) descBits.push(esc(t.prompt));
  if (t.lastResultPreview) descBits.push('最近结果：' + esc(t.lastResultPreview));
  if (t.lastFiredAt) descBits.push('上次触发 ' + fmtDate(t.lastFiredAt));
  html += '<div class="list-body"><div class="list-row"><div class="list-main"><div class="list-desc">' +
    (descBits.length ? descBits.join('<br>') : '暂无执行记录') +
    '</div></div></div></div>';

  html += '<div class="btn-row">' +
    '<button class="primary" data-act="run">立即运行</button>' +
    '<button class="secondary" data-act="toggle">' + (enabled ? '停用' : '启用') + '</button>' +
    '<button class="secondary danger" data-act="delete">删除</button>' +
    '</div></div>';
  return html;
}

async function loadScheduled() {
  const root = $('#scheduledBody');
  if (!root) return;
  scheduledMsg('加载中…');
  try {
    const d = await rpc('scheduled.list');
    const tasks = d?.tasks || [];
    if (!tasks.length) {
      root.innerHTML = '<div class="empty-state"><h2>还没有定时任务</h2><p>创建任务请在本机 App 的「定时任务」页面完成。</p></div>';
    } else {
      root.innerHTML = tasks.map(taskCard).join('');
      bindScheduledActions();
    }
    scheduledMsg('');
  } catch (e) {
    root.innerHTML = '<div class="error">' + esc(e.message) + '</div>';
    scheduledMsg('加载失败', true);
  }
}

function bindScheduledActions() {
  $$('#scheduledBody .card-list').forEach(card => {
    const taskId = card.dataset.taskId;
    [...card.querySelectorAll('button')].forEach(btn => {
      btn.onclick = async () => {
        const act = btn.dataset.act;
        if (act === 'run' && !confirm('立即运行该任务？任务会在后台执行，最长约 10 分钟。')) return;
        if (act === 'delete' && !confirm('删除该定时任务？此操作不可恢复。')) return;
        btn.disabled = true;
        const label = btn.textContent;
        btn.textContent = '…';
        try {
          if (act === 'run') {
            await rpc('scheduled.run', { taskId });
            scheduledMsg('已触发，任务在后台执行');
          } else if (act === 'toggle') {
            const enable = card.dataset.enabled !== '1';
            await rpc('scheduled.toggle', { taskId, enabled: enable });
            scheduledMsg('已' + (enable ? '启用' : '停用'));
            await loadScheduled();
            return;
          } else if (act === 'delete') {
            await rpc('scheduled.delete', { taskId });
            scheduledMsg('已删除');
            await loadScheduled();
            return;
          }
        } catch (e) {
          scheduledMsg(e.message, true);
        } finally {
          btn.disabled = false;
          btn.textContent = label;
        }
      };
    });
  });
}

TAB_LOADERS.scheduled = loadScheduled;
$('#scheduledRefresh') && ($('#scheduledRefresh').onclick = loadScheduled);
