const VERSION="v1.5",IDLE_MS=8000;
const $=id=>document.getElementById(id);
let lists=[],products=[],recognition=null;
const api=()=>String(window.SPESA_CONFIG?.API_URL||"").replace(/\/$/,"");
function credentials(){return {email:$("email").value.trim(),password:$("password").value};}
async function request(path,body){const url=api();if(!url||url.includes("INSERISCI-QUI"))throw Error("Prima configura l'indirizzo del Worker.");const r=await fetch(url+path,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(body)});let data={};try{data=await r.json()}catch{}if(!r.ok)throw Error(data.error||"Richiesta non riuscita.");return data;}
$("bringLoginForm").addEventListener("submit",async event=>{event.preventDefault();const c=credentials();if(!c.email||!c.password){$("connection").textContent="Inserisci email e password.";return;}$("loadLists").disabled=true;$("connection").textContent="Connessione a Bring!…";try{const d=await request("/api/lists",c);lists=d.lists||[];$("listSelect").innerHTML="";for(const l of lists){const o=document.createElement("option");o.value=l.listUuid;o.textContent=l.name;$("listSelect").append(o)}$("listSelect").disabled=!lists.length;$("connection").textContent=lists.length?"Collegamento riuscito. Scegli la lista.":"Nessuna lista trovata."; }catch(e){$("connection").textContent=e.message;}finally{$("loadLists").disabled=false;}});
function splitProducts(s){return s.split(/[\n,;]+/i).map(x=>x.trim().replace(/^[-•\s]+|[.!?]+$/g,"")).filter(Boolean);}
function render(){const root=$("items");root.innerHTML="";if(!products.length)root.innerHTML='<p class="muted">I prodotti compariranno qui.</p>';products.forEach((p,i)=>{const row=document.createElement("div");row.className="item-row";const input=document.createElement("input");input.value=p;input.setAttribute("aria-label","Prodotto "+(i+1));input.oninput=()=>{products[i]=input.value;updateCount()};const del=document.createElement("button");del.className="remove";del.textContent="×";del.setAttribute("aria-label","Rimuovi prodotto");del.onclick=()=>{products.splice(i,1);render()};row.append(input,del);root.append(row)});updateCount();}
function updateCount(){$("count").textContent=products.length+" prodotti";$("send").disabled=!products.length||!$("listSelect").value;}
$("parse").onclick=()=>{const raw=$("rawText").value;products=splitProducts(raw);render();$("sendStatus").textContent="VERIFICA (non inviare): testo letto="+JSON.stringify(raw)+" | prodotti creati="+JSON.stringify(products);};
$("addItem").onclick=()=>{products.push("");render();const inputs=$("items").querySelectorAll("input");inputs[inputs.length-1]?.focus();};
$("listSelect").onchange=updateCount;
$("send").onclick=async()=>{const c=credentials(),listId=$("listSelect").value;const clean=products.map(x=>x.trim()).filter(Boolean);if(!c.email||!c.password||!listId||!clean.length)return;$("send").disabled=true;$("sendStatus").textContent="Invio a Bring!…";try{const d=await request("/api/add",{...c,listId,items:clean.map(name=>({name}))});$("sendStatus").textContent="Aggiunti "+(d.added??clean.length)+" prodotti alla lista!";products=[];$("rawText").value="";render();}catch(e){$("sendStatus").textContent=e.message;}finally{updateCount();}};
const Speech=window.SpeechRecognition||window.webkitSpeechRecognition;
if(Speech){
  let listening=false,committed="",pending="",idleTimer=null,silenceClose=false;
  const show=interim=>{$("rawText").value=[committed,interim].filter(Boolean).join("\n");};
  const commit=t=>{t=t.trim();if(t)committed+=(committed?"\n":"")+t;};
  const uiOff=()=>{$("record").classList.remove("listening");$("record").textContent="🎙️ Tocca per dettare";};
  const clearTimers=()=>{clearTimeout(idleTimer);};
  const armIdle=()=>{clearTimeout(idleTimer);idleTimer=setTimeout(()=>{if(listening){silenceClose=true;listening=false;try{recognition.stop();}catch(_){}}},IDLE_MS);};
  const startRec=()=>{
    recognition=new Speech();
    recognition.lang="it-IT";
    recognition.continuous=false;
    recognition.interimResults=true;
    recognition.maxAlternatives=1;
    recognition.onresult=e=>{
      let interim="";
      for(let i=e.resultIndex;i<e.results.length;i++){
        const r=e.results[i],t=r[0].transcript.trim();
        if(r.isFinal){commit(t);pending="";}else interim=t;
      }
      pending=interim;
      show(interim);
      armIdle();
    };
    recognition.onerror=e=>{
      if(e.error==="no-speech"||e.error==="aborted")return;
      listening=false;clearTimers();
      $("speechStatus").textContent="Dettatura non disponibile ("+e.error+"): usa il microfono della tastiera.";
    };
    recognition.onend=()=>{
      if(pending){commit(pending);pending="";show("");}
      if(listening){
        let tries=0;
        const go=()=>{if(!listening)return;try{startRec();}catch(_){if(++tries<5)setTimeout(go,100);else{listening=false;clearTimers();uiOff();}}};
        go();
      }
      else{
        clearTimers();uiOff();
        $("speechStatus").textContent=(silenceClose?"Microfono chiuso per silenzio. ":"Dettatura terminata. ")+"Premi “Prepara i prodotti”.";
        silenceClose=false;
      }
    };
    recognition.start();
  };
  $("record").onclick=()=>{
    if(listening){listening=false;clearTimers();try{recognition.stop();}catch(_){}return;}
    committed=$("rawText").value.trim();pending="";silenceClose=false;listening=true;
    $("record").classList.add("listening");$("record").textContent="■ Ferma dettatura";
    $("speechStatus").textContent="Ti ascolto… fai una breve pausa tra un prodotto e l'altro.";
    armIdle();
    try{startRec();}catch(_){listening=false;clearTimers();uiOff();$("speechStatus").textContent="Usa il microfono della tastiera.";}
  };
}else{$("record").onclick=()=>{$("speechStatus").textContent="Il browser non supporta la dettatura diretta. Tocca il campo e usa il microfono di Gboard.";$("rawText").focus();};}
if("serviceWorker"in navigator&&location.protocol.startsWith("http"))navigator.serviceWorker.register("./sw.js").catch(()=>{});
// versione in fondo alla pagina
(()=>{const f=document.querySelector("footer");if(f)f.textContent+=" · "+VERSION;})();
render();
