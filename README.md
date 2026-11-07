# Accordatore, Canto e Intona (Android)

Il progetto contiene tre app Android indipendenti:

- **Accordatore** (`:app`): accordatore per chitarra.
- **Canto** (`:canto`): app di canto esistente, mantenuta senza modifiche.
- **Intona** (`:canto-nuovo`): nuova app di esercizio vocale, creata da zero con un ID Android distinto.

## Usare Intona

Seleziona una base strumentale e una traccia con la voce solista della stessa canzone. I file vengono copiati nella memoria privata dell'app e la traccia vocale viene analizzata per disegnare le note originali. Se possibile, usa una voce separata dalla base. Ogni file deve essere inferiore a 250 MB; la traccia vocale deve durare al massimo 10 minuti.

Apri il brano, collega le cuffie e premi **Avvia**. Il grafico mostra insieme la voce originale e quella rilevata dal microfono. I suggerimenti indicano quanti centesimi alzare o abbassare; un cursore permette di sincronizzare la traccia originale e uno di compensare il ritardo del microfono. Puoi facoltativamente confrontare anche note in ottave diverse o ascoltare la voce originale.

## Compilare e installare

Apri il progetto in Android Studio e seleziona il modulo `canto-nuovo` per eseguire Intona. Con JDK 17 o successivo e Android SDK 35 configurato:

```powershell
.\gradlew.bat :canto-nuovo:assembleDebug
```

Il file APK è `canto-nuovo\build\outputs\apk\debug\canto-nuovo-debug.apk`. L'APK Intona è distinto da quelli delle app Canto e Accordatore, quindi puoi installarli sullo stesso telefono.

Anche GitHub Actions compila tutte e tre le app a ogni push. Nella pagina della build scarica l'artefatto **Intona-apk**.

## Compilare le altre app

```powershell
.\gradlew.bat :canto:assembleDebug
.\gradlew.bat :app:assembleDebug
```
