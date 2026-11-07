# Accordatore per chitarra (Android)

App Android in Kotlin, senza dipendenze esterne. Ascolta il microfono, rileva la frequenza con l'algoritmo YIN e mostra nota, scostamento in centesimi e se stringere o allentare la corda.

Accordature incluse: Standard, Drop D, DADGAD, mezzo tono sotto.

## Come ottenere l'APK

1. Installa [Android Studio](https://developer.android.com/studio) (versione recente).
2. `File > Open` e scegli questa cartella (`Accordatore`). Al primo avvio scarica da solo SDK e Gradle.
3. Menu `Build > Build Bundle(s) / APK(s) > Build APK(s)`.
4. Android Studio mostra un link "locate": l'APK è in `app/build/outputs/apk/debug/app-debug.apk`.
5. Copialo sul telefono e aprilo (serve consentire l'installazione da fonti sconosciute), oppure collega il telefono con il debug USB e premi Run.

Da riga di comando (con Android SDK già installato): `./gradlew assembleDebug`.

## Uso

Alla prima apertura concedi il permesso del microfono. Scegli l'accordatura in alto e suona una corda alla volta: l'ago diventa verde quando sei entro ±5 centesimi.

## Struttura

- `PitchDetector.kt`: rilevamento della frequenza (YIN).
- `TunerView.kt`: indicatore con ago.
- `MainActivity.kt`: interfaccia, microfono e logica di accordatura.
