/* 技能管理页：列表 + 启用/停用 + 详情 + 删除。 */

function skillsMsg(text, isError) {
  const el = $('#skillsMsg');
  if (!el) return;
  el.textContent = text || '';
  el.classList.toggle('error-text', !!isError);
}

async function loadSkills() {
  const root = $('#skillsBody');
  if (!root) return;
  skillsMsg('加载中…');
  try {
    const d = await rpc('skills.list');
    const skills = d.skills || d.items || [];
    root.innerHTML = skills.length
      ? skills.map(skillsCard).join('')
      : '<div class="models-empty">还没有安装技能。请在手机 App 的设置里导入技能包。</div>';
    skillsMsg('');
  } catch (e) {
    root.innerHTML = '<div class="error">' + esc(e.message) + '</div>';
    skillsMsg('加载失败', true);
  }
}

function skillsCard(s) {
  const enabled = s.isEnabled !== false;
  const tags = [];
  if (s.version) tags.push('<span class="tag">v' + esc(s.version) + '</span>');
  if (s.importSource) tags.push('<span class="tag">' + esc(s.importSource) + '</span>');
  tags.push('<span class="tag">使用 ' + esc(String(s.useCount ?? 0)) + ' 次</span>');
  return '<div class="card-list" data-id="' + esc(s.id) + '" data-enabled="' + (enabled ? '1' : '0') + '">' +
    '<div class="card-head">' +
      '<span class="dot-' + (enabled ? 'on' : 'off') + '"></span>' +
      '<strong>' + esc(s.name || s.id) + '</strong>' +
      '<span class="card-sub">' + tags.join('') + '</span>' +
    '</div>' +
    (s.description ? '<div class="list-body"><div class="list-row"><div class="list-main"><div class="list-desc">' + esc(s.description) + '</div></div></div></div>' : '') +
    '<div class="preview-box hidden" data-role="detail"></div>' +
    '<div class="btn-row">' +
      '<button class="secondary" data-act="toggle">' + (enabled ? '停用' : '启用') + '</button>' +
      '<button class="secondary" data-act="detail">详情</button>' +
      '<button class="secondary danger" data-act="delete">删除</button>' +
    '</div></div>';
}

$('#skillsBody').onclick = async e => {
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
      await rpc('skills.toggle', { skillId: id, enabled: enable });
      skillsMsg(enable ? '已启用' : '已停用');
      await loadSkills();
    } else if (act === 'detail') {
      const box = card.querySelector('[data-role="detail"]');
      if (!box.classList.contains('hidden')) {
        box.classList.add('hidden');
        return;
      }
      if (!box.dataset.loaded) {
        const d = await rpc('skills.get', { skillId: id });
        box.textContent = d.body || '(空)';
        box.dataset.loaded = '1';
      }
      box.classList.remove('hidden');
    } else if (act === 'delete') {
      const name = card.querySelector('strong')?.textContent || id;
      if (confirm('删除技能「' + name + '」？此操作不可撤销。')) {
        await rpc('skills.delete', { skillId: id });
        skillsMsg('已删除');
        await loadSkills();
      }
    }
  } catch (err) {
    skillsMsg(err.message, true);
  } finally {
    btn.disabled = false;
    btn.textContent = label;
  }
};

TAB_LOADERS.skills = loadSkills;
$('#skillsRefresh').onclick = loadSkills;
