package it.andreavio.spesavocale

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var consecutiveServerErrors = 0
    private val appUrl = "https://andreavio00.github.io/spesa-vocale/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "AndroidSpeech")
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme == "https" && uri.host == "andreavio00.github.io") return false
                if (uri.scheme == "intent") {
                    try { startActivity(Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)) } catch (_: Exception) {}
                    return true
                }
                if (uri.scheme == "https") {
                    try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) {}
                }
                return true
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (url.startsWith(appUrl)) installBridge()
            }
        }
        web.loadUrl(appUrl)
    }

    private fun installBridge() {
        web.evaluateJavascript("""
            (function(){
              const b=document.getElementById('record'), f=document.getElementById('rawText'), s=document.getElementById('speechStatus');
              if(!b||!f||!window.AndroidSpeech)return;
              let committed = f.value || '';
              let partial = '';
              const showText = value => { f.value=value; f.dispatchEvent(new Event('input',{bubbles:true})); };
              b.onclick=()=>AndroidSpeech.toggle();
              ['parse','send'].forEach(id=>{const e=document.getElementById(id);if(e)e.addEventListener('click',()=>AndroidSpeech.stop(),true);});
              window.onNativeSpeechPartialText=t=>{
                partial=(t||'').trim();
                showText(committed+(partial?(committed.trim()?'\\n':'')+partial:''));
              };
              window.onNativeSpeechText=t=>{
                t=(t||'').trim();
                partial='';
                if(t) committed+=(committed.trim()?'\\n':'')+t;
                showText(committed);
              };
              window.onNativeSpeechState=(active,msg)=>{
                if(active){committed=f.value||'';partial='';}
                b.classList.toggle('listening',!!active);
                b.textContent=active?'■ Ferma dettatura':'🎙️ Tocca per dettare';
                s.textContent=msg||'';
              };
              window.onNativeSpeechError=m=>{partial='';showText(committed);s.textContent=m;};
            })();
        """.trimIndent(), null)
    }

    inner class Bridge {
        @JavascriptInterface fun toggle() { runOnUiThread { if (listening) stopSpeech() else askPermissionAndStart() } }
        @JavascriptInterface fun stop() { runOnUiThread { stopSpeech() } }
    }

    private fun askPermissionAndStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 41)
        } else startSpeech()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 41) {
            if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) startSpeech()
            else state(false, "Permesso microfono negato.")
        }
    }

    private fun startSpeech() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            state(false, "Riconoscimento vocale Android non disponibile.")
            return
        }
        listening = true
        consecutiveServerErrors = 0
        state(true, "Ti ascolto… pronuncia i prodotti. Puoi fare pause tra i gruppi.")
        listenAgain()
    }

    private fun listenAgain() {
        if (!listening) return
        try {
            // Crea il recognizer una sola volta e riutilizzalo dopo ogni risultato.
            // Distruggerlo e ricrearlo a ogni frase può far cadere il servizio Android.
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this)
                recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(t: Int, p: Bundle?) {}
                override fun onPartialResults(p: Bundle?) {
                    val text = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    val safe = JSONObject.quote(text)
                    web.post { web.evaluateJavascript("window.onNativeSpeechPartialText($safe)", null) }
                }
                override fun onResults(b: Bundle?) {
                    val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (text.isNotBlank()) {
                        consecutiveServerErrors = 0
                        val safe = JSONObject.quote(text)
                        web.post { web.evaluateJavascript("window.onNativeSpeechText($safe)", null) }
                    }
                    if (listening) web.postDelayed({ listenAgain() }, 250)
                }
                override fun onError(error: Int) {
                    web.post { web.evaluateJavascript("window.onNativeSpeechPartialText('')", null) }
                    if (!listening) return
                    if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                        error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                        error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) {
                        if (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) {
                            consecutiveServerErrors++
                            // Ricrea il servizio con una pausa crescente, senza
                            // lasciare il pulsante in uno stato apparentemente attivo.
                            val delay = 1500L
                            state(true, "Il servizio vocale si è disconnesso: nuovo tentativo…")
                            destroySpeech()
                            if (consecutiveServerErrors >= 3) {
                                listening = false
                                state(false, "Il servizio di riconoscimento vocale continua a disconnettersi. Non continuo a tentare: verifica il servizio vocale Android.")
                                return
                            }
                            web.postDelayed({ if (listening) listenAgain() }, delay)
                        } else {
                            web.postDelayed({ if (listening) listenAgain() }, 400)
                        }
                    } else {
                        listening = false
                        destroySpeech()
                        state(false, "Riconoscimento interrotto (codice $error). Riprova.")
                    }
                }
                })
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                // Non forzare la modalità offline: alcuni servizi dichiarano
                // il supporto on-device ma si disconnettono con il modello it-IT.
                // Una pausa breve chiude il prodotto corrente; le parole pronunciate
                // prima della pausa restano nella stessa frase (es. "vino rosso").
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
            }
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            listening = false
            destroySpeech()
            state(false, "Impossibile avviare il microfono: ${e.localizedMessage ?: "errore"}")
        }
    }

    private fun state(active: Boolean, message: String) {
        val safe = JSONObject.quote(message)
        web.post { web.evaluateJavascript("window.onNativeSpeechState($active,$safe)", null) }
    }
    private fun stopSpeech() {
        listening = false
        destroySpeech()
        state(false, "Dettatura terminata. Premi «Prepara i prodotti».")
    }
    private fun destroySpeech() {
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }
    override fun onPause() { if (listening) stopSpeech(); super.onPause() }
    override fun onDestroy() { stopSpeech(); web.destroy(); super.onDestroy() }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }
}
