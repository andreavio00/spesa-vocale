# Spesa Vocale

Piccola PWA in italiano per dettare prodotti, controllarli e aggiungerli a una lista Bring!.

## Struttura
- App statica nella root: pubblicabile con GitHub Pages.
- Worker Cloudflare in `worker/`: gestisce l'accesso Bring! e le richieste di aggiunta multipla.

## Prima configurazione
1. Pubblica la root con GitHub Pages (Settings → Pages → Deploy from a branch → main → /(root)).
2. In Cloudflare crea un Worker dalla cartella `worker` usando Wrangler oppure collega il repository.
3. Imposta `APP_ORIGIN` su `https://andreavio00.github.io` nel file `worker/wrangler.toml`.
4. Dopo il deploy, copia l'indirizzo `*.workers.dev` in `config.js`.
5. Apri la pagina GitHub Pages su Chrome Android e scegli “Aggiungi a schermata Home”.

## Sicurezza e limiti
- Non inserire credenziali Bring! nel repository o nei messaggi. L'app le invia via HTTPS al Worker solo quando carichi le liste o aggiungi prodotti; il Worker non le salva.
- Il Worker non registra i corpi delle richieste.
- L'API delle liste personali Bring! usata qui è non ufficiale e può cambiare. Questo progetto non è affiliato a Bring! Labs AG.
- L'API key tecnica usata dal protocollo è pubblicamente presente nei client non ufficiali: non è la password dell'utente.
- La prima versione è sperimentale: va verificata con un account e una lista di prova prima dell'uso abituale.
- La dettatura diretta dipende dal supporto Web Speech del browser; in alternativa si può usare il microfono della tastiera Android.
