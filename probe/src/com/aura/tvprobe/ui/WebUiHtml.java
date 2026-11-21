package com.aura.tvprobe;

/**
 * Mobile-first 4-Tab Aura TV Phone Companion & Remote served locally on :8765.
 * Tabs: Co-Pilot (AI Second Screen) · Remote (D-Pad + Keyboard) · Discover (Apps & Shows) · Setup (RAM & Config).
 */
public final class WebUiHtml {
    public static final String PAGE = "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>" +
        "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no'>" +
        "<title>Aura TV Companion</title><style>" +
        ":root{--bg:#060810;--surf:#0f1422;--card:#151c2f;--brd:rgba(255,255,255,.1);--acc:#38bdf8;--ind:#6366f1;--grn:#34d399;--rose:#fb7185;--txt:#f8fafc;--sub:#94a3b8}" +
        "*{box-sizing:border-box;margin:0;padding:0;-webkit-tap-highlight-color:transparent}" +
        "body{background:var(--bg);color:var(--txt);font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;max-width:680px;margin:0 auto;padding:14px 14px 86px}" +
        "header{display:flex;justify-content:space-between;align-items:center;margin-bottom:14px}" +
        ".brand{font-weight:800;font-size:18px;letter-spacing:-.4px}.brand span{color:var(--acc)}" +
        ".pill{background:var(--surf);color:var(--txt);border:1px solid var(--brd);border-radius:99px;padding:7px 13px;font-size:12px;font-weight:600;cursor:pointer}" +
        ".pill.acc{background:linear-gradient(135deg,var(--ind),var(--acc));border:none;color:#031018;font-weight:700}" +
        ".pill.grn{background:rgba(52,211,153,.15);border-color:rgba(52,211,153,.4);color:var(--grn)}" +
        ".pill.rose{background:rgba(251,113,133,.15);border-color:rgba(251,113,133,.4);color:var(--rose)}" +
        ".card{background:var(--surf);border:1px solid var(--brd);border-radius:18px;padding:14px;margin-bottom:12px}" +
        ".lbl{font-size:10px;font-weight:700;letter-spacing:1.4px;text-transform:uppercase;color:var(--acc);margin-bottom:8px}" +
        ".row{display:flex;gap:8px;flex-wrap:wrap;align-items:center}" +
        "input,select{background:#090d18;color:var(--txt);border:1px solid var(--brd);border-radius:12px;padding:10px 13px;font-size:14px;width:100%}" +
        ".toast{display:none;align-items:center;gap:8px;padding:10px 14px;border-radius:12px;font-size:12px;font-weight:600;margin-bottom:10px}" +
        ".toast.load{display:flex;background:#131b33;border:1px solid var(--acc);color:#e0f2fe}" +
        ".toast.ok{display:flex;background:#0d2920;border:1px solid var(--grn);color:#a7f3d0}" +
        ".spin{width:14px;height:14px;border:2px solid rgba(255,255,255,.25);border-top-color:#fff;border-radius:50%;animation:sp .7s linear infinite}" +
        "@keyframes sp{to{transform:rotate(360deg)}}" +
        ".dpad{display:grid;grid-template-columns:repeat(3,74px);grid-template-rows:repeat(3,64px);gap:10px;justify-content:center;margin:14px 0}" +
        ".kbtn{background:var(--card);color:var(--txt);border:1px solid var(--brd);border-radius:16px;font-size:18px;font-weight:700;cursor:pointer;display:flex;align-items:center;justify-content:center}" +
        ".kbtn:active{transform:scale(.94);background:var(--acc);color:#041018}" +
        ".kbtn.ok{background:linear-gradient(135deg,var(--ind),var(--acc));color:#041018;border:none;font-size:15px}" +
        ".apps{display:flex;gap:8px;overflow-x:auto;padding-bottom:6px;scrollbar-width:none}" +
        ".appbtn{flex:0 0 auto;background:var(--card);border:1px solid var(--brd);border-radius:12px;padding:9px 14px;color:var(--txt);font-size:12px;font-weight:700;cursor:pointer}" +
        ".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(148px,1fr));gap:10px}" +
        ".tile{background:var(--card);border:1px solid var(--brd);border-radius:14px;overflow:hidden;display:flex;flex-direction:column}" +
        ".tile img{width:100%;aspect-ratio:2/3;object-fit:cover;background:#0d1220}" +
        ".tb{padding:9px;display:flex;flex-direction:column;gap:6px;flex:1;justify-content:space-between}" +
        ".bxs{display:flex;gap:4px}.bxs button{flex:1;padding:6px 4px;border-radius:8px;border:1px solid var(--brd);background:var(--surf);color:var(--txt);font-size:11px;font-weight:700;cursor:pointer}" +
        ".bxs button.play{background:var(--acc);color:#041018;border:none}" +
        ".tabs{position:fixed;bottom:0;left:0;right:0;background:rgba(10,14,24,.96);backdrop-filter:blur(12px);border-top:1px solid var(--brd);display:flex;justify-content:space-around;padding:8px 6px 12px;z-index:20}" +
        ".tab{background:none;border:none;color:var(--sub);font-size:11px;font-weight:700;display:flex;flex-direction:column;align-items:center;gap:3px;cursor:pointer;padding:4px 12px}" +
        ".tab.on{color:var(--acc)}.tab i{font-style:normal;font-size:18px}" +
        ".sec{display:none}.sec.on{display:block}" +
        ".statgrid{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:10px}" +
        ".statbox{background:var(--card);border:1px solid var(--brd);border-radius:12px;padding:12px;text-align:center}" +
        ".statval{font-size:22px;font-weight:800;color:var(--acc);margin-top:4px}" +
        "</style></head><body>" +
        "<header><div><div class='brand'>AURA <span>TV</span></div><div id='sub' style='color:var(--sub);font-size:11px'>Connecting…</div></div>" +
        "<div class='row'><button class='pill' id='pbtn' onclick='cycleProf()'>Profile</button>" +
        "<button class='pill' onclick='loadAll(true)'>↻</button></div></header>" +
        "<div id='toast' class='toast'></div>" +

        /* TAB 1: CO-PILOT */
        "<div id='t-copilot' class='sec on'>" +
        "<div class='card'><div class='lbl'>NOW PLAYING ON TV</div>" +
        "<div id='np-title' style='font-size:16px;font-weight:700;margin-bottom:4px'>Nothing playing</div>" +
        "<div id='np-meta' style='font-size:12px;color:var(--sub);margin-bottom:10px'>Open any show on TV to unlock live co-pilot</div>" +
        "<div class='row'>" +
        "<button class='pill acc' onclick='ask(\"Catch me up without spoilers\")'>⚡ Catch Me Up</button>" +
        "<button class='pill' onclick='ask(\"Who is this actor on screen?\")'>🎭 Who’s That?</button>" +
        "<button class='pill' onclick='ask(\"Is this episode heavy or light? Spoiler-free vibe check\")'>✨ Episode Vibe</button>" +
        "</div></div>" +
        "<div class='card'><div class='lbl'>ASK AURA AI</div>" +
        "<div class='row' style='margin-bottom:10px'>" +
        "<input id='q' style='flex:1' placeholder='Ask for a mood, show, or say Play it…' onkeydown='if(event.key===\"Enter\")ask()'>" +
        "<button class='pill acc' onclick='ask()'>Ask</button>" +
        "<button class='pill rose' id='mic' onclick='startMic()'>🎙</button></div>" +
        "<div class='row' style='margin-bottom:10px'>" +
        "<button class='pill grn' onclick='ask(\"Movie night blend for me and partner tonight\")'>🍿 Movie Night Blend</button>" +
        "<button class='pill' onclick='ask(\"Surprise me and play something great tonight\")'>🎲 Surprise Me</button>" +
        "<button class='pill' onclick='ask(\"Morph my home screen to light and smart mood\")'>☁️ Cozy Mood</button>" +
        "<button class='pill' onclick='ask(\"Morph my home screen to under 2 hours\")'>⏱ Under 2h</button></div>" +
        "<div id='reply' style='font-size:13px;line-height:1.45;color:#e2e8f0'></div>" +
        "<div id='chips' class='row' style='margin-top:8px'></div></div>" +
        "<div id='copilot-picks' class='grid'></div></div>" +

        /* TAB 2: REMOTE */
        "<div id='t-remote' class='sec'>" +
        "<div class='card'><div class='lbl'>TYPE ON TV (PHONE KEYBOARD)</div>" +
        "<div class='row'><input id='tvtxt' style='flex:1' placeholder='Type or paste anything to search on TV…' onkeydown='if(event.key===\"Enter\")sendTvText()'>" +
        "<button class='pill acc' onclick='sendTvText()'>Send</button></div></div>" +
        "<div class='card'><div class='lbl'>TOUCH REMOTE</div>" +
        "<div class='dpad'>" +
        "<div></div><button class='kbtn' onclick='rk(\"UP\")'>▲</button><div></div>" +
        "<button class='kbtn' onclick='rk(\"LEFT\")'>◀</button><button class='kbtn ok' onclick='rk(\"OK\")'>OK</button><button class='kbtn' onclick='rk(\"RIGHT\")'>▶</button>" +
        "<div></div><button class='kbtn' onclick='rk(\"DOWN\")'>▼</button><div></div></div>" +
        "<div class='row' style='justify-content:center;margin-bottom:12px'>" +
        "<button class='pill' onclick='rk(\"BACK\")'>↩ Back</button>" +
        "<button class='pill acc' onclick='rk(\"HOME\")'>⌂ Home</button>" +
        "<button class='pill' onclick='rk(\"PLAY_PAUSE\")'>⏯ Play/Pause</button></div>" +
        "<div class='row' style='justify-content:center;margin-bottom:12px'>" +
        "<button class='pill' onclick='rk(\"VOL_DOWN\")'>🔉 Vol −</button>" +
        "<button class='pill' onclick='rk(\"MUTE\")'>🔇 Mute</button>" +
        "<button class='pill' onclick='rk(\"VOL_UP\")'>🔊 Vol +</button></div>" +
        "<div class='row' style='justify-content:center'>" +
        "<button class='pill' onclick='hdmi(1)'>📺 HDMI 1</button>" +
        "<button class='pill' onclick='hdmi(2)'>📺 HDMI 2</button></div></div></div>" +

        /* TAB 3: DISCOVER */
        "<div id='t-discover' class='sec'>" +
        "<div class='card'><div class='lbl'>LAUNCH ON TV</div><div id='apps' class='apps'>Loading apps…</div></div>" +
        "<div class='row' style='margin-bottom:10px' id='ftabs'></div>" +
        "<div id='grid' class='grid'></div></div>" +

        /* TAB 4: SETUP */
        "<div id='t-setup' class='sec'>" +
        "<div class='card'><div class='lbl'>TV HEALTH GUARDIAN</div>" +
        "<div class='statgrid'>" +
        "<div class='statbox'><div style='font-size:11px;color:var(--sub)'>FREE RAM</div><div id='s-ram' class='statval'>--</div></div>" +
        "<div class='statbox'><div style='font-size:11px;color:var(--sub)'>TV CPU</div><div id='s-cpu' class='statval'>--</div></div></div>" +
        "<button class='pill acc' style='width:100%;padding:11px' onclick='boostRam()'>⚡ Boost TV RAM Now</button></div>" +
        "<div class='card'><div class='lbl'>AI PROVIDER & REGION (SWITCH AT RUNTIME)</div>" +
        "<div style='display:flex;flex-direction:column;gap:9px'>" +
        "<select id='c-prov'>" +
        "<option value='gemini'>Gemini Cloud (Recommended · Fastest)</option>" +
        "<option value='auto'>Auto (Local LLM First → Gemini Fallback)</option>" +
        "<option value='local'>Local LLM Only (100% Offline · Ollama / LM Studio)</option></select>" +
        "<input id='c-gem' type='password' placeholder='Gemini API Key (leave blank to keep current)'>" +
        "<input id='c-lurl' placeholder='Local LLM URL (e.g. http://192.168.1.20:11434/v1)'>" +
        "<input id='c-lmod' placeholder='Local LLM Model (e.g. qwen2.5:3b or llama3.2:3b)'>" +
        "<input id='c-reg' placeholder='2-letter Region Code (e.g. US, GB, IN, JP)'>" +
        "<div class='row'>" +
        "<button class='pill acc' style='flex:1;padding:10px' onclick='saveCfg(false)'>Save Settings</button>" +
        "<button class='pill rose' onclick='saveCfg(true)'>Reset to Gemini</button></div></div></div></div>" +

        /* BOTTOM NAV */
        "<nav class='tabs'>" +
        "<button class='tab on' id='nb-copilot' onclick='go(\"copilot\")'><i>✨</i>Co-Pilot</button>" +
        "<button class='tab' id='nb-remote' onclick='go(\"remote\")'><i>🎛</i>Remote</button>" +
        "<button class='tab' id='nb-discover' onclick='go(\"discover\")'><i>🎬</i>Discover</button>" +
        "<button class='tab' id='nb-setup' onclick='go(\"setup\")'><i>⚙</i>Setup</button></nav>" +

        "<script>" +
        "const PROFS=['member1','partner','member3','kids','family'];" +
        "let st=null,sys=null,apps=[],curTab='copilot',dTab='foryou';" +
        "function toast(t,m){const e=document.getElementById('toast');e.className='toast '+t;e.innerHTML=(t==='load'?'<div class=\"spin\"></div>':'')+'<span>'+m+'</span>';if(t==='ok')setTimeout(()=>{if(e.className==='toast ok')e.className='toast'},3200);}" +
        "function go(tab){curTab=tab;['copilot','remote','discover','setup'].forEach(k=>{document.getElementById('t-'+k).className='sec'+(k===tab?' on':'');document.getElementById('nb-'+k).className='tab'+(k===tab?' on':'');});if(tab==='setup')loadSys();if(tab==='discover'&&!apps.length)loadApps();}" +
        "async function loadAll(force=false){if(force)toast('load','Refreshing TV picks…');const r=await fetch(force?'/api/state?refresh=1':'/api/state');st=await r.json();render();if(force)toast('ok','Picks refreshed!');}" +
        "async function loadSys(){const r=await fetch('/api/system');sys=await r.json();document.getElementById('s-ram').textContent=(sys.avail_ram_mb||'--')+' MB';document.getElementById('s-cpu').textContent=(sys.cpu_load_pct||'--')+'%';document.getElementById('c-prov').value=sys.llm_provider||'auto';document.getElementById('c-lurl').value=sys.local_llm_url||'';document.getElementById('c-lmod').value=sys.local_llm_model||'';document.getElementById('c-reg').value=sys.region_country||'';}" +
        "async function loadApps(){const r=await fetch('/api/apps');const d=await r.json();apps=d.apps||[];document.getElementById('apps').innerHTML=apps.map(a=>`<button class='appbtn' onclick='fetch(\"/launch?pkg=${encodeURIComponent(a.pkg||\"\")}\");toast(\"ok\",\"Launching ${a.name}…\")'>${a.name}</button>`).join('')||'No apps found';}" +
        "function render(){if(!st)return;const p=st.active_profile||'member1',pn=(st.profile_labels&&st.profile_labels[p])||p;" +
        "document.getElementById('pbtn').textContent='👤 '+pn;" +
        "document.getElementById('sub').textContent=`${(st.recommendations||[]).length} picks · ${st.history_count||0} watched`;" +
        "const np=st.now_playing||{};document.getElementById('np-title').textContent=np.title?(np.series_title?np.series_title+' · '+np.title:np.title):'Aura Home Ready';" +
        "document.getElementById('np-meta').textContent=np.title?`${np.app_name||'TV'} · ${Math.round((np.position_sec||0)/60)}m watched`:'Play any show on TV for live Catch Me Up & Cast X-Ray';" +
        "const vs=st.voice_session||{};document.getElementById('reply').textContent=vs.reply||'';document.getElementById('chips').innerHTML=(vs.follow_ups||[]).map(f=>`<button class='pill' style='font-size:11px' onclick='ask(${JSON.stringify(f)})'>${f}</button>`).join('');" +
        "document.getElementById('copilot-picks').innerHTML=(vs.results||[]).slice(0,8).map(tile).join('');" +
        "document.getElementById('ftabs').innerHTML=[['foryou','For You'],['voice','AI Picks'],['watched','Watched']].map(x=>`<button class='pill ${dTab===x[0]?\"acc\":\"\"}' onclick='dTab=\"${x[0]}\";render()'>${x[1]}</button>`).join('');" +
        "const list=dTab==='voice'?(vs.results||[]):(dTab==='watched'?(st.watched_items||st.history||[]):(st.recommendations||[]));" +
        "document.getElementById('grid').innerHTML=list.slice(0,24).map(tile).join('');}" +
        "function tile(x){const t=x.title||'Title',u=encodeURIComponent(x.launch_uri||''),pk=encodeURIComponent(x.launch_pkg||x.app_pkg||'');" +
        "return `<div class='tile'><img src='${x.poster_url||\"\"}' loading='lazy' onerror='this.style.opacity=0.12'><div class='tb'><div>" +
        "<div style='font-size:10px;color:var(--acc);font-weight:700'>${x.app||x.app_name||'TV'} · ${x.runtime_min||45}m</div>" +
        "<div style='font-size:13px;font-weight:700;margin:3px 0'>${t}</div>" +
        "<div style='font-size:11px;color:var(--sub)'>${(x.reason||x.synopsis||'').slice(0,68)}</div></div>" +
        "<div class='bxs'><button class='play' onclick='fetch(\"/launch?uri=${u}&pkg=${pk}\");toast(\"ok\",\"Playing on TV…\")'>▶ Play</button>" +
        "<button onclick='rate(${JSON.stringify(t)},9)'>👍</button><button onclick='rate(${JSON.stringify(t)},3)'>👎</button></div></div></div>`;}" +
        "async function ask(msg){const q=msg||document.getElementById('q').value.trim();if(!q)return;document.getElementById('q').value='';toast('load','Aura is thinking…');" +
        "await fetch('/api/chat',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({message:q,reset:false})});await loadAll(false);toast('ok','Done!');}" +
        "async function rk(k){await fetch('/api/remote?key='+k);}" +
        "async function hdmi(p){await fetch('/api/remote?hdmi='+p);toast('ok','Switched to HDMI '+p);}" +
        "async function sendTvText(){const t=document.getElementById('tvtxt').value.trim();if(!t)return;document.getElementById('tvtxt').value='';await fetch('/api/remote?text='+encodeURIComponent(t));toast('ok','Sent to TV!');}" +
        "async function rate(t,r){await fetch('/api/rate?title='+encodeURIComponent(t)+'&rating='+r);toast('ok',r>=8?'Loved '+t:'Skipped '+t);loadAll(false);}" +
        "async function boostRam(){toast('load','Boosting TV RAM…');const r=await fetch('/api/system/boost');const d=await r.json();await loadSys();toast('ok','Freed +'+(d.freed_mb||0)+' MB RAM!');}" +
        "async function saveCfg(clearLocal){const qs=[];const pr=clearLocal?'gemini':document.getElementById('c-prov').value,g=document.getElementById('c-gem').value.trim(),u=document.getElementById('c-lurl').value.trim(),m=document.getElementById('c-lmod').value.trim(),rg=document.getElementById('c-reg').value.trim();" +
        "qs.push('llm_provider='+encodeURIComponent(pr));if(g)qs.push('gemini_api_key='+encodeURIComponent(g));if(clearLocal)qs.push('clear_local_llm=1');else if(u)qs.push('local_llm_url='+encodeURIComponent(u));if(m)qs.push('local_llm_model='+encodeURIComponent(m));if(rg)qs.push('region_country='+encodeURIComponent(rg));" +
        "await fetch('/api/config?'+qs.join('&'));document.getElementById('c-gem').value='';await loadSys();toast('ok','Settings saved to TV!');}" +
        "async function cycleProf(){const p=(st&&st.active_profile)||'member1',n=PROFS[(PROFS.indexOf(p)+1)%PROFS.length];await fetch('/api/set-profile?profile='+n);await loadAll(false);toast('ok','Switched profile');}" +
        "function startMic(){const SR=window.SpeechRecognition||window.webkitSpeechRecognition;if(!SR){toast('ok','Use keyboard mic');return;}const r=new SR();r.lang=navigator.language||'en-US';toast('load','Listening…');r.onresult=e=>{ask(e.results[0][0].transcript);};r.start();}" +
        "loadAll(false);</script></body></html>";
}
