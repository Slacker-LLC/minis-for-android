/* MCP 管理页：列表 + 启用/停用 + 详情 + 删除。 */

function mcpMsg(text, isError) {
  const el = $('#mcpMsg');
  if (!el) return;
  el.textContent = text || '';
  el.classList.toggle('error-text', !!isError);
}

async function loadMcp() {
  const root = $('#mcpBody');
  if (!root) return;
  mcpMsg('加载中…');
  try {
    const d = await rpc('mcp.list');
    const servers = d.servers || d.items || [];
    root.innerHTML = servers.length
      ? servers.map(mcpCard).join('')
      : '<div class="models-empty">还没有配置 MCP 服务器。请在手机 App 的设置里添加。</div>';
    mcpMsg('');
  } catch (e) {
    root.innerHTML = '<div class="error">' + esc(e.message) + '</div>';
    mcpMsg('加载失败', true);
  }
}

function mcpCard(s) {
  const enabled = s.enabled !== false;
  const envCount = Object.keys(s.env || {}).length;
  const headerCount = Object.keys(s.headers || {}).length;
  const transport = s.url || ((s.command || '') + ' ' + (s.args || []).join(' '));
  const sub = [s.note, transport].filter(Boolean).join(' · ');
  const tags = [];
  tags.push('<span class="tag">' + (s.url ? 'HTTP/SSE' : 'STDIO') + '</span>');
  tags.push('<span class="tag">env ' + envCount + '</span>');
  if (headerCount) tags.push('<span class="tag">headers ' + headerCount + '</span>');
  if (s.startupTimeoutSeconds) tags.push('<span class="tag">' + esc(String(s.startupTimeoutSeconds)) + 's</span>');
  return '<div class="card-list" data-id="' + esc(s.id) + '" data-enabled="' + (enabled ? '1' : '0') + '">' +
    '<div class="card-head">' +
      '<span class="dot-' + (enabled ? 'on' : 'off') + '"></span>' +
      '<strong>' + esc(s.id) + '</strong>' +
      '<span class="card-sub">' + tags.join('') + '</span>' +
    '</div>' +
    (sub ? '<div class="list-body"><div class="list-row"><div class="list-main"><div class="list-desc">' + esc(sub) + '</div></div></div></div>' : '') +
    '<div class="preview-box hidden" data-role="detail"></div>' +
    '<div class="btn-row">' +
      '<button class="secondary" data-act="toggle">' + (enabled ? '停用' : '启用') + '</button>' +
      '<button class="secondary" data-act="detail">详情</button>' +
      '<button class="secondary danger" data-act="delete">删除</button>' +
    '</div></div>';
}

$('#mcpBody').onclick = async e => {
  const btn = e.target.closest('button[data-act]');
  if (!btn) return;
  const card = btn.closest('.card-list');
  if (!card) return;
  const id = card.dataset.id;
  const act = btn.dataset.act;
  btn.disabled = true;
  const label = btn.textContent;
  btn.textContent = '…';
  try {
    if (act === 'toggle') {
      const enable = card.dataset.enabled !== '1';
      await rpc('mcp.toggle', { serverId: id, enabled: enable });
      mcpMsg(enable ? '已启用' : '已停用');
      await loadMcp();
    } else if (act === 'detail') {
      const box = card.querySelector('[data-role="detail"]');
      if (!box.classList.contains('hidden')) {
        box.classList.add('hidden');
        return;
      }
      if (!box.dataset.loaded) {
        const all = (await rpc('mcp.list')).servers || [];
        const s = all.find(x => x.id === id) || {};
        // 环境变量与请求头里可能藏着密钥，只展示键名，不把值下发到网页。
        const detail = {
          id: s.id,
          note: s.note,
          url: s.url,
          command: s.command,
          args: s.args,
          envKeys: Object.keys(s.env || {}),
          headerKeys: Object.keys(s.headers || {}),
          startupTimeoutSeconds: s.startupTimeoutSeconds,
          createdAt: s.createdAt,
        };
        box.textContent = JSON.stringify(detail, null, 2);
        box.dataset.loaded = '1';
      }
      box.classList.remove('hidden');
    } else if (act === 'delete') {
      const name = card.querySelector('strong')?.textContent || id;
      if (confirm('删除 MCP 服务器「' + name + '」？此操作不可撤销。')) {
        await rpc('mcp.delete', { serverId: id });
        mcpMsg('已删除');
        await loadMcp();
      }
    }
  } catch (err) {
    mcpMsg(err.message, true);
  } finally {
    btn.disabled = false;
    btn.textContent = label;
  }
};

TAB_LOADERS.mcp = loadMcp;
$('#mcpRefresh').onclick = loadMcp;
