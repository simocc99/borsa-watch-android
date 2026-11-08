# Borsa Watch - guida all'installazione

Due pezzi: **il server** (gira su un PC Windows sempre acceso a casa) e **l'app** (sul telefono Android).

```
Yahoo Finance  ->  SERVER (PC Windows)  <-  Wi-Fi di casa  ->  APP sul telefono
                   legge i prezzi, controlla         mostra la lista, riceve gli avvisi,
                   gli avvisi, ha l'assistente AI    parla con l'assistente
```

Tutto è gratuito e per uso personale. Non serve il Play Store e non ci sono rinnovi.

## 1. Server sul PC Windows (circa 5 minuti)

1. Sul PC apri la pagina **Releases** della repo e scarica nella **stessa cartella** (es. Download) questi tre file:
   `BorsaServer.exe`, `Installa_Server.bat`, `Installa_Server.ps1`
   (facoltativo: metti nella stessa cartella anche `tickers.txt` con la tua lista: una riga per titolo, `CODICE # Nome`).
2. Doppio clic su **`Installa_Server.bat`**.
   - Se Windows dice «Windows ha protetto il PC»: **Maggiori informazioni -> Esegui comunque** (il programma non è firmato).
   - Premi **Sì** alla finestra del permesso di amministratore.
3. La finestra mostra 5 passi. Al passo 4 ti chiede se disattivare la sospensione del PC: rispondi **S** (consigliato, altrimenti con il PC addormentato non arrivano gli avvisi).
4. Alla fine si apre una pagina con l'**indirizzo** (es. `http://192.168.1.20:8765`) e il **codice di abbinamento**. Tienila aperta, ti serve tra poco.

Il server riparte da solo ogni volta che si entra in Windows.

Se hai un antivirus (es. Norton) che lo blocca: aggiungi `C:\BorsaWatch\BorsaServer.exe` alle eccezioni.

## 2. App sul telefono Android (circa 3 minuti)

1. Dal telefono apri la pagina **Releases** e scarica **`BorsaWatch.apk`**.
2. Aprilo. Android chiede di consentire l'installazione da questa fonte: **Consenti**, poi **Installa**.
3. Apri l'app, consenti le **notifiche**, vai su **Impostazioni**:
   - **Indirizzo** e **Codice**: quelli della pagina del PC (telefono e PC devono essere sulla stessa rete Wi-Fi).
   - Premi **Collega**. Se va bene compare «Collegato ✓» e nella scheda **Titoli** arriva la lista.
4. Sempre in Impostazioni: **Non risparmiare batteria per l'app** -> Batteria -> **Nessuna restrizione**.
5. Premi **Invia una notifica di prova** per controllare che le notifiche arrivino.

Per avere il widget con i primi 5 titoli: tieni premuto sulla schermata Home -> Widget -> Borsa Watch.

## 3. Assistente AI (facoltativo, gratis)

1. Vai su <https://openrouter.ai>, crea un account e poi **Keys -> Create key**.
2. Nell'app: **Impostazioni -> Assistente AI**, incolla la chiave, **Salva chiave**.
3. Nella scheda **Assistente** scrivi, per esempio, «Avvisami se Apple scende sotto 100»: ti compare una scheda **Da confermare**; solo dopo il tocco su **Conferma** cambia qualcosa.

Regole dell'assistente: non dà consigli di investimento, non inventa dati (se un numero della risposta non risulta dai dati letti, lo segnala), dice «non lo so» quando manca l'informazione.

## 4. Notifiche anche fuori casa (facoltativo, Firebase gratuito)

Senza questo passo gli avvisi arrivano controllando il server ogni 15 minuti quando il telefono è sul Wi-Fi di casa. Con Firebase arrivano subito ovunque.

1. <https://console.firebase.google.com> -> **Aggiungi progetto** (nome qualsiasi, senza Analytics).
2. **Aggiungi app Android** con nome pacchetto `it.borsawatch` -> scarica `google-services.json`.
3. **Impostazioni progetto -> Account di servizio -> Genera nuova chiave privata** -> scarica il file JSON.
4. Il file `google-services.json` va tra i segreti di GitHub (**Settings -> Secrets and variables -> Actions -> New secret**, nome `GOOGLE_SERVICES_JSON`, contenuto del file), poi rilancia il workflow **Rilascio** e reinstalla l'APK.
5. Il file JSON dell'account di servizio lo incolli nella pagina del server (`http://localhost:8765` sul PC), riquadro «Notifiche push».

## Se qualcosa non va

| Problema | Cosa fare |
|---|---|
| L'app dice «Non riesco a raggiungere il server» | Controlla che PC e telefono siano sullo stesso Wi-Fi e che il PC sia acceso e non in sospensione. Riprova l'indirizzo scritto nella pagina del PC. |
| «Il codice di abbinamento non è corretto» | Copia di nuovo il codice dalla pagina `http://localhost:8765` sul PC. |
| Nessun prezzo / pochi titoli | Yahoo limita le richieste se sono troppe. Aspetta qualche minuto e premi «Rileggi i prezzi adesso». |
| Le notifiche non arrivano | Impostazioni dell'app -> «Consenti le notifiche» e «Non risparmiare batteria». |
| Vuoi cambiare ogni quanto legge | Impostazioni -> «Come legge i prezzi il server». |
| Toglierlo dal PC | Esegui `Disinstalla_Server.ps1` (tasto destro -> Esegui con PowerShell). |

I prezzi arrivano da Yahoo Finance con qualche minuto di ritardo. Non sono consigli di investimento.
