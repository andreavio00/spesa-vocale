const $=id=>document.getElementById(id);
let lists=[],products=[],recognition=null;
const api=()=>String(window.SPESA_CONFIG?.API_URL||"").replace(/\/$/,"");
function credentials(){return {email:$("email").value.trim(),password:$("password").value};}
async function request(path,body){const url=api();if(!url||url.includes("INSERISCI-QUI"))throw Error("Prima configura l'indirizzo del Worker.");const r=await fetch(url+path,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(body)});let data={};try{data=await r.json()}catch{}if(!r.ok)throw Error(data.error||"Richiesta non riuscita.");return data;}
$("bringLoginForm").addEventListener("submit",async event=>{event.preventDefault();const c=credentials();if(!c.email||!c.password){$("connection").textContent="Inserisci email e password.";return;}$("loadLists").disabled=true;$("connection").textContent="Connessione a Bring!…";try{const d=await request("/api/lists",c);lists=d.lists||[];$("listSelect").innerHTML="";for(const l of lists){const o=document.createElement("option");o.value=l.listUuid;o.textContent=l.name;$("listSelect").append(o)}$("listSelect").disabled=!lists.length;$("connection").textContent=lists.length?"Collegamento riuscito. Scegli la lista.":"Nessuna lista trovata."; }catch(e){$("connection").textContent=e.message;}finally{$("loadLists").disabled=false;}});
function splitProducts(s){return s.split(/[\n,;]+/i).map(x=>x.trim().replace(/^[-•\s]+|[.!?]+$/g,"")).filter(Boolean);}
function render(){const root=$("items");root.innerHTML="";if(!products.length)root.innerHTML='<p class="muted">I prodotti compariranno qui.</p>';products.forEach((p,i)=>{const row=document.createElement("div");row.className="item-row";const input=document.createElement("input");input.value=p;input.setAttribute("aria-label","Prodotto "+(i+1));input.oninput=()=>{products[i]=input.value;updateCount()};const del=document.createElement("button");del.className="remove";del.textContent="×";del.setAttribute("aria-label","Rimuovi prodotto");del.onclick=()=>{products.splice(i,1);render()};row.append(input,del);root.append(row)});updateCount();}
function updateCount(){$("count").textContent=products.length+" prodotti";$("send").disabled=!products.length||!$("listSelect").value;}
function openBringApp(){const fallback=encodeURIComponent("https://play.google.com/store/apps/details?id=ch.publisheria.bring");window.location.href="intent://#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;package=ch.publisheria.bring;S.browser_fallback_url="+fallback+";end";}
$("parse").onclick=()=>{const raw=$("rawText").value;products=splitProducts(raw);render();$("sendStatus").textContent="VERIFICA (non inviare): testo letto="+JSON.stringify(raw)+" | prodotti creati="+JSON.stringify(products);};
$("addItem").onclick=()=>{products.push("");render();const inputs=$("items").querySelectorAll("input");inputs[inputs.length-1]?.focus();};
$("listSelect").onchange=updateCount;
$("send").onclick=async()=>{const c=credentials(),listId=$("listSelect").value;const clean=products.map(x=>x.trim()).filter(Boolean);if(!c.email||!c.password||!listId||!clean.length)return;$("send").disabled=true;$("sendStatus").textContent="Invio a Bring!…";try{const d=await request("/api/add",{...c,listId,items:clean.map(name=>({name}))});$("sendStatus").textContent="Aggiunti "+(d.added??clean.length)+" prodotti alla lista!";products=[];$("rawText").value="";render();openBringApp();}catch(e){$("sendStatus").textContent=e.message;}finally{updateCount();}};
const Speech=window.SpeechRecognition||window.webkitSpeechRecognition;
if(Speech){
  let dictationActive=false;
  let prefixText="";
  let sessionResults=[];
  let silenceTimer=null;
  let recognitionEnded=false;
  let pauseElapsed=false;

  function commitSession(){
    const finalText=sessionResults.filter(r=>r&&r.final).map(r=>r.text).filter(Boolean).join(" ").trim();
    if(finalText)prefixText+=(prefixText?"\n":"")+finalText;
    $("rawText").value=prefixText;
    sessionResults=[];
  }

  function startRecognition(){
    if(!dictationActive)return;
    recognitionEnded=false;
    pauseElapsed=false;
    sessionResults=[];
    recognition=new Speech();
    recognition.lang="it-IT";
    recognition.continuous=true;
    recognition.interimResults=true;

    recognition.onresult=e=>{
      for(let i=e.resultIndex;i<e.results.length;i++){
        const result=e.results[i];
        sessionResults[i]={text:result[0].transcript.trim(),final:result.isFinal};
      }
      const finalText=sessionResults.filter(r=>r&&r.final).map(r=>r.text).filter(Boolean).join(" ");
      const interimText=sessionResults.filter(r=>r&&!r.final).map(r=>r.text).filter(Boolean).join(" ");
      $("rawText").value=prefixText+[finalText,interimText].filter(Boolean).join(" ");

      clearTimeout(silenceTimer);
      silenceTimer=setTimeout(()=>{
        silenceTimer=null;
        pauseElapsed=true;
        if(recognitionEnded){
          if(dictationActive)startRecognition();
        }else{
          try{recognition.stop();}catch(e){}
        }
      },1500);
    };

    recognition.onerror=e=>{
      if(e.error==="not-allowed"||e.error==="service-not-allowed"){
        dictationActive=false;
        clearTimeout(silenceTimer);
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
      recognitionEnded=true;
      commitSession();
      if(dictationActive){
        if(pauseElapsed){
          startRecognition();
        }else if(!silenceTimer){
          silenceTimer=setTimeout(()=>{
            silenceTimer=null;
            pauseElapsed=true;
            if(dictationActive)startRecognition();
          },1500);
        }
        return;
      }
      clearTimeout(silenceTimer);
      $("record").classList.remove("listening");
      $("record").textContent="🎙️ Tocca per dettare";
      if($("speechStatus").textContent==="Ti ascolto…")$("speechStatus").textContent="Dettatura terminata. Premi “Prepara i prodotti”.";
    };

    try{
      recognition.start();
    }catch(e){
      dictationActive=false;
      clearTimeout(silenceTimer);
      $("record").classList.remove("listening");
      $("record").textContent="🎙️ Tocca per dettare";
      $("speechStatus").textContent="Non riesco ad avviare il microfono. Controlla i permessi di Chrome oppure usa Gboard.";
    }
  }

  $("record").onclick=()=>{
    if(dictationActive){
      dictationActive=false;
      clearTimeout(silenceTimer);
      silenceTimer=null;
      if(recognitionEnded){
        $("record").classList.remove("listening");
        $("record").textContent="🎙️ Tocca per dettare";
        $("speechStatus").textContent="Dettatura terminata. Premi “Prepara i prodotti”.";
      }else{
        try{recognition?.stop();}catch(e){}
      }
      return;
    }
    prefixText=$("rawText").value.trim();
    sessionResults=[];
    $("speechStatus").textContent="Ti ascolto…";
    $("record").classList.add("listening");
    $("record").textContent="■ Ferma dettatura";
    dictationActive=true;
    startRecognition();
  };
}else{
  $("record").onclick=()=>{
    $("speechStatus").textContent="La dettatura diretta non è supportata qui. Tocca il campo e usa il microfono di Gboard.";
    $("rawText").focus();
  };
}
if("serviceWorker"in navigator&&location.protocol.startsWith("http"))navigator.serviceWorker.register("./sw.js").catch(()=>{});
render();
