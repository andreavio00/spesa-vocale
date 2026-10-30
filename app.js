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
  recognition=new Speech();
  recognition.lang="it-IT";
  recognition.continuous=true;
  recognition.interimResults=true;
  let dictationActive=false;
  let prefixText="";
  let sessionFinalText="";
  recognition.onresult=e=>{
    const finalParts=[],interimParts=[];
    for(let i=0;i<e.results.length;i++){
      const t=e.results[i][0].transcript.trim();
      if(!t)continue;
      if(e.results[i].isFinal)finalParts.push(t);
      else interimParts.push(t);
    }
    sessionFinalText=finalParts.join(" ");
    const visible=[prefixText.trim(),sessionFinalText,interimParts.join(" ")].filter(Boolean).join(" ");
    $("rawText").value=visible;
  };
  recognition.onerror=e=>{
    if(e.error==="not-allowed"||e.error==="service-not-allowed"){
      dictationActive=false;
      $("speechStatus").textContent="Microfono bloccato: autorizza il microfono per questo sito nelle impostazioni di Chrome.";
    }else if(e.error!=="no-speech"&&e.error!=="aborted"){
      $("speechStatus").textContent="Dettatura non disponibile ("+e.error+"). Puoi usare il microfono di Gboard nel campo di testo.";
    }
    if(!dictationActive){
      $("record").classList.remove("listening");
      $("record").textContent="🎙️ Tocca per dettare";
    }
  };
  recognition.onend=()=>{
    if(dictationActive){
      prefixText=[$("rawText").value.trim()].filter(Boolean).join(" ")+" ";
      sessionFinalText="";
      try{
        setTimeout(()=>{if(dictationActive){try{recognition.start();}catch(e){}}},300);
      }catch(e){}
      return;
    }
    $("record").classList.remove("listening");
    $("record").textContent="🎙️ Tocca per dettare";
    if($("speechStatus").textContent==="Ti ascolto…")$("speechStatus").textContent="Dettatura terminata. Premi “Prepara i prodotti”.";
  };
  $("record").onclick=()=>{
    if(dictationActive){
      dictationActive=false;
      recognition.stop();
      return;
    }
    try{
      prefixText=$("rawText").value?$("rawText").value.trim()+" ":"";
      sessionFinalText="";
      $("speechStatus").textContent="Ti ascolto…";
      $("record").classList.add("listening");
      $("record").textContent="■ Ferma dettatura";
      dictationActive=true;
      recognition.start();
    }catch(e){
      dictationActive=false;
      $("record").classList.remove("listening");
      $("record").textContent="🎙️ Tocca per dettare";
      $("speechStatus").textContent="Non riesco ad avviare il microfono. Controlla i permessi di Chrome oppure usa Gboard.";
    }
  };
}else{
  $("record").onclick=()=>{
    $("speechStatus").textContent="La dettatura diretta non è supportata qui. Tocca il campo e usa il microfono di Gboard.";
    $("rawText").focus();
  };
}
if("serviceWorker"in navigator&&location.protocol.startsWith("http"))navigator.serviceWorker.register("./sw.js").catch(()=>{});
render();
