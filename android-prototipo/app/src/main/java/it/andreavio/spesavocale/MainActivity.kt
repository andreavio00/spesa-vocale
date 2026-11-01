package it.andreavio.spesavocale

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

class MainActivity : FragmentActivity() {
    private lateinit var web: WebView
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var consecutiveServerErrors = 0
    private val silenceHandler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences by lazy { getSharedPreferences("spesa_vocale_settings", Context.MODE_PRIVATE) }
    private val appUrl = "https://andreavio00.github.io/spesa-vocale/"
    private var lastSpeechActivityAt = 0L
    private val silenceStopRunnable = object : Runnable {
        override fun run() {
            if (!listening) return
            val timeout = prefs.getInt("silence_stop_seconds", 4).coerceIn(2, 10) * 1000L
            val elapsed = android.os.SystemClock.elapsedRealtime() - lastSpeechActivityAt
            if (elapsed >= timeout) {
                listening = false
                silenceHandler.removeCallbacks(this)
                destroySpeech()
                state(false, "Dettatura arrestata dopo ${timeout / 1000} secondi di silenzio.")
            } else {
                silenceHandler.postDelayed(this, (timeout - elapsed).coerceAtMost(250L))
            }
        }
    }

    private fun noteSpeechActivity() {
        lastSpeechActivityAt = android.os.SystemClock.elapsedRealtime()
    }

    private fun armSilenceStop() {
        noteSpeechActivity()
        silenceHandler.removeCallbacks(silenceStopRunnable)
        silenceHandler.postDelayed(silenceStopRunnable, 250L)
    }

    private fun biometricPrompt(onSuccess: (BiometricPrompt.AuthenticationResult) -> Unit, onError: (String) -> Unit, cipher: Cipher? = null) {
        val manager = BiometricManager.from(this)
        if (manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) {
            onError("Impronta digitale non disponibile o non configurata. Puoi comunque inserire la password manualmente.")
            return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onSuccess(result) }
            override fun onAuthenticationError(code: Int, message: CharSequence) { onError(message.toString()) }
            override fun onAuthenticationFailed() { Toast.makeText(this@MainActivity, "Impronta non riconosciuta. Riprova.", Toast.LENGTH_SHORT).show() }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Spesa Vocale")
            .setSubtitle(if (cipher == null) "Conferma per proteggere la password Bring!" else "Sblocca le credenziali Bring!")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Annulla")
            .build()
        val crypto = cipher?.let { BiometricPrompt.CryptoObject(it) }
        if (crypto != null) prompt.authenticate(info, crypto) else prompt.authenticate(info)
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("spesa_vocale_bring_key", null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            "spesa_vocale_bring_key",
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationValidityDurationSeconds(-1)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun savePasswordSecure(account: String, password: String) {
        if (account.isBlank() || password.isBlank()) {
            Toast.makeText(this, "Inserisci User-ID/email e password.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            prefs.edit().putString("bring_account_id", account.trim()).apply()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            biometricPrompt({ result ->
                try {
                    val authenticated = result.cryptoObject?.cipher ?: throw IllegalStateException("Autenticazione non valida")
                    val encrypted = authenticated.doFinal(password.toByteArray(StandardCharsets.UTF_8))
                    prefs.edit()
                        .putString("bring_password_iv", Base64.encodeToString(authenticated.iv, Base64.NO_WRAP))
                        .putString("bring_password_data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                        .apply()
                    web.evaluateJavascript("window.onNativeCredentialsSaved && window.onNativeCredentialsSaved()", null)
                    Toast.makeText(this, "Password protetta e salvata sul telefono.", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Non sono riuscito a salvare la password: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }, { message -> Toast.makeText(this, message, Toast.LENGTH_LONG).show() }, cipher)
        } catch (e: Exception) {
            Toast.makeText(this, "Protezione biometrica non disponibile: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun connectWithBiometric() {
        val account = prefs.getString("bring_account_id", "").orEmpty()
        val ivText = prefs.getString("bring_password_iv", null)
        val dataText = prefs.getString("bring_password_data", null)
        if (account.isBlank() || ivText.isNullOrBlank() || dataText.isNullOrBlank()) {
            Toast.makeText(this, "Prima salva le credenziali nelle Impostazioni.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = Base64.decode(ivText, Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            biometricPrompt({ result ->
                try {
                    val authenticated = result.cryptoObject?.cipher ?: throw IllegalStateException("Autenticazione non valida")
                    val password = authenticated.doFinal(Base64.decode(dataText, Base64.NO_WRAP)).toString(StandardCharsets.UTF_8)
                    val accountJson = JSONObject.quote(account)
                    val passwordJson = JSONObject.quote(password)
                    web.evaluateJavascript("window.onNativeCredentials && window.onNativeCredentials($accountJson,$passwordJson)", null)
                } catch (e: Exception) {
                    Toast.makeText(this, "Credenziali non leggibili. Salvale nuovamente nelle Impostazioni.", Toast.LENGTH_LONG).show()
                }
            }, { message -> Toast.makeText(this, message, Toast.LENGTH_LONG).show() }, cipher)
        } catch (e: Exception) {
            Toast.makeText(this, "Credenziali da salvare nuovamente: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showSavePasswordDialog(account: String) {
        val field = EditText(this).apply {
            hint = "Password Bring!"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 0)
            addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle("Salva password")
            .setMessage("La password sarà cifrata sul telefono e potrà essere recuperata solo dopo l'autenticazione biometrica.")
            .setView(container)
            .setNegativeButton("Annulla", null)
            .setNeutralButton(if (prefs.contains("bring_password_data")) "Elimina password" else "Chiudi", null)
            .setPositiveButton("Proteggi con impronta") { _, _ ->
                val password = field.text.toString()
                field.setText("")
                savePasswordSecure(account, password)
            }.create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        if (prefs.contains("bring_password_data")) {
                            prefs.edit().remove("bring_password_iv").remove("bring_password_data").apply()
                            try {
                                KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("spesa_vocale_bring_key") }
                            } catch (_: Exception) {}
                            web.evaluateJavascript("window.onNativeCredentialsRemoved && window.onNativeCredentialsRemoved()", null)
                            Toast.makeText(this, "Password salvata eliminata.", Toast.LENGTH_SHORT).show()
                        }
                        dialog.dismiss()
                    }
                }
                dialog.show()
            }
    }

    private fun showSettings() {
        val account = EditText(this).apply {
            hint = "Email / User-ID Bring!"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(prefs.getString("bring_account_id", ""))
        }
        val stopLabel = TextView(this)
        val stopSeek = SeekBar(this).apply {
            max = 8
            progress = (prefs.getInt("silence_stop_seconds", 4) - 2).coerceIn(0, 8)
        }
        val phraseLabel = TextView(this)
        val phraseSeek = SeekBar(this).apply {
            max = 15
            progress = ((prefs.getInt("phrase_silence_ms", 1000) - 500) / 100).coerceIn(0, 15)
        }
        fun updateLabels() {
            stopLabel.text = "Arresto automatico dopo il silenzio: ${stopSeek.progress + 2} secondi"
            phraseLabel.text = "Pausa per chiudere una frase: ${(phraseSeek.progress + 5) / 10.0} secondi"
        }
        stopSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = updateLabels()
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        phraseSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = updateLabels()
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        updateLabels()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 0)
            addView(TextView(this@MainActivity).apply { text = "Account Bring!"; textSize = 16f })
            addView(account)
            addView(stopLabel)
            addView(stopSeek)
            addView(phraseLabel)
            addView(phraseSeek)
            addView(TextView(this@MainActivity).apply {
                text = if (prefs.contains("bring_password_data")) "Password protetta presente sul telefono." else "Nessuna password salvata."
                setPadding(0, 12, 0, 0)
            })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Impostazioni")
            .setView(layout)
            .setNegativeButton("Chiudi", null)
            .setNeutralButton("Gestisci password", null)
            .setPositiveButton("Salva", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val id = account.text.toString().trim()
                prefs.edit()
                    .putString("bring_account_id", id)
                    .putInt("silence_stop_seconds", stopSeek.progress + 2)
                    .putInt("phrase_silence_ms", 500 + phraseSeek.progress * 100)
                    .apply()
                web.evaluateJavascript("window.onNativeAccountId && window.onNativeAccountId(${JSONObject.quote(id)})", null)
                Toast.makeText(this, "Impostazioni salvate.", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val id = account.text.toString().trim()
                prefs.edit().putString("bring_account_id", id).apply()
                dialog.dismiss()
                showSavePasswordDialog(id)
            }
        }
        dialog.show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
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
              const header=document.querySelector('header');
              if(header&&!document.getElementById('nativeSettings')){
                const settings=document.createElement('button');
                settings.id='nativeSettings';settings.type='button';settings.textContent='⚙ Impostazioni';
                settings.style.cssText='margin-left:auto;padding:9px 11px;border:1px solid #d8d0c4;border-radius:10px;background:#fff;font:600 13px system-ui;color:#40382f';
                settings.onclick=()=>AndroidSpeech.openSettings();
                header.appendChild(settings);
              }
              const form=document.getElementById('bringLoginForm');
              if(form&&!document.getElementById('biometricLogin')){
                const bio=document.createElement('button');
                bio.id='biometricLogin';bio.type='button';bio.textContent='🔒 Collega con impronta';
                bio.className='secondary';bio.style.display=AndroidSpeech.hasSavedCredentials()?'block':'none';
                bio.addEventListener('click',()=>AndroidSpeech.connectWithBiometric());
                const submit=document.getElementById('loadLists');
                if(submit)form.insertBefore(bio,submit);
              }
              const savedId=AndroidSpeech.getAccountId();
              if(savedId){const email=document.getElementById('email');if(email&&!email.value)email.value=savedId;}
              window.onNativeAccountId=id=>{const email=document.getElementById('email');if(email)email.value=id||'';};
              window.onNativeCredentials=(email,password)=>{
                const e=document.getElementById('email'),p=document.getElementById('password'),form=document.getElementById('bringLoginForm');
                if(e)e.value=email;if(p)p.value=password;
                if(e)e.dispatchEvent(new Event('input',{bubbles:true}));
                if(p)p.dispatchEvent(new Event('input',{bubbles:true}));
                if(form&&form.requestSubmit)form.requestSubmit();else if(form)form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));
              };
              window.onNativeCredentialsSaved=()=>{const bio=document.getElementById('biometricLogin');if(bio)bio.style.display='block';};
              window.onNativeCredentialsRemoved=()=>{const bio=document.getElementById('biometricLogin');if(bio)bio.style.display='none';};
              let committed = f.value || '';
              let partial = '';
              const showText = value => { f.value=value; f.dispatchEvent(new Event('input',{bubbles:true})); };
              b.onclick=()=>AndroidSpeech.toggle();
              const openBring=document.getElementById('openBring');
              if(openBring)openBring.addEventListener('click',e=>{e.preventDefault();AndroidSpeech.openBring();});
              ['parse','send'].forEach(id=>{const e=document.getElementById(id);if(e)e.addEventListener('click',()=>AndroidSpeech.stop(),true);});
              window.onNativeSpeechPartialText=t=>{
                partial=(t||'').trim();
                showText(committed+(partial?(committed.trim()?'\n':'')+partial:''));
              };
              window.onNativeSpeechText=t=>{
                t=(t||'').trim();
                partial='';
                if(t) committed+=(committed.trim()?'\n':'')+t;
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
        @JavascriptInterface fun openSettings() { runOnUiThread { showSettings() } }
        @JavascriptInterface fun hasSavedCredentials(): Boolean = prefs.contains("bring_password_data") && prefs.contains("bring_password_iv")
        @JavascriptInterface fun getAccountId(): String = prefs.getString("bring_account_id", "").orEmpty()
        @JavascriptInterface fun connectWithBiometric() { runOnUiThread { this@MainActivity.connectWithBiometric() } }
        @JavascriptInterface fun openBring() {
            runOnUiThread {
                try {
                    val launchIntent = packageManager.getLaunchIntentForPackage("ch.publisheria.bring")
                    if (launchIntent != null) startActivity(launchIntent)
                    else state(false, "Bring! non risulta installata su questo dispositivo.")
                } catch (_: Exception) {
                    state(false, "Non riesco ad aprire Bring!.")
                }
            }
        }
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
        noteSpeechActivity()
        silenceHandler.removeCallbacks(silenceStopRunnable)
        silenceHandler.postDelayed(silenceStopRunnable, 250L)
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
                override fun onBeginningOfSpeech() { armSilenceStop() }
                override fun onRmsChanged(v: Float) { if (v > 2.5f) armSilenceStop() }
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(t: Int, p: Bundle?) {}
                override fun onPartialResults(p: Bundle?) {
                    val text = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (text.isNotBlank()) armSilenceStop()
                    val safe = JSONObject.quote(text)
                    web.post { web.evaluateJavascript("window.onNativeSpeechPartialText($safe)", null) }
                }
                override fun onResults(b: Bundle?) {
                    val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (text.isNotBlank()) {
                        armSilenceStop()
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
                val phraseSilence = prefs.getInt("phrase_silence_ms", 1000).coerceIn(500, 2000).toLong()
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, phraseSilence)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, phraseSilence)
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
        silenceHandler.removeCallbacks(silenceStopRunnable)
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
