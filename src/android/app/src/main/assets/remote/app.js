const $=s=>document.querySelector(s), $$=s=>[...document.querySelectorAll(s)];
const state={sessionId:null,sessions:[],poll:null,running:false,filePath:'/var/minis/workspace',editorRevision:null,settingsLoaded:false,attachFiles:[]};

async function api(path,opt={}){
  const h={...(opt.headers||{})};
  if(opt.body&&!h['Content-Type'])h['Content-Type']='application/json';
  const r=await fetch(path,{...opt,headers:h,credentials:'same-origin'});
  let data={};try{data=await r.json()}catch{}
  if(r.status===401&&path!=='/api/auth/login'&&path!=='/api/auth/status'){showLogin();throw new Error('登录已失效')}
  if(!r.ok)throw new Error(data.error||data.output||('HTTP '+r.status));
  return data;
}
function esc(s=''){return String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}
function size(n){if(n<1024)return n+' B';if(n<1048576)return(n/1024).toFixed(1)+' KB';return(n/1048576).toFixed(1)+' MB'}
function toast(msg,type){const wrap=$('#toasts');if(!wrap)return;const el=document.createElement('div');el.className='toast'+(type==='error'?' toast-error':'');el.textContent=msg;wrap.appendChild(el);requestAnimationFrame(()=>el.classList.add('show'));setTimeout(()=>{el.classList.remove('show');setTimeout(()=>el.remove(),400)},3200)}
function showLogin(){clearInterval(state.poll);$('#app').classList.add('hidden');$('#login').classList.remove('hidden');$('#passwordInput').value='';$('#passwordInput').focus()}
async function showApp(){
  $('#login').classList.add('hidden');$('#app').classList.remove('hidden');
  await loadSessions();
  document.querySelector('.tab[data-tab="workbench"]')?.click();
  startPoll();
}
async function login(){
  $('#loginError').textContent='';
  try{
    await api('/api/auth/login',{method:'POST',body:JSON.stringify({username:$('#usernameInput').value.trim(),password:$('#passwordInput').value})});
    $('#passwordInput').value='';await showApp();
  }catch(e){$('#loginError').textContent='登录失败：'+e.message}
}
async function boot(){
  try{const s=await api('/api/auth/status');if(s.authenticated){if(s.username)$('#usernameInput').value=s.username;await showApp()}else showLogin()}
  catch{showLogin()}
}
async function logout(){try{await api('/api/auth/logout',{method:'POST'})}catch{}showLogin()}

async function loadSessions(){const d=await api('/api/sessions?limit=200');state.sessions=d.sessions||[];renderSessions();if(!state.sessionId&&state.sessions.length)selectSession(state.sessions[0].id)}
function renderSessions(){const root=$('#sessions');root.innerHTML=state.sessions.map(s=>`<button class="session ${s.id===state.sessionId?'active':''}" data-id="${esc(s.id)}"><span class="${s.isRunning?'run-dot':''}"></span><span class="session-text"><div class="session-title">${esc(s.title||'新会话')}</div><div class="session-meta">${esc(s.modelName||s.modelId||'')}</div></span><span class="session-more" data-id="${esc(s.id)}" title="更多">⋯</span></button>`).join('');$$('.session').forEach(x=>x.onclick=()=>selectSession(x.dataset.id));$$('.session-more').forEach(x=>x.onclick=e=>{e.stopPropagation();const r=x.getBoundingClientRect();openSessionMenu({clientX:r.right-8,clientY:r.bottom+4,preventDefault(){},stopPropagation(){}},x.dataset.id)})}
async function selectSession(id){clearQuestionCard();clearApprovalCard();state.sessionId=id;renderSessions();$('.sidebar').classList.remove('open');const s=state.sessions.find(x=>x.id===id);$('#title').textContent=s?.title||'新会话';$('#model').textContent=s?.modelName||'';await Promise.all([loadMessages(),loadFiles(),document.querySelector('.tab[data-tab="workbench"]')?.classList.contains('active')?loadWorkbench():Promise.resolve()]);$('#prompt').focus()}
function textOf(m){if(m.content)return m.content;if(Array.isArray(m.parts))return m.parts.filter(p=>p.type==='text').map(p=>p.value||p.text||'').join('');return''}
function toolsHtml(m){
  if(!Array.isArray(m.toolCalls)||!m.toolCalls.length)return '';
  const names=m.toolCalls.map(t=>esc(t.name||t.toolName||t.type||'tool'));
  return '<div class="tool-card"><div class="tool-card-head"><span class="tool-dot'+(m.isStreaming?' running':' done')+'"></span><span>工具</span><span class="tool-count">'+names.length+'</span></div>'+
    '<div class="tool-card-list">'+names.join('<span class="tool-sep">·</span>')+'</div></div>';
}
function bubbleHtml(m){
  // User text is shown verbatim — they typed it, and rendering their own
  // asterisks as bold would misrepresent what they sent. Only model output
  // goes through Markdown.
  const text=textOf(m);
  const body=m.role==='user'?esc(text).replace(/\n/g,'<br>'):MD.render(text);
  return body+toolsHtml(m);
}
function messageEl(m){
  const el=document.createElement('div');
  el.className='message '+(m.role==='user'?'user':'assistant');
  el.dataset.id=m.id||('tmp-'+Math.random());
  el.innerHTML='<div class="role">'+(m.role==='user'?'你':'Minis')+'</div><div class="bubble"></div>';
  el.querySelector('.bubble').innerHTML=bubbleHtml(m);
  if(m.role==='assistant'&&m.id){
    el.querySelector('.bubble').insertAdjacentHTML('beforeend',
      '<div class="feedback-bar" data-fb="'+esc(m.id)+'"><button class="fb-up" title="有用">👍</button><button class="fb-down" title="不满意">👎</button></div>');
  }
  el.dataset.sig=signatureOf(m);
  return el;
}
// Content grows token by token while the agent streams, so the signature has
// to include the length — not just the id — to notice an in-place update.
function signatureOf(m){
  const t=textOf(m);
  return (m.id||'')+':'+t.length+':'+((m.toolCalls||[]).length);
}
async function loadMessages(){
  if(!state.sessionId)return;
  const d=await api('/api/messages?sessionId='+encodeURIComponent(state.sessionId)+'&limit=500');
  const root=$('#messages');const msgs=d.messages||[];
  if(!msgs.length){root.innerHTML='<div class="empty-state"><h2>新会话</h2><p>输入一条消息开始。</p></div>';return}
  // Stick to the bottom only when the reader is already there; otherwise a
  // streaming reply would keep yanking the view away from what they scrolled to.
  const atBottom=root.scrollHeight-root.scrollTop-root.clientHeight<90;
  const empty=root.querySelector('.empty-state');if(empty)empty.remove();

  const existing=new Map();
  [...root.children].forEach(el=>{if(el.dataset.id)existing.set(el.dataset.id,el)});

  let prev=null;
  msgs.forEach(m=>{
    const id=m.id||'';const el=existing.get(id);
    if(el){
      const sig=signatureOf(m);
      if(el.dataset.sig!==sig){el.querySelector('.bubble').innerHTML=bubbleHtml(m);el.dataset.sig=sig}
      existing.delete(id);
      prev=el;
    }else{
      const fresh=messageEl(m);
      if(prev&&prev.nextSibling)root.insertBefore(fresh,prev.nextSibling);else root.appendChild(fresh);
      prev=fresh;
    }
  });
  existing.forEach(el=>el.remove());
  if(atBottom)root.scrollTop=root.scrollHeight;
}
async function loadStatus(){if(!state.sessionId)return;try{const s=await api('/api/session/status?sessionId='+encodeURIComponent(state.sessionId));state.running=!!s.isRunning;$('#runState').textContent=s.isRunning?'生成中':'空闲';$('#runState').classList.toggle('running',!!s.isRunning);$('#cancelBtn').disabled=!s.isRunning}catch{}}
async function send(){if(!$('#questionCard').classList.contains('hidden'))return;const text=$('#prompt').value.trim();if(!text)return;$('#sendBtn').disabled=true;try{const body={prompt:text,wait:false};if(state.sessionId)body.sessionId=state.sessionId;const attach=state.attachFiles||[];if(attach.length){const attachments=[];for(const f of attach){try{attachments.push(await fileToAttachment(f))}catch{}}if(attachments.length)body.attachments=attachments;}const d=await api('/api/prompt',{method:'POST',body:JSON.stringify(body)});$('#prompt').value='';autoGrow();state.attachFiles=[];renderAttachChips();if(!state.sessionId)state.sessionId=d.sessionId;await loadSessions();await loadMessages();await loadStatus()}catch(e){toast(e.message)}finally{$('#sendBtn').disabled=false}}
async function cancel(){if(!state.sessionId)return;await api('/api/cancel',{method:'POST',body:JSON.stringify({sessionId:state.sessionId})});await loadStatus()}

async function loadFiles(path=state.filePath){if(!state.sessionId)return;state.filePath=path;$('#filePath').value=path;try{const d=await api('/api/files?sessionId='+encodeURIComponent(state.sessionId)+'&path='+encodeURIComponent(path));$('#fileList').innerHTML=(d.items||[]).map(f=>`<div class="file-row" data-path="${esc(f.path)}" data-dir="${f.directory}"><span class="file-icon">${f.directory?'▸':'·'}</span><span class="file-name">${esc(f.name)}</span><span class="file-size">${f.directory?'':size(f.size)}</span></div>`).join('');$$('.file-row').forEach(x=>x.onclick=()=>x.dataset.dir==='true'?loadFiles(x.dataset.path):openFile(x.dataset.path))}catch(e){
    // The default workspace path does not exist until the sandbox has been
    // initialised at least once; fall back to the rootfs root instead of
    // showing a dead panel.
    if(path!=='/'&&/not a directory|no such file|not found/i.test(e.message||'')){return loadFiles('/')}
    $('#fileList').innerHTML='<div class="error">'+esc(e.message)+'</div>';
  }}
async function openFile(path){try{const d=await api('/api/file?sessionId='+encodeURIComponent(state.sessionId)+'&path='+encodeURIComponent(path));state.editorRevision=d.sha256||null;$('#editorPath').textContent=path;$('#fileContent').value=d.content||'';$('#editor').classList.remove('hidden')}catch(e){toast('打开文件失败：'+e.message)}}
async function saveFile(){const path=$('#editorPath').textContent;if(!path)return;try{const d=await api('/api/file',{method:'PUT',body:JSON.stringify({sessionId:state.sessionId,path,content:$('#fileContent').value,expectedSha256:state.editorRevision})});state.editorRevision=d.sha256||state.editorRevision;await loadFiles();$('#saveFile').textContent='已保存';setTimeout(()=>$('#saveFile').textContent='保存文件',800)}catch(e){toast('保存失败：'+e.message+'\n如果文件被 Agent 修改过，请重新打开后再编辑。')}}
async function runShell(){if(!state.sessionId)return toast('请先选择会话');const cmd=$('#shellCommand').value.trim();if(!cmd)return;$('#shellRun').disabled=true;$('#shellOutput').textContent+='\n$ '+cmd+'\n';try{const d=await api('/api/shell',{method:'POST',body:JSON.stringify({sessionId:state.sessionId,command:cmd})});$('#shellOutput').textContent+=d.output+`\n[exit ${d.exitCode}, ${d.durationMs} ms]`+(d.fullOutputPath?`\n[full: ${d.fullOutputPath}]`:'')+'\n';$('#shellOutput').scrollTop=$('#shellOutput').scrollHeight}catch(e){$('#shellOutput').textContent+='ERROR: '+e.message+'\n'}finally{$('#shellRun').disabled=false}}

function renderTunnelStatus(t){
  const label=t?.running?'已连接':(t?.detail||t?.phase||'未连接');
  $('#tunnelStatusText').textContent=label;
}
async function loadSettings(){
  const d=await api('/api/settings');state.settingsLoaded=true;
  $('#settingsUsername').value=d.username||'';$('#settingsPort').value=d.port||8765;$('#settingsLan').checked=!!d.lanAccess;
  $('#settingsTunnelEnabled').checked=!!d.cloudflareTunnelEnabled;$('#settingsHostname').value=d.cloudflareHostname||'';
  $('#settingsTunnelToken').placeholder=d.cloudflareTunnelTokenConfigured?'已安全保存；留空保持不变':'粘贴 Tunnel Token';renderTunnelStatus(d.tunnel);
  $('#settingsCurrentPassword').value='';$('#settingsNewPassword').value='';$('#restartRemote').classList.add('hidden');
  try{const p=await rpc('settings.permissionPreset.get');if($('#permissionPreset'))$('#permissionPreset').value=p.preset||'workspace-write';if($('#permissionNote'))$('#permissionNote').textContent=p.danger?'danger-full-access：全部能力（当前无额外管理员操作）。':'workspace-write：沙箱化 shell + 工作区文件读写 + RPC。'}catch{}
}
async function saveSettings(){
  const b={
    username:$('#settingsUsername').value.trim(),port:Number($('#settingsPort').value),lanAccess:$('#settingsLan').checked,
    cloudflareTunnelEnabled:$('#settingsTunnelEnabled').checked,cloudflareHostname:$('#settingsHostname').value.trim()
  };
  const current=$('#settingsCurrentPassword').value,newPassword=$('#settingsNewPassword').value,tunnelToken=$('#settingsTunnelToken').value.trim();
  if(current)b.currentPassword=current;if(newPassword)b.newPassword=newPassword;if(tunnelToken)b.cloudflareTunnelToken=tunnelToken;
  $('#settingsMessage').textContent='正在保存…';
  try{
    const d=await api('/api/settings',{method:'PATCH',body:JSON.stringify(b)});renderTunnelStatus(d.tunnel);
    $('#settingsCurrentPassword').value='';$('#settingsNewPassword').value='';$('#settingsTunnelToken').value='';
    $('#settingsMessage').textContent=d.reauthRequired?'登录信息已更新，请重新登录。':(d.restartRequired?'已保存。监听地址/端口改变，需要重启 Web Remote。':'已保存。');
    $('#restartRemote').classList.toggle('hidden',!d.restartRequired);
    if(d.reauthRequired)setTimeout(showLogin,650);
  }catch(e){$('#settingsMessage').textContent='保存失败：'+e.message}
}
async function restartRemote(){
  $('#settingsMessage').textContent='远程服务正在重启，当前连接会短暂断开…';
  try{await api('/api/settings/restart',{method:'POST',body:'{}'})}catch{}
  setTimeout(()=>location.reload(),1400);
}

function startPoll(){
  clearTimeout(state.poll);clearInterval(state.poll);
  let sessionTick=0;
  const tick=async()=>{
    if(!document.hidden){
      try{
        // The session list only changes on create/rename, so it does not need
        // the fast cadence the running reply does.
        if(sessionTick++%4===0){await loadSessions();await loadAgentBars();await loadUsage();if(document.querySelector('.tab[data-tab="workbench"]')?.classList.contains('active'))await loadWorkbench()}
        if(state.sessionId){await loadStatus();await loadMessages();if(state.running){await pollQuestions();await pollApprovals()}}
      }catch{}
    }
    state.poll=setTimeout(tick,state.running?450:2500);
  };
  state.poll=setTimeout(tick,250);
}
function autoGrow(){const t=$('#prompt');t.style.height='auto';t.style.height=Math.min(t.scrollHeight,180)+'px'}

// ── 模型提问卡片（ask_user_question） ─────────────────────────────────────
function clearQuestionCard(){
  const card=$('#questionCard');
  if(card)card.classList.add('hidden');
  state.question=null;
  const sendBtn=$('#sendBtn');
  if(sendBtn)sendBtn.disabled=false;
}

async function pollQuestions(){
  if(!state.sessionId)return;
  const card=$('#questionCard');
  if(!card)return;
  try{
    const d=await rpc('chat.question.pending',{sessionId:state.sessionId});
    const list=d?.questions||[];
    const q=list.find(x=>x.id!==state.question?.id)||list[0];
    if(q){
      if(state.question?.id!==q.id)renderQuestionCard(q);
      $('#sendBtn').disabled=true;
    }else if(!state.question){
      clearQuestionCard();
    }
  }catch{}
}

function renderQuestionCard(q){
  state.question={id:q.id,multiple:!!q.multiple};
  const card=$('#questionCard');
  const opts=(q.options||[]).map((o,i)=>{
    const id='qopt_'+q.id+'_'+i;
    const tag=o.recommended?' <span class="q-rec">推荐</span>':'';
    return '<label class="q-option"><input type="'+(q.multiple?'checkbox':'radio')+'" name="q_'+esc(q.id)+'" value="'+esc(o.value)+'" />'+
      '<span>'+esc(o.label||o.value)+tag+'</span></label>';
  }).join('');
  const custom=q.allowCustom===false?'':'<label class="q-custom"><span>自定义答案</span><textarea id="qCustom" rows="2" placeholder="输入自己的答案…"></textarea></label>';
  const submit=q.multiple?'<button id="qSubmit" class="primary">提交答案</button>':'';
  card.innerHTML='<div class="question-card-inner">'+
    '<div class="question-card-head"><span class="dot-on"></span><strong>模型在等你回答</strong>'+
    '<button id="qSkip" class="ghost">跳过</button></div>'+
    '<div class="question-card-body">'+esc(q.prompt||'')+'</div>'+
    (opts?'<div class="q-options">'+opts+'</div>':'')+
    custom+
    (submit?'<div class="btn-row">'+submit+'</div>':'')+
    '</div>';
  card.classList.remove('hidden');
  $('#sendBtn').disabled=true;
  card.querySelectorAll('.q-option input').forEach(inp=>{
    inp.onchange=()=>{
      if(!q.multiple)submitQuestion(false,[inp.value],'');
    };
  });
  $('#qSkip').onclick=()=>submitQuestion(true,[],'');
  const qSubmit=$('#qSubmit');
  if(qSubmit)qSubmit.onclick=()=>{
    const sel=Array.from(card.querySelectorAll('.q-option input:checked')).map(x=>x.value);
    const custom=$('#qCustom')?.value?.trim()||'';
    if(sel.length===0&&!custom){toast('请至少选择一个选项或填写自定义答案');return;}
    submitQuestion(false,sel,custom);
  };
}

async function submitQuestion(skipped,selected,custom){
  const q=state.question;
  if(!q)return;
  state.question=null;
  try{
    const body={questionId:q.id,skipped:!!skipped};
    if(selected&&selected.length)body.selected=selected;
    if(custom)body.custom=custom;
    await rpc('chat.question.answer',body);
  }catch(e){
    toast(e.message||'提交答案失败');
  }finally{
    clearQuestionCard();
  }
}

// ── 一次性危险操作审批（与手机端共享 ApprovalSeam） ──────────────────────
function clearApprovalCard(){
  const card=$('#approvalCard');
  if(card)card.classList.add('hidden');
  state.approval=null;
}
async function pollApprovals(){
  if(!state.sessionId)return;
  const card=$('#approvalCard');if(!card)return;
  try{
    const d=await rpc('agent.approval.list',{sessionId:state.sessionId});
    const a=(d?.approvals||[])[0];
    if(a){if(state.approval?.id!==a.id)renderApprovalCard(a)}
    else clearApprovalCard();
  }catch{}
}
function renderApprovalCard(a){
  state.approval={id:a.id};
  const card=$('#approvalCard');
  card.innerHTML='<div class="question-card-inner approval-card-inner">'+
    '<div class="question-card-head"><strong>需要批准危险操作</strong><span class="q-rec">仅本次</span></div>'+
    '<p>Agent 想执行以下可能造成破坏的操作，请核对后决定。</p>'+
    '<pre class="approval-summary">'+esc(a.tool||'tool')+'\n'+esc(a.summary||'')+'</pre>'+
    '<div class="question-actions"><button class="mini danger-ghost" id="approvalDeny">拒绝</button><button class="mini" id="approvalAllow">仅此一次允许</button></div></div>';
  card.classList.remove('hidden');
  $('#approvalDeny').onclick=()=>answerApproval(a.id,false);
  $('#approvalAllow').onclick=()=>answerApproval(a.id,true);
}
async function answerApproval(id,allowed){
  try{await rpc('agent.approval.answer',{approvalId:id,allowed:!!allowed})}
  catch(e){toast(e.message||'提交审批结果失败')}
  finally{clearApprovalCard()}
}

// ── 会话全文搜索 ──────────────────────────────────────────────────────────
async function doSearch(){
  const q=$('#searchQuery').value.trim();
  const box=$('#searchResults');
  if(!q)return;
  box.classList.remove('hidden');
  box.innerHTML='<div class="search-head"><span>搜索中…</span></div>'+
    '<div class="skeleton"></div><div class="skeleton"></div><div class="skeleton"></div>';
  try{
    const d=await rpc('chat.search',{query:q,limit:20});
    renderSearchResults(d);
  }catch(e){
    box.innerHTML='<div class="search-note error">'+esc(e.message||'搜索失败')+'</div>';
  }
}

function renderSearchResults(d){
  const box=$('#searchResults');
  const list=d?.results||[];
  box.innerHTML='<div class="search-head"><span>'+(d?.count||0)+' 个会话命中</span><button id="searchClear" class="ghost">清空</button></div>'+
    (list.length?list.map(r=>{
      const hits=(r.hits||[]).map(h=>
        '<div class="search-hit"><span class="search-role">'+esc(h.role||'')+'</span>'+
        '<span class="search-time">'+new Date(h.createdAt||Date.now()).toLocaleString()+'</span>'+
        '<div class="search-snippet">'+esc(h.content||'')+'</div></div>'
      ).join('');
      return '<div class="search-row" data-id="'+esc(r.sessionId)+'">'+
        '<div class="search-title">'+esc(r.title||'未命名会话')+' <span class="tag">'+r.matchedCount+' 条</span></div>'+
        '<div class="search-snippet">'+esc(r.snippet||'')+'</div>'+
        (hits?'<div class="search-hits">'+hits+'</div>':'')+
        '</div>';
    }).join(''):'<div class="search-note">没有匹配的会话。</div>');
  box.querySelectorAll('.search-row').forEach(row=>{
    row.onclick=()=>{
      selectSession(row.dataset.id);
      clearSearchResults();
    };
  });
  $('#searchClear').onclick=clearSearchResults;
}

function clearSearchResults(){
  const box=$('#searchResults');
  if(box)box.classList.add('hidden');
  const input=$('#searchQuery');
  if(input)input.value='';
}

// ── 附件（沿用 chat.prompt 的 attachments 契约） ─────────────────────────
function fileToAttachment(file){
  return new Promise((resolve,reject)=>{
    const r=new FileReader();
    r.onload=()=>resolve({name:file.name,data:String(r.result).split(',')[1]||'',mime:file.type||'application/octet-stream'});
    r.onerror=reject;
    r.readAsDataURL(file);
  });
}
function renderAttachChips(){
  const wrap=$('#attachChips');if(!wrap)return;
  const files=state.attachFiles||[];
  if(!files.length){wrap.classList.add('hidden');wrap.innerHTML='';return}
  wrap.classList.remove('hidden');
  wrap.innerHTML=files.map((f,i)=>'<span class="attach-chip">'+esc(f.name)+' <button data-i="'+i+'" title="移除">×</button></span>').join('');
  wrap.querySelectorAll('button').forEach(b=>b.onclick=()=>{state.attachFiles.splice(+b.dataset.i,1);renderAttachChips()});
}

// ── 目标 / 待办 / 计划 / 产出文件条（DeepSeek Harness 风格） ──────────────
async function loadAgentBars(){
  if(!state.sessionId)return;
  try{
    const [g,t,p,d]=await Promise.all([
      rpc('agent.goal.get',{sessionId:state.sessionId}),
      rpc('agent.todo.get',{sessionId:state.sessionId}),
      rpc('agent.plan.get',{sessionId:state.sessionId}),
      rpc('agent.deliverables.list',{sessionId:state.sessionId}),
    ]);
    renderGoalBar(g||{});renderTodoBar(t||{});renderPlanBanner(p||{});renderDeliverablesBar(d||{});
  }catch{}
}

function renderGoalBar(g){
  const bar=$('#goalBar');if(!bar)return;
  if(!g.text){bar.classList.add('hidden');return}
  bar.classList.remove('hidden');
  bar.innerHTML='<span class="agent-bar-icon">🎯</span><span class="agent-bar-text">'+esc(g.text)+'</span>'+
    '<button class="mini" id="goalToggle">'+(g.active?'暂停':'恢复')+'</button>'+
    '<button class="mini danger-ghost" id="goalClear">清除</button>';
  $('#goalToggle').onclick=async()=>{try{await rpc('agent.goal.setActive',{sessionId:state.sessionId,active:!g.active});await loadAgentBars()}catch(e){toast(e.message)}};
  $('#goalClear').onclick=async()=>{try{await rpc('agent.goal.set',{sessionId:state.sessionId,text:''});await loadAgentBars()}catch(e){toast(e.message)}};
}

function renderTodoBar(t){
  const bar=$('#todoBar');if(!bar)return;
  const items=t.items||[];
  if(!items.length){bar.classList.add('hidden');return}
  bar.classList.remove('hidden');
  bar.innerHTML='<span class="agent-bar-icon">☑</span><div class="agent-bar-list">'+
    items.slice(0,5).map(x=>'<span class="todo-item '+(x.status==='completed'?'done':(x.status==='in_progress'?'doing':''))+'">'+esc(x.title)+'</span>').join('')+
    (items.length>5?'<span class="todo-more">+'+ (items.length-5) +'</span>':'')+'</div>';
}

function renderPlanBanner(p){
  const bar=$('#planBanner');if(!bar)return;
  if(p.mode!=='plan'){bar.classList.add('hidden');return}
  bar.classList.remove('hidden');
  bar.innerHTML='<span class="plan-tag">Plan</span><span class="agent-bar-text">'+esc(p.plan||'先计划，再执行。')+'</span>'+
    '<button class="mini" id="planExit">退出计划</button>';
  $('#planExit').onclick=async()=>{try{await rpc('agent.plan.set',{sessionId:state.sessionId,mode:'off'});$('#prompt').placeholder='给 Minis 发消息…';await loadAgentBars()}catch(e){toast(e.message)}};
  $('#prompt').placeholder='描述你的任务以生成计划…';
}

function renderDeliverablesBar(d){
  const bar=$('#deliverablesBar');if(!bar)return;
  const files=d.files||[];
  if(!files.length){bar.classList.add('hidden');return}
  bar.classList.remove('hidden');
  bar.innerHTML='<span class="agent-bar-icon">📄</span><span class="agent-bar-text">本轮产出：</span>'+
    files.slice(0,5).map(f=>'<button class="mini deliverable-link" data-path="'+esc(f.path)+'">'+esc(f.path.split('/').pop())+'</button>').join('')+
    (files.length>5?'<span class="todo-more">+'+ (files.length-5) +'</span>':'');
  bar.querySelectorAll('.deliverable-link').forEach(b=>b.onclick=()=>{
    const path=b.dataset.path;
    document.querySelector('.tab[data-tab="files"]')?.click();
    openFile(path);
  });
}

$('#connectBtn').onclick=login;$('#passwordInput').onkeydown=e=>{if(e.key==='Enter')login()};$('#logoutBtn').onclick=logout;
$('#sendBtn').onclick=send;$('#prompt').oninput=autoGrow;$('#prompt').onkeydown=e=>{if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();send()}};$('#cancelBtn').onclick=cancel;
$('#searchGo').onclick=doSearch;$('#searchQuery').onkeydown=e=>{if(e.key==='Enter')doSearch()};
$('#attachBtn').onclick=()=>$('#attachInput').click();
$('#attachInput').onchange=()=>{state.attachFiles=Array.from($('#attachInput').files||[]);renderAttachChips();$('#attachInput').value=''};
$('#permissionSave').onclick=async()=>{
  const v=$('#permissionPreset').value;
  if(v==='danger-full-access'&&!confirm('Danger Full Access 会放开全部远程能力（当前与默认一致，未来管理员操作将不再拦截）。确定？'))return;
  try{await rpc('settings.permissionPreset.set',{preset:v});$('#settingsMessage').textContent='权限预设已保存';await loadSettings()}catch(e){$('#settingsMessage').textContent='保存失败：'+e.message}
};
$('#newChat').onclick=()=>{state.sessionId=null;clearQuestionCard();clearApprovalCard();renderSessions();$('#title').textContent='新会话';$('#model').textContent='';$('#messages').innerHTML='<div class="empty-state"><h2>新会话</h2><p>发送第一条消息后会自动创建。</p></div>';if(document.querySelector('.tab[data-tab="workbench"]')?.classList.contains('active'))loadWorkbench();$('#prompt').focus()};
$('#filePath').onkeydown=e=>{if(e.key==='Enter')loadFiles($('#filePath').value)};$('#fileUp').onclick=()=>{const p=state.filePath.replace(/\/$/,'').split('/').slice(0,-1).join('/')||'/';loadFiles(p)};$('#closeEditor').onclick=()=>$('#editor').classList.add('hidden');$('#saveFile').onclick=saveFile;$('#shellRun').onclick=runShell;
// 各工具页的懒加载器；分页脚本（skills/memory/mcp/scheduled）加载后自行注册。
const TAB_LOADERS={settings:loadSettings,models:loadModels};
$$('.tab').forEach(b=>b.onclick=async()=>{$$('.tab').forEach(x=>x.classList.toggle('active',x===b));$$('.tool-tab').forEach(x=>x.classList.remove('active'));$('#'+b.dataset.tab+'Tab').classList.add('active');const fn=TAB_LOADERS[b.dataset.tab];if(fn)await fn()});
$('#saveSettings').onclick=saveSettings;$('#restartRemote').onclick=restartRemote;$('#mobileMenu').onclick=()=>$('.sidebar').classList.toggle('open');$('#toolsToggle').onclick=()=>$('.tools-pane').classList.toggle('open');
$('#messages').addEventListener('click',async e=>{
  const btn=e.target.closest('.code-copy');if(!btn)return;
  const code=btn.closest('.code-block')?.dataset.code||'';
  try{await navigator.clipboard.writeText(code);btn.textContent='已复制'}
  catch{
    // clipboard API needs a secure context; plain http://LAN has none.
    const ta=document.createElement('textarea');ta.value=code;document.body.appendChild(ta);
    ta.select();try{document.execCommand('copy');btn.textContent='已复制'}catch{btn.textContent='复制失败'}
    ta.remove();
  }
  setTimeout(()=>{btn.textContent='复制'},1200);
});
$('#messages').addEventListener('click',async e=>{
  const btn=e.target.closest('.fb-up,.fb-down');if(!btn)return;
  const bar=btn.closest('.feedback-bar');if(!bar)return;
  const id=bar.dataset.fb;const kind=btn.classList.contains('fb-up')?'up':'down';
  bar.querySelectorAll('button').forEach(x=>x.classList.remove('active'));
  btn.classList.add('active');
  try{await rpc('chat.feedback.put',{messageId:id,kind})}catch{}
});

// ---------------------------------------------------------------- 模型管理
// 走 /api/rpc 转发到 App 自己的 provider.* 方法，不另造一套后端逻辑。
async function rpc(method, params = {}) {
  const d = await api('/api/rpc', {
    method: 'POST',
    body: JSON.stringify({ jsonrpc: '2.0', id: Date.now(), method, params }),
  });
  if (d.error) throw new Error(d.error.message || ('RPC ' + d.error.code));
  return d.result;
}

function modelsMsg(text, isError) {
  const el = $('#modelsMsg');
  if (!el) return;
  el.textContent = text || '';
  el.classList.toggle('error-text', !!isError);
}

async function loadModels() {
  const root = $('#modelsBody');
  if (!root) return;
  modelsMsg('加载中…');
  try {
    const [inst, groups, agent] = await Promise.all([
      rpc('provider.instances.list'),
      rpc('provider.groups.list'),
      rpc('agent.settings.get').catch(() => ({})),
    ]);
    const instances = inst?.instances || inst?.items || [];
    const groupList = groups?.groups || groups?.items || [];
    state.instances = instances;
    state.primaryGroupId = groups?.defaultGroupId || '';
    state.subGroupId = groups?.defaultSubGroupId || '';
    state.agentSettings = agent || {};

    // 逐个实例取模型；串行请求即可，实例数量是个位数
    const blocks = [];
    for (const it of instances) {
      let models = [];
      try {
        const m = await rpc('provider.models.list', { instanceId: it.id });
        models = m?.models || m?.entries || m?.items || [];
      } catch (e) {
        models = [];
      }
      blocks.push({ inst: it, models });
    }

    root.innerHTML = renderModels(blocks, groupList, groups, agent);
    bindModelActions();
    bindAgentDefaults();
    modelsMsg('');
  } catch (e) {
    root.innerHTML = '<div class="error">' + esc(e.message) + '</div>';
    modelsMsg('加载失败', true);
  }
}

function renderModels(blocks, groups, groupsMeta = {}, agent = {}) {
  let html = '';

  html += renderAgentDefaults(groupsMeta, groups, agent);
  html += '<div class="settings-section-title" style="padding:16px 16px 8px">供应商</div>';
  if (!blocks.length) {
    html += '<div class="models-empty">还没有配置任何供应商。请在手机 App 里添加，或使用下方的导入。</div>';
  }
  blocks.forEach(({ inst, models }) => {
    const enabled = inst.isEnabled !== false;
    html += '<div class="model-card">' +
      '<div class="model-card-head">' +
        '<span class="model-dot ' + (enabled ? 'on' : '') + '"></span>' +
        '<strong>' + esc(inst.label || inst.name || inst.id) + '</strong>' +
        '<span class="model-type">' + esc(inst.providerType || inst.type || '') + '</span>' +
        '<button class="mini" data-act="test" data-id="' + esc(inst.id) + '">测试</button>' +
        '<button class="mini" data-act="refresh" data-id="' + esc(inst.id) + '">刷新模型</button>' +
      '</div>';
    if (models.length) {
      html += '<div class="model-list">';
      models.slice(0, 60).forEach(m => {
        const name = m.displayName || m.name || m.model?.name || m.model?.id || m.id;
        html += '<div class="model-row"><span class="model-name">' + esc(name) + '</span>' +
                (m.isHidden ? '<span class="model-flag">隐藏</span>' : '') + '</div>';
      });
      if (models.length > 60) html += '<div class="model-row model-more">…共 ' + models.length + ' 个</div>';
      html += '</div>';
    } else {
      html += '<div class="model-list"><div class="model-row model-more">（无模型，点「刷新模型」拉取）</div></div>';
    }
    html += '</div>';
  });

  html += '<div class="settings-section-title" style="padding:20px 16px 8px">模型组</div>';
  if (!groups.length) {
    html += '<div class="models-empty">还没有模型组。</div>';
  }
  groups.forEach(g => {
    html += '<div class="model-card"><div class="model-card-head">' +
      '<strong>' + esc(g.name || g.id) + '</strong>' +
      (g.isDefault ? '<span class="model-flag primary">主代理</span>' : '') +
      (g.isSub ? '<span class="model-flag">子代理</span>' : '') +
      '<span class="model-type">' + ((g.memberEntryIds || g.members || []).length) + ' 个成员</span>' +
      (g.isDefault ? '' : '<button class="mini" data-act="setDefault" data-id="' + esc(g.id) + '">设为默认</button>') +
      '</div></div>';
  });

  return html;
}

function renderAgentDefaults(meta, groups, agent) {
  const primaryId = meta?.defaultGroupId || '';
  const subId = meta?.defaultSubGroupId || '';
  const primary = (groups || []).find(g => g.id === primaryId);
  const sub = (groups || []).find(g => g.id === subId);
  const depth = (agent?.maxDepth != null ? agent.maxDepth : 3);
  const timeout = (agent?.timeoutMinutes != null ? agent.timeoutMinutes : 10);
  return '<div class="card-list">' +
    '<div class="card-head">' +
      '<span class="dot-on"></span>' +
      '<strong>代理设置</strong>' +
      '<span class="card-sub"><span class="tag primary">主代理</span><span class="tag">子代理</span></span>' +
    '</div>' +
    '<div class="agent-current">' +
      '<div class="agent-role"><span class="tag primary">主代理</span>' +
        '<div class="list-main"><div class="list-title">' + esc(primary?.name || '未设置') + '</div>' +
        '<div class="list-desc">主要任务模型组</div></div></div>' +
      '<div class="agent-role"><span class="tag">子代理</span>' +
        '<div class="list-main"><div class="list-title">' + esc(sub?.name || '继承主代理') + '</div>' +
        '<div class="list-desc">轻量任务（标题生成等）</div></div></div>' +
    '</div>' +
    '<div class="agent-roster">' + (groups || []).map(g =>
      '<div class="list-row"><div class="list-main"><div class="list-title">' + esc(g.name || g.id) +
        (g.isDefault ? ' <span class="tag primary">主</span>' : '') +
        (g.isSub ? ' <span class="tag">子</span>' : '') + '</div>' +
        '<div class="list-desc">' + ((g.members || []).length) + ' 个成员</div></div>' +
        '<button class="mini" data-role="primary" data-id="' + esc(g.id) + '"' + (g.isDefault ? ' disabled' : '') + '>设为主代理</button>' +
        '<button class="mini" data-role="sub" data-id="' + esc(g.id) + '"' + (g.isSub ? ' disabled' : '') + '>设为子代理</button>' +
      '</div>'
    ).join('') + '</div>' +
    (subId ? '<div class="btn-row"><button class="secondary" id="clearSubBtn">子代理改回「继承主代理」</button></div>' : '') +
    '<div class="settings-separator"></div>' +
    '<div class="list-desc" style="padding:10px 14px 6px"><strong>子代理委派（subagent）</strong></div>' +
    '<div class="form-grid">' +
      '<label class="form-field">委派深度（层）<input id="agentMaxDepth" type="number" min="1" max="5" value="' + depth + '" /></label>' +
      '<label class="form-field">单任务超时（分钟）<input id="agentTimeout" type="number" min="1" max="30" value="' + timeout + '" /></label>' +
    '</div>' +
    '<div class="btn-row"><button id="agentLimitsSave" class="primary">保存子代理限制</button></div>' +
    '<div class="list-desc" style="padding:2px 14px 12px">' +
      '主/子代理改动只影响之后新建的会话；子代理未设置时继承主代理。' +
      '子代理复用主代理的模型组与工具，无独立人设。' +
    '</div>' +
  '</div>';
}

function bindAgentDefaults() {
  const root = $('#modelsBody');
  if (!root) return;
  root.querySelectorAll('button[data-role]').forEach(btn => {
    btn.onclick = async () => {
      const role = btn.dataset.role, id = btn.dataset.id;
      btn.disabled = true;
      try {
        if (role === 'primary') {
          await rpc('provider.groups.setDefault', { groupId: id });
          modelsMsg('主代理已更新');
        } else {
          await rpc('provider.groups.setSubDefault', { groupId: id });
          modelsMsg('子代理已更新');
        }
        await loadModels();
      } catch (e) {
        modelsMsg(e.message, true);
        btn.disabled = false;
      }
    };
  });
  const clearSub = $('#clearSubBtn');
  if (clearSub) clearSub.onclick = async () => {
    try {
      await rpc('provider.groups.setSubDefault', { groupId: null });
      modelsMsg('子代理已改回继承主代理');
      await loadModels();
    } catch (e) { modelsMsg(e.message, true); }
  };
  const save = $('#agentLimitsSave');
  if (save) save.onclick = async () => {
    const depth = parseInt($('#agentMaxDepth').value, 10);
    const timeout = parseInt($('#agentTimeout').value, 10);
    if (!depth || depth < 1 || depth > 5) { modelsMsg('委派深度需在 1–5 之间', true); return; }
    if (!timeout || timeout < 1 || timeout > 30) { modelsMsg('超时需在 1–30 分钟之间', true); return; }
    save.disabled = true;
    try {
      await rpc('agent.settings.set', { maxDepth: depth, timeoutMinutes: timeout });
      modelsMsg('子代理限制已保存');
      await loadModels();
    } catch (e) {
      modelsMsg(e.message, true);
      save.disabled = false;
    }
  };
}

function bindModelActions() {
  $$('#modelsBody .mini').forEach(btn => {
    btn.onclick = async () => {
      const act = btn.dataset.act, id = btn.dataset.id;
      btn.disabled = true;
      const label = btn.textContent;
      btn.textContent = '…';
      try {
        if (act === 'test') {
          const r = await rpc('provider.instances.test', { instanceId: id });
          modelsMsg(r?.ok === false ? ('连接失败：' + (r.error || r.message || '')) : '连接正常', r?.ok === false);
        } else if (act === 'refresh') {
          const r = await rpc('provider.models.refresh', { instanceId: id });
          modelsMsg('已刷新 ' + (r?.count ?? r?.added ?? '') + ' 个模型');
          await loadModels();
          return;
        } else if (act === 'setDefault') {
          await rpc('provider.groups.setDefault', { groupId: id });
          modelsMsg('已设为默认');
          await loadModels();
          return;
        }
      } catch (e) {
        modelsMsg(e.message, true);
      } finally {
        btn.disabled = false;
        btn.textContent = label;
      }
    };
  });
}

// DeepSeek 的 token 用 body[data-ds-dark-theme] 整体切换暗色，跟随系统即可。
function applyTheme(){
  const mq = window.matchMedia('(prefers-color-scheme: dark)');
  document.body.toggleAttribute('data-ds-dark-theme', mq.matches);
}
applyTheme();
try{ window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', applyTheme); }catch{}

$('#modelsRefresh') && ($('#modelsRefresh').onclick = loadModels);
boot();

/* ---------------------------------------------------------------------------
 * 手机端已有能力的网页入口：模型切换、token 用量、压缩上下文、会话重命名/删除。
 * 后端全部转调既有的 Debug RPC 方法，这里只负责界面。
 * ------------------------------------------------------------------------- */

state.models = [];
state.menuFor = null;

function fmtTokens(n) {
  if (!n && n !== 0) return '';
  if (n < 1000) return n + '';
  if (n < 1000000) return (n / 1000).toFixed(n < 10000 ? 1 : 0) + 'k';
  return (n / 1000000).toFixed(1) + 'M';
}

async function loadUsage() {
  if (!state.sessionId) return;
  try {
    const d = await api('/api/usage?sessionId=' + encodeURIComponent(state.sessionId));
    // 后端字段名随版本略有出入，取第一个像总数的
    // ?? 与 || 不能混写（JS 规范禁止），最后一项本身已保证是数字
    const total = d.totalTokens ?? d.total ?? d.tokens ??
      ((d.inputTokens || 0) + (d.outputTokens || 0));
    $('#usagePill').textContent = total ? fmtTokens(total) + ' tok' : '';
  } catch { $('#usagePill').textContent = ''; }
}

async function openModelSheet() {
  if (!state.sessionId) return toast('请先选择会话');
  const sheet = $('#modelSheet');
  const list = $('#modelList');
  list.innerHTML = '<div class="sheet-empty">正在加载…</div>';
  sheet.classList.remove('hidden');
  try {
    const d = await api('/api/models');
    state.models = d.models || d.entries || [];
    if (!state.models.length) { list.innerHTML = '<div class="sheet-empty">没有可用模型</div>'; return; }
    list.innerHTML = state.models.map(m => {
      const id = m.entryId || m.id || '';
      const name = m.displayName || m.name || m.model || id;
      const sub = [m.providerName || m.provider || '', m.model || ''].filter(Boolean).join(' · ');
      return '<button class="sheet-row" data-id="' + esc(id) + '">' +
        '<span class="sheet-row-main">' + esc(name) + '</span>' +
        (sub ? '<span class="sheet-row-sub">' + esc(sub) + '</span>' : '') + '</button>';
    }).join('');
    $$('#modelList .sheet-row').forEach(b => b.onclick = () => pickModel(b.dataset.id));
  } catch (e) {
    list.innerHTML = '<div class="sheet-empty">加载失败：' + esc(e.message) + '</div>';
  }
}

async function pickModel(entryId) {
  try {
    await api('/api/session/model', {
      method: 'POST',
      body: JSON.stringify({ sessionId: state.sessionId, modelEntryId: entryId }),
    });
    $('#modelSheet').classList.add('hidden');
    await loadSessions();
    const s = state.sessions.find(x => x.id === state.sessionId);
    if (s) $('#model').textContent = s.modelName || s.modelId || '';
  } catch (e) { toast('切换模型失败：' + e.message); }
}

async function doCompact() {
  if (!state.sessionId) return;
  if (!confirm('压缩当前上下文？之前的对话会被摘要替代，可在手机端撤销。')) return;
  const btn = $('#compactBtn');
  btn.disabled = true; btn.textContent = '压缩中…';
  try {
    await api('/api/compact', { method: 'POST', body: JSON.stringify({ sessionId: state.sessionId }) });
    await loadMessages();
  } catch (e) { toast('压缩失败：' + e.message); }
  finally { btn.disabled = false; btn.textContent = '压缩'; }
}

async function newSession() {
  try {
    const d = await api('/api/session/new', { method: 'POST', body: '{}' });
    await loadSessions();
    if (d.sessionId) await selectSession(d.sessionId);
  } catch (e) { toast('新建会话失败：' + e.message); }
}

function openSessionMenu(ev, id) {
  ev.preventDefault();
  ev.stopPropagation();
  state.menuFor = id;
  const m = $('#sessionMenu');
  m.classList.remove('hidden');
  // 贴着点击位置，但不越出视口右/下边缘
  const mw = 160, mh = 88;
  m.style.left = Math.min(ev.clientX, window.innerWidth - mw - 8) + 'px';
  m.style.top = Math.min(ev.clientY, window.innerHeight - mh - 8) + 'px';
}

async function renameSession() {
  const id = state.menuFor; if (!id) return;
  const cur = state.sessions.find(x => x.id === id);
  const name = prompt('新的会话名称', cur?.title || '');
  if (name == null || !name.trim()) return;
  try {
    await api('/api/session/title', { method: 'POST', body: JSON.stringify({ sessionId: id, title: name.trim() }) });
    await loadSessions();
    if (id === state.sessionId) $('#title').textContent = name.trim();
  } catch (e) { toast('重命名失败：' + e.message); }
}

async function deleteSession() {
  const id = state.menuFor; if (!id) return;
  const cur = state.sessions.find(x => x.id === id);
  if (!confirm('删除会话「' + (cur?.title || id) + '」？此操作不可撤销。')) return;
  try {
    await api('/api/session/delete', { method: 'POST', body: JSON.stringify({ sessionId: id }) });
    if (id === state.sessionId) { state.sessionId = null; $('#messages').innerHTML = ''; }
    await loadSessions();
    if (state.sessionId == null && state.sessions.length) await selectSession(state.sessions[0].id);
  } catch (e) { toast('删除失败：' + e.message); }
}

$('#model').onclick = openModelSheet;
$('#modelSheetClose').onclick = () => $('#modelSheet').classList.add('hidden');
$('#modelSheet').onclick = e => { if (e.target.id === 'modelSheet') $('#modelSheet').classList.add('hidden'); };
$('#compactBtn').onclick = doCompact;
$('#newChat').onclick = newSession;
$('#sessionMenu').onclick = e => {
  const act = e.target.dataset.act;
  $('#sessionMenu').classList.add('hidden');
  if (act === 'rename') renameSession();
  if (act === 'delete') deleteSession();
};
document.addEventListener('click', e => {
  if (!e.target.closest('#sessionMenu')) $('#sessionMenu').classList.add('hidden');
});
$('#commandBtn').onclick=e=>{
  e.stopPropagation();
  const m=$('#commandMenu');
  m.classList.toggle('hidden');
  if(!m.classList.contains('hidden')){
    const r=$('#commandBtn').getBoundingClientRect();
    m.style.left=Math.min(r.right-180,window.innerWidth-188)+'px';
    m.style.top=Math.max(8,r.top-184)+'px';
  }
};
$('#commandMenu').onclick=async e=>{
  const act=e.target.dataset.act;
  $('#commandMenu').classList.add('hidden');
  if(act==='newChat')newSession();
  else if(act==='compact')doCompact();
  else if(act==='plan')togglePlanMode();
  else if(act==='settings'){document.querySelector('.tab[data-tab="settings"]')?.click();$('.tools-pane').classList.add('open')}
};
document.addEventListener('click', e => {
  if(!e.target.closest('#commandMenu')&&!e.target.closest('#commandBtn'))$('#commandMenu').classList.add('hidden');
});
async function togglePlanMode(){
  if(!state.sessionId)return toast('请先选择会话','error');
  try{
    const p=await rpc('agent.plan.get',{sessionId:state.sessionId});
    const on=p?.mode==='plan';
    await rpc('agent.plan.set',{sessionId:state.sessionId,mode:on?'off':'plan'});
    $('#prompt').placeholder=on?'给 Minis 发消息…':'描述你的任务以生成计划…';
    await loadAgentBars();
    toast(on?'已退出计划模式':'已进入计划模式');
  }catch(err){toast(err.message,'error')}
}
// 会话项：右键（桌面）与长按（触屏）都能唤出菜单
$('#sessions').addEventListener('contextmenu', e => {
  const row = e.target.closest('.session');
  if (row) openSessionMenu(e, row.dataset.id);
});
let pressTimer = null;
$('#sessions').addEventListener('touchstart', e => {
  const row = e.target.closest('.session');
  if (!row) return;
  pressTimer = setTimeout(() => {
    const t = e.touches[0];
    openSessionMenu({ preventDefault(){}, stopPropagation(){}, clientX: t.clientX, clientY: t.clientY }, row.dataset.id);
  }, 500);
}, { passive: true });
['touchend', 'touchmove', 'touchcancel'].forEach(ev =>
  $('#sessions').addEventListener(ev, () => clearTimeout(pressTimer), { passive: true }));
