/* 记忆管理页：全局开关 + SOUL.md 编辑 + 记忆文件浏览/编辑/删除。 */

const memState = { currentName: null, files: [], globalEnabled: false };

function memoryMsg(text, isError) {
  const el = $('#memoryMsg');
  if (!el) return;
  el.textContent = text || '';
  el.classList.toggle('error-text', !!isError);
}

async function loadMemory() {
  const root = $('#memoryBody');
  if (!root) return;
  memoryMsg('加载中…');
  try {
    const [f, g, s] = await Promise.all([
      rpc('memory.files.list'),
      rpc('memory.globalToggle'),
      rpc('soul.get'),
    ]);
    memState.files = f.files || f.items || [];
    memState.globalEnabled = !!g.enabled;
    root.innerHTML = renderMemory(s);
    renderMemFiles();
    bindMemoryEvents();
    memoryMsg('');
  } catch (e) {
    root.innerHTML = '<div class="error">' + esc(e.message) + '</div>';
    memoryMsg('加载失败', true);
  }
}

function renderMemory(soul) {
  const on = memState.globalEnabled;
  return '<div class="card-list">' +
    '<div class="card-head">' +
      '<span class="dot-' + (on ? 'on' : 'off') + '" id="memGlobalDot"></span>' +
      '<strong>全局记忆</strong>' +
      '<span class="card-sub"><button class="mini" id="memGlobalBtn">' + (on ? '停用' : '启用') + '</button></span>' +
    '</div>' +
    '<div class="list-row"><div class="list-main"><div class="list-desc">开关控制所有会话是否读写记忆文件，具体文件在下方管理。</div></div></div>' +
    '</div>' +

    '<div class="card-list">' +
    '<div class="card-head"><strong>SOUL.md（人设）</strong></div>' +
    '<div class="form-grid">' +
      '<label class="form-field">名称<input id="soulName" value="' + esc(soul.name || '') + '"></label>' +
      '<label class="form-field">语言<input id="soulLang" value="' + esc(soul.lang || '') + '"></label>' +
      '<label class="form-field full">风格<input id="soulStyle" value="' + esc(soul.style || '') + '"></label>' +
    '</div>' +
    '<textarea id="soulBody" class="mem-edit" placeholder="SOUL.md 正文…">' + esc(soul.body || '') + '</textarea>' +
    '<div class="btn-row"><button class="primary" id="soulSaveBtn">保存 SOUL.md</button></div>' +
    '</div>' +

    '<div class="card-list">' +
    '<div class="card-head"><strong>记忆文件</strong><span class="card-sub">点击文件加载到编辑器</span></div>' +
    '<div class="list-body" id="memFileList"></div>' +
    '</div>' +

    '<div class="card-list">' +
    '<div class="card-head"><strong>编辑器</strong><span class="card-sub" id="memEditName">未选择文件</span></div>' +
    '<textarea id="memEdit" class="mem-edit" placeholder="点击上方文件后在此编辑…"></textarea>' +
    '<div class="btn-row">' +
      '<button class="primary" id="memSaveBtn">保存</button>' +
      '<button class="secondary danger hidden" id="memDeleteBtn">删除文件</button>' +
    '</div></div>';
}

function renderMemFiles() {
  const root = $('#memFileList');
  if (!root) return;
  const files = memState.files;
  root.innerHTML = files.length
    ? files.map(f =>
        '<div class="list-row" data-name="' + esc(f.name) + '">' +
          '<span class="tag ' + (f.isGlobal ? 'primary' : '') + '">' + (f.isGlobal ? '全局' : '会话') + '</span>' +
          '<span class="list-main">' +
            '<span class="list-title">' + esc(f.name) + '</span>' +
            (f.preview ? '<span class="list-desc">' + esc(f.preview) + '</span>' : '') +
          '</span>' +
          '<span class="tag">' + esc(String(f.fileSize)) + '</span>' +
          (f.modifiedDate ? '<span class="tag">' + esc(String(f.modifiedDate)) + '</span>' : '') +
        '</div>').join('')
    : '<div class="list-row"><div class="list-main"><div class="list-desc">暂无记忆文件。</div></div></div>';
}

async function openMemFile(name) {
  try {
    const d = await rpc('memory.files.read', { name });
    memState.currentName = d.name || name;
    $('#memEditName').textContent = memState.currentName + (d.isGlobal ? '（全局）' : '');
    $('#memEdit').value = d.content || '';
    $('#memDeleteBtn').classList.toggle('hidden', !!d.isGlobal);
    memoryMsg('');
  } catch (e) {
    memoryMsg('读取失败：' + e.message, true);
  }
}

function bindMemoryEvents() {
  $('#memGlobalBtn').onclick = async () => {
    const enable = !memState.globalEnabled;
    try {
      await rpc('memory.setGlobalEnabled', { enabled: enable });
      memoryMsg(enable ? '全局记忆已启用' : '全局记忆已停用');
      await loadMemory();
    } catch (e) { memoryMsg(e.message, true); }
  };

  $('#soulSaveBtn').onclick = async () => {
    try {
      await rpc('soul.save', {
        name: $('#soulName').value.trim(),
        lang: $('#soulLang').value.trim(),
        style: $('#soulStyle').value.trim(),
        body: $('#soulBody').value,
      });
      memoryMsg('SOUL.md 已保存');
    } catch (e) { memoryMsg('保存失败：' + e.message, true); }
  };

  $('#memFileList').onclick = e => {
    const row = e.target.closest('[data-name]');
    if (row) openMemFile(row.dataset.name);
  };

  $('#memSaveBtn').onclick = async () => {
    const name = memState.currentName;
    if (!name) { memoryMsg('请先选择一个记忆文件', true); return; }
    try {
      await rpc('memory.files.write', { name, content: $('#memEdit').value });
      memoryMsg('已保存 ' + name);
      await loadMemory();
    } catch (e) { memoryMsg('保存失败：' + e.message, true); }
  };

  $('#memDeleteBtn').onclick = async () => {
    const name = memState.currentName;
    if (!name) { memoryMsg('请先选择一个记忆文件', true); return; }
    if (!confirm('删除记忆文件「' + name + '」？此操作不可撤销。')) return;
    try {
      await rpc('memory.files.delete', { name });
      memState.currentName = null;
      memoryMsg('已删除 ' + name);
      await loadMemory();
    } catch (e) { memoryMsg('删除失败：' + e.message, true); }
  };
}

TAB_LOADERS.memory = loadMemory;
$('#memoryRefresh').onclick = loadMemory;
