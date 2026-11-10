package com.aura.tvprobe;

public class WebUiHtml {
    public static final String PAGE = "<!DOCTYPE html><html><head><meta charset='UTF-8'>" +
        "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
        "<title>Aura AI · Living Room Concierge</title><style>" +
        ":root{--bg:#08080e;--surf:#141420;--card:#1c1c2e;--brd:#2a2a44;--acc:#7c6ef7;--pink:#e05c8a;--grn:#38c976;--txt:#f3f3f8;--sub:#9898b2}" +
        "*{box-sizing:border-box;margin:0;padding:0}" +
        "body{background:var(--bg);color:var(--txt);font-family:system-ui,-apple-system,sans-serif;max-width:1200px;margin:0 auto;padding:20px}" +
        "header{display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:12px;margin-bottom:16px}" +
        ".bar{display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin-bottom:14px}" +
        ".chip{background:var(--surf);color:var(--txt);border:1px solid var(--brd);border-radius:99px;padding:8px 15px;font-size:13px;font-weight:600;cursor:pointer}" +
        ".chip.act{background:var(--pink);border-color:#ff85b3;color:#fff}" +
        ".chip.acc{background:var(--acc);border-color:#9b8ff9;color:#fff}" +
        ".banner{display:none;align-items:center;gap:10px;padding:12px 16px;border-radius:14px;font-size:13px;font-weight:600;margin-bottom:14px}" +
        ".banner.load{display:flex;background:#1e1938;border:1px solid var(--acc);color:#e6e2ff}" +
        ".banner.ok{display:flex;background:#112e22;border:1px solid var(--grn);color:#8ff7ba}" +
        ".spin{width:18px;height:18px;border:3px solid rgba(255,255,255,.25);border-top-color:#fff;border-radius:50%;animation:sp .8s linear infinite}" +
        "@keyframes sp{to{transform:rotate(360deg)}}" +
        ".voice-panel{background:linear-gradient(135deg,#18162c,#11111d);border:1px solid var(--brd);border-radius:18px;padding:16px;margin-bottom:18px}" +
        ".voice-row{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:10px}" +
        ".voice-row input{flex:1;min-width:220px;background:#0d0d16;color:#fff;border:1px solid var(--brd);border-radius:99px;padding:10px 16px;font-size:14px}" +
        ".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(190px,1fr));gap:18px;transition:opacity .2s}" +
        ".tile{background:var(--surf);border:1px solid var(--brd);border-radius:16px;overflow:hidden;display:flex;flex-direction:column;justify-content:space-between;transition:transform .15s}" +
        ".tile:hover{transform:translateY(-3px);border-color:var(--acc)}" +
        ".tile img{width:100%;aspect-ratio:2/3;object-fit:cover;background:#1c1930}" +
        ".tile-body{padding:12px;flex:1;display:flex;flex-direction:column;justify-content:space-between}" +
        ".badge{display:inline-block;background:rgba(124,110,247,.18);color:#b4aaff;font-size:10px;font-weight:700;padding:3px 8px;border-radius:99px}" +
        ".btns{display:flex;gap:6px;margin-top:10px}" +
        ".btns button{flex:1;background:var(--acc);color:#fff;border:none;border-radius:8px;padding:7px;font-size:11px;font-weight:600;cursor:pointer}" +
        ".btns button.sec{background:var(--card);border:1px solid var(--brd)}" +
        ".btns button.wat{background:rgba(56,201,118,.2);border:1px solid var(--grn);color:#7ef5b0}" +
        "</style></head><body>" +
        "<header><div><h1 style='font-size:21px'>✨ Aura AI · Living Room Concierge</h1>" +
        "<div style='color:var(--sub);font-size:12px' id='stat'>Ready</div></div>" +
        "<div style='display:flex;gap:8px;flex-wrap:wrap'>" +
        "<button class='chip' id='prof-btn' onclick='cycleProf()'>👤 Profile</button>" +
        "<button class='chip' id='srv-btn' onclick='cycleSrv()'>📡 Server</button>" +
        "<button class='chip acc' onclick='manualRefresh()'>🔄 Refresh</button></div></header>" +
        "<div id='banner' class='banner'></div>" +
        "<div class='voice-panel'>" +
        "<div class='voice-row'>" +
        "<button class='chip act' id='mic-btn' onclick='startMic()'>🎙️ Speak</button>" +
        "<input id='chat-in' placeholder=\"Ask or refine: e.g. 'I want to watch a sci-fi movie or horror show'\" onkeydown='if(event.key===\"Enter\")sendChat()'>" +
        "<button class='chip acc' onclick='sendChat()'>Ask Aura</button>" +
        "<button class='chip' onclick='sendChat(\"\",true)'>Reset</button></div>" +
        "<div id='chat-reply' style='font-size:13px;color:#dcdcf0;margin-bottom:8px'></div>" +
        "<div class='bar' id='follow-bar' style='margin-bottom:0'></div></div>" +
        "<div class='bar' id='tab-bar'></div>" +
        "<div class='grid' id='grid'></div>" +
        "<script>" +
        "const PROFS=['member1','partner','member3','kids','family'];" +
        "const TABS=[['foryou','✨ For You'],['voice','🎙️ Voice Picks'],['short','⏱️ Quick <30m'],['scifi','🚀 Sci-Fi & Horror'],['drama','🎭 Drama & Action'],['watched','✓ Watched']];" +
        "let st=null,curTab='foryou';" +
        "function showBanner(type,msg){const b=document.getElementById('banner');b.className='banner '+type;b.innerHTML=(type==='load'?'<div class=\"spin\"></div>':'')+'<span>'+msg+'</span>';document.getElementById('grid').style.opacity=type==='load'?'0.35':'1';}" +
        "async function load(force=false){const r=await fetch(force?'/api/state?refresh=1':'/api/state');st=await r.json();render();}" +
        "function render(){if(!st)return;" +
        " const p=st.active_profile||'member1',srv=st.server_mode||'auto',age=Math.round((st.cache_age_sec||0)/60);" +
        " const pName=(st.profile_labels&&st.profile_labels[p])||p.toUpperCase();" +
        " document.getElementById('stat').textContent=`1-Hr Smart Cache (${age}m old) • ${st.history_count||0} items synced`;" +
        " document.getElementById('prof-btn').textContent='👤 '+pName;" +
        " document.getElementById('srv-btn').textContent='📡 '+srv.toUpperCase();" +
        " document.getElementById('tab-bar').innerHTML=TABS.map(t=>`<button class='chip ${t[0]===curTab?\"acc\":\"\"}' onclick='curTab=\"${t[0]}\";render()'>${t[1]}</button>`).join('');" +
        " const vs=st.voice_session||{};" +
        " document.getElementById('chat-reply').textContent='🗣️ Aura: '+(vs.reply||'Say what mood, genre, or runtime you want to watch.');" +
        " document.getElementById('follow-bar').innerHTML=(vs.follow_ups||[]).map(f=>`<button class='chip' style='font-size:11px;padding:5px 11px' onclick='sendChat(${JSON.stringify(f)},false)'>💬 ${f}</button>`).join('');" +
        " const items=pickItems();" +
        " document.getElementById('grid').innerHTML=items.map(tileHtml).join('');" +
        "}" +
        "function pickItems(){" +
        " const recos=st.recommendations||[],hist=st.history||[],vs=st.voice_session||{};" +
        " if(curTab==='voice')return (vs.results&&vs.results.length)?vs.results:recos;" +
        " if(curTab==='watched')return (st.watched_items&&st.watched_items.length)?st.watched_items:hist.slice(0,24);" +
        " if(curTab==='short')return recos.concat(hist).filter(x=>(x.runtime_min||45)<=30).slice(0,18);" +
        " if(curTab==='scifi')return recos.concat(hist).filter(x=>/sci-fi|science|thriller|mystery|horror|fantasy/i.test((x.genres||'')+(x.reason||''))).slice(0,18);" +
        " if(curTab==='drama')return recos.concat(hist).filter(x=>/drama|romance|action|crime|adventure|family/i.test((x.genres||'')+(x.reason||''))).slice(0,18);" +
        " return recos;" +
        "}" +
        "function tileHtml(x){" +
        " const t=(x.series_title?x.series_title+' · ':'')+(x.title||'');" +
        " const u=encodeURIComponent(x.launch_uri||''),pk=encodeURIComponent(x.launch_pkg||x.app_pkg||'');" +
        " const isW=!!x.watched;" +
        " return `<div class='tile'><img src='${x.poster_url||\"\"}' loading='lazy' onerror='this.style.opacity=0.15'><div class='tile-body'><div>" +
        "  <span class='badge'>${x.app||x.app_name||'TV'} • ${x.runtime_min||45}m</span>" +
        "  <div style='font-weight:700;font-size:14px;margin:6px 0 3px'>${t}</div>" +
        "  <div style='color:#bdbdd2;font-size:11px'>${(x.reason||x.synopsis||'').slice(0,75)}</div></div>" +
        "  <div class='btns'><button onclick='fetch(\"/launch?uri=${u}&pkg=${pk}\")'>▶ Play</button>" +
        "  <button class='${isW?\"wat\":\"sec\"}' onclick='markWatched(${JSON.stringify(x.title||\"\")},${!isW})'>${isW?\"✓ Watched\":\"✓ Mark Watched\"}</button></div></div></div>`;" +
        "}" +
        "async function sendChat(msg,reset=false){" +
        " const q=msg!==undefined?msg:document.getElementById('chat-in').value.trim();" +
        " if(!reset&&!q)return;" +
        " showBanner('load',reset?'Resetting conversation…':`Asking Aura AI: \"${q}\" (fetching picks & HD posters)…`);" +
        " document.getElementById('chat-in').value=reset?'':q;" +
        " const r=await fetch('/api/chat',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({message:q,reset:reset})});" +
        " const res=await r.json();" +
        " curTab='voice';await load(false);" +
        " showBanner('ok',reset?'✅ Conversation reset!':`✅ ${(res.results||[]).length} New Picks Ready! ${res.reply||''}`);" +
        "}" +
        "async function manualRefresh(){showBanner('load','Refreshing AI recommendations & posters…');await load(true);showBanner('ok','✅ Recommendations updated!');}" +
        "async function markWatched(title,w){" +
        " await fetch('/api/watched?title='+encodeURIComponent(title)+'&watched='+(w?1:0));" +
        " await load(false);showBanner('ok',w?`✅ Marked '${title}' as Watched`:`✅ Unmarked '${title}'`);" +
        "}" +
        "function startMic(){" +
        " const SR=window.SpeechRecognition||window.webkitSpeechRecognition;" +
        " if(!SR){alert('Use the text box or your keyboard mic.');return;}" +
        " const rec=new SR();rec.lang='en-IN';" +
        " document.getElementById('mic-btn').textContent='🔴 Listening…';" +
        " showBanner('load','🔴 Listening to your voice… Speak now!');" +
        " rec.onresult=e=>{const txt=e.results[0][0].transcript;document.getElementById('chat-in').value=txt;sendChat(txt,false);};" +
        " rec.onend=()=>{document.getElementById('mic-btn').textContent='🎙️ Speak';};" +
        " rec.start();" +
        "}" +
        "async function cycleProf(){const p=st.active_profile||'member1';const next=PROFS[(PROFS.indexOf(p)+1)%PROFS.length];await fetch('/api/set-profile?profile='+next);await load(false);showBanner('ok','✅ Switched profile to '+next.toUpperCase());}" +
        "async function cycleSrv(){const s=st.server_mode||'auto';const next=s==='auto'?'tv':(s==='tv'?'phone':'auto');await fetch('/api/set-server?mode='+next);await load(false);}" +
        "load(false);" +
        "</script></body></html>";
}
