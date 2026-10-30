const API="https://api.getbring.com/rest/";
const API_KEY="cof4Nc6D8saplXjE3h3HXqHH8m7VU2i1Gs0g85Sp";
const BASE={"X-BRING-API-KEY":API_KEY,"X-BRING-CLIENT":"android","X-BRING-APPLICATION":"bring","X-BRING-COUNTRY":"IT","Accept":"application/json"};
const json=(data,status=200,origin="")=>new Response(JSON.stringify(data),{status,headers:{"Content-Type":"application/json; charset=utf-8","Access-Control-Allow-Origin":origin,"Access-Control-Allow-Methods":"POST, OPTIONS","Access-Control-Allow-Headers":"Content-Type","Vary":"Origin"}});
function allowed(origin,env){return origin==="https://andreavio00.github.io"||origin==="http://localhost:8788"||origin==="http://127.0.0.1:8787";}
const translationCache=new Map();
const SUPPORTED_LOCALES=new Set(["de-AT","de-CH","de-DE","en-AU","en-CA","en-GB","en-US","es-ES","fr-CH","fr-FR","hu-HU","it-CH","it-IT","nb-NO","nl-NL","pl-PL","pt-BR","ru-RU","sv-SE","tr-TR"]);
const DEFAULT_LOCALE_BY_LANGUAGE={de:"de-DE",en:"en-US",es:"es-ES",fr:"fr-FR",hu:"hu-HU",it:"it-IT",nb:"nb-NO",nl:"nl-NL",pl:"pl-PL",pt:"pt-BR",ru:"ru-RU",sv:"sv-SE",tr:"tr-TR"};
async function getListLocale(headers,listId){
  const r=await fetch(API+"bringusersettings/"+encodeURIComponent(headers["X-BRING-USER-UUID"]),{headers});
  if(!r.ok)throw new Error("Non riesco a leggere la lingua della lista Bring! ("+r.status+").");
  const data=await r.json();
  const setting=(data.userlistsettings||[]).find(x=>x.listUuid===listId);
  const values={};
  for(const item of (setting?.usersettings||[]))values[item.key]=item.value;
  if(values.listArticleLanguage&&SUPPORTED_LOCALES.has(values.listArticleLanguage))return values.listArticleLanguage;
  const account=await fetch(API+"v2/bringusers/"+encodeURIComponent(headers["X-BRING-USER-UUID"]),{headers});
  if(!account.ok)throw new Error("Non riesco a determinare la lingua dei prodotti Bring! ("+account.status+").");
  const user=await account.json();
  const locale=user.userLocale||{};
  const full=[locale.language,locale.country].filter(Boolean).join("-");
  if(SUPPORTED_LOCALES.has(full))return full;
  const fallback=DEFAULT_LOCALE_BY_LANGUAGE[String(locale.language||"").toLowerCase()];
  if(fallback)return fallback;
  throw new Error("Non riesco a determinare la lingua del catalogo della lista Bring!.");
}
async function getItemTranslations(locale){
  if(translationCache.has(locale))return translationCache.get(locale);
  const promise=(async()=>{
    const r=await fetch("https://web.getbring.com/locale/articles."+encodeURIComponent(locale)+".json");
    if(!r.ok)throw new Error("Catalogo Bring! non disponibile per la lingua "+locale+".");
    const source=await r.json(),reverse=new Map();
    for(const [catalogName,displayName] of Object.entries(source)){
      const key=String(displayName).normalize("NFD").replace(/[\u0300-\u036f]/g,"").trim().toLocaleLowerCase("it");
      if(key&&!reverse.has(key))reverse.set(key,catalogName);
    }
    return reverse;
  })();
  translationCache.set(locale,promise);
  try{return await promise;}catch(e){translationCache.delete(locale);throw e;}
}
function normalizeItemName(value){
  return String(value||"").trim().normalize("NFD").replace(/[\u0300-\u036f]/g,"").toLocaleLowerCase("it");
}
async function bringSession(email,password){const form=new URLSearchParams({email,password});const res=await fetch(API+"v2/bringauth",{method:"POST",headers:BASE,body:form});if(!res.ok)throw new Error(res.status===401?"Email o password Bring! non corrette.":"Bring! non ha accettato la richiesta di accesso ("+res.status+").");const a=await res.json();if(!a.access_token||!a.uuid)throw new Error("Risposta di autenticazione Bring! non riconosciuta.");return {headers:{...BASE,Authorization:(a.token_type||"Bearer")+" "+a.access_token,"X-BRING-USER-UUID":a.uuid,"X-BRING-PUBLIC-USER-UUID":a.publicUuid||""},uuid:a.uuid};}
async function handle(request,env){const origin=request.headers.get("Origin")||"";if(!allowed(origin,env))return json({error:"Origine non autorizzata."},403,origin);if(request.method==="OPTIONS")return new Response(null,{status:204,headers:{"Access-Control-Allow-Origin":origin,"Access-Control-Allow-Methods":"POST, OPTIONS","Access-Control-Allow-Headers":"Content-Type","Vary":"Origin"}});if(request.method!=="POST")return json({error:"Metodo non supportato."},405,origin);const url=new URL(request.url);try{const body=await request.json();const email=String(body.email||"").trim(),password=String(body.password||"");if(!email||!password||email.length>254||password.length>512)throw new Error("Inserisci email e password Bring! valide.");const session=await bringSession(email,password);if(url.pathname==="/api/lists"){const r=await fetch(API+"bringusers/"+encodeURIComponent(session.uuid)+"/lists",{headers:session.headers});if(!r.ok)throw new Error("Non riesco a leggere le liste Bring! ("+r.status+").");const data=await r.json();return json({lists:data.lists||[]},200,origin);}if(url.pathname==="/api/add"){const listId=String(body.listId||"");if(!/^[0-9a-f-]{20,64}$/i.test(listId))throw new Error("Seleziona una lista Bring! valida.");if(!Array.isArray(body.items)||!body.items.length||body.items.length>100)throw new Error("La lista deve contenere da 1 a 100 prodotti.");const locale=await getListLocale(session.headers,listId);const translations=await getItemTranslations(locale);const changes=body.items.map(item=>{const name=String(item.name||"").trim();return {itemId:translations.get(normalizeItemName(name))||name,spec:String(item.spec||"").trim(),uuid:crypto.randomUUID(),operation:"TO_PURCHASE",accuracy:"0.0",altitude:"0.0",latitude:"0.0",longitude:"0.0"};}).filter(x=>x.itemId&&x.itemId.length<=100);if(!changes.length)throw new Error("Non ci sono prodotti validi da inviare.");const r=await fetch(API+"v2/bringlists/"+encodeURIComponent(listId)+"/items",{method:"PUT",headers:{...session.headers,"Content-Type":"application/json"},body:JSON.stringify({changes,sender:""})});if(!r.ok)throw new Error("Bring! non ha completato l'aggiunta dei prodotti ("+r.status+").");return json({ok:true,added:changes.length},200,origin);}return json({error:"Percorso non trovato."},404,origin);}catch(e){return json({error:e instanceof Error?e.message:"Errore inatteso durante la richiesta."},400,origin);}}
export default {fetch:handle};

// Deploy trigger: verifica automatica del collegamento Cloudflare Workers Builds.
