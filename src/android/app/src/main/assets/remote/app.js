const $=s=>document.querySelector(s), $$=s=>[...document.querySelectorAll(s)];
const state={sessionId:null,sessions:[],poll:null,running:false,filePath:'/var/minis/workspace',editorRevision:null,settingsLoaded:false};

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
function showLogin(){clearInterval(state.poll);$('#app').classList.add('hidden');$('#login').classList.remove('hidden');$('#passwordInput').value='';$('#passwordInput').focus()}
async function showApp(){
  $('#login').classList.add('hidden');$('#app').classList.remove('hidden');
  await loadSessions();startPoll();
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
function renderSessions(){const root=$('#sessions');root.innerHTML=state.sessions.map(s=>`<button class="session ${s.id===state.sessionId?'active':''}" data-id="${esc(s.id)}"><span class="${s.isRunning?'run-dot':''}"></span><span class="session-text"><div class="session-title">${esc(s.title||'新会话')}</div><div class="session-meta">${esc(s.modelName||s.modelId||'')}</div></span></button>`).join('');$$('.session').forEach(x=>x.onclick=()=>selectSession(x.dataset.id))}
async function selectSession(id){state.sessionId=id;renderSessions();$('.sidebar').classList.remove('open');const s=state.sessions.find(x=>x.id===id);$('#title').textContent=s?.title||'新会话';$('#model').textContent=s?.modelName||'';await Promise.all([loadMessages(),loadFiles()]);$('#prompt').focus()}
function textOf(m){if(m.content)return m.content;if(Array.isArray(m.parts))return m.parts.filter(p=>p.type==='text').map(p=>p.value||p.text||'').join('');return''}
function toolsHtml(m){
  if(!Array.isArray(m.toolCalls)||!m.toolCalls.length)return '';
  return '<div class="tool-card">'+m.toolCalls.map(t=>esc(t.name||t.toolName||t.type||'tool')).join(' · ')+'</div>';
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
async function send(){const text=$('#prompt').value.trim();if(!text)return;$('#sendBtn').disabled=true;try{const body={prompt:text,wait:false};if(state.sessionId)body.sessionId=state.sessionId;const d=await api('/api/prompt',{method:'POST',body:JSON.stringify(body)});$('#prompt').value='';autoGrow();if(!state.sessionId)state.sessionId=d.sessionId;await loadSessions();await loadMessages();await loadStatus()}catch(e){alert(e.message)}finally{$('#sendBtn').disabled=false}}
async function cancel(){if(!state.sessionId)return;await api('/api/cancel',{method:'POST',body:JSON.stringify({sessionId:state.sessionId})});await loadStatus()}

async function loadFiles(path=state.filePath){if(!state.sessionId)return;state.filePath=path;$('#filePath').value=path;try{const d=await api('/api/files?sessionId='+encodeURIComponent(state.sessionId)+'&path='+encodeURIComponent(path));$('#fileList').innerHTML=(d.items||[]).map(f=>`<div class="file-row" data-path="${esc(f.path)}" data-dir="${f.directory}"><span class="file-icon">${f.directory?'▸':'·'}</span><span class="file-name">${esc(f.name)}</span><span class="file-size">${f.directory?'':size(f.size)}</span></div>`).join('');$$('.file-row').forEach(x=>x.onclick=()=>x.dataset.dir==='true'?loadFiles(x.dataset.path):openFile(x.dataset.path))}catch(e){
    // The default workspace path does not exist until the sandbox has been
    // initialised at least once; fall back to the rootfs root instead of
    // showing a dead panel.
    if(path!=='/'&&/not a directory|no such file|not found/i.test(e.message||'')){return loadFiles('/')}
    $('#fileList').innerHTML='<div class="error">'+esc(e.message)+'</div>';
  }}
async function openFile(path){try{const d=await api('/api/file?sessionId='+encodeURIComponent(state.sessionId)+'&path='+encodeURIComponent(path));state.editorRevision=d.sha256||null;$('#editorPath').textContent=path;$('#fileContent').value=d.content||'';$('#editor').classList.remove('hidden')}catch(e){alert('打开文件失败：'+e.message)}}
async function saveFile(){const path=$('#editorPath').textContent;if(!path)return;try{const d=await api('/api/file',{method:'PUT',body:JSON.stringify({sessionId:state.sessionId,path,content:$('#fileContent').value,expectedSha256:state.editorRevision})});state.editorRevision=d.sha256||state.editorRevision;await loadFiles();$('#saveFile').textContent='已保存';setTimeout(()=>$('#saveFile').textContent='保存文件',800)}catch(e){alert('保存失败：'+e.message+'\n如果文件被 Agent 修改过，请重新打开后再编辑。')}}
async function runShell(){if(!state.sessionId)return alert('请先选择会话');const cmd=$('#shellCommand').value.trim();if(!cmd)return;$('#shellRun').disabled=true;$('#shellOutput').textContent+='\n$ '+cmd+'\n';try{const d=await api('/api/shell',{method:'POST',body:JSON.stringify({sessionId:state.sessionId,command:cmd})});$('#shellOutput').textContent+=d.output+`\n[exit ${d.exitCode}, ${d.durationMs} ms]`+(d.fullOutputPath?`\n[full: ${d.fullOutputPath}]`:'')+'\n';$('#shellOutput').scrollTop=$('#shellOutput').scrollHeight}catch(e){$('#shellOutput').textContent+='ERROR: '+e.message+'\n'}finally{$('#shellRun').disabled=false}}

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
        if(sessionTick++%4===0)await loadSessions();
        if(state.sessionId){await loadStatus();await loadMessages()}
      }catch{}
    }
    state.poll=setTimeout(tick,state.running?450:2500);
  };
  state.poll=setTimeout(tick,250);
}
function autoGrow(){const t=$('#prompt');t.style.height='auto';t.style.height=Math.min(t.scrollHeight,180)+'px'}

$('#connectBtn').onclick=login;$('#passwordInput').onkeydown=e=>{if(e.key==='Enter')login()};$('#logoutBtn').onclick=logout;
$('#sendBtn').onclick=send;$('#prompt').oninput=autoGrow;$('#prompt').onkeydown=e=>{if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();send()}};$('#cancelBtn').onclick=cancel;
$('#newChat').onclick=()=>{state.sessionId=null;renderSessions();$('#title').textContent='新会话';$('#model').textContent='';$('#messages').innerHTML='<div class="empty-state"><h2>新会话</h2><p>发送第一条消息后会自动创建。</p></div>';$('#prompt').focus()};
$('#filePath').onkeydown=e=>{if(e.key==='Enter')loadFiles($('#filePath').value)};$('#fileUp').onclick=()=>{const p=state.filePath.replace(/\/$/,'').split('/').slice(0,-1).join('/')||'/';loadFiles(p)};$('#closeEditor').onclick=()=>$('#editor').classList.add('hidden');$('#saveFile').onclick=saveFile;$('#shellRun').onclick=runShell;
$$('.tab').forEach(b=>b.onclick=async()=>{$$('.tab').forEach(x=>x.classList.toggle('active',x===b));$$('.tool-tab').forEach(x=>x.classList.remove('active'));$('#'+b.dataset.tab+'Tab').classList.add('active');if(b.dataset.tab==='settings')await loadSettings()});
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
boot();
