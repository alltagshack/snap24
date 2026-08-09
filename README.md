# SNAP24 - Continuous Video Recorder

Eine Android-App für kontinuierliche Videoaufnahme mit Ringpuffer.

Abgesehen von der SVG-Datei, die ich leicht angepasst habe, der 2. Markdown-Datei mit einer Zusammenfassung der Prompts und der Lizenz-Datei, ist diese App
nur mit **opencode** und dem Model **Nemotron 3 Ultra Free** entstanden.
Bis auf diesen Absatz ist auch die Readme-Datei so entstanden und alle anderen
Bilder, Texte usw. Man kann den Chat [hier](https://opncd.ai/share/RkboQxPO) einsehen
oder diese [Kurzversion](short-prompt.md).
2 oder 3 redundante Details in der Readme wurden von mir entfernt. Hier ist
noch ein [LinkedIn Artikel](https://de.linkedin.com/pulse/coding-mit-ai-ein-test-jochen-peters-b9qje) von mir dazu.

## Features

- **Kontinuierliche Aufnahme**: 30-Minuten-Segmente, Ringpuffer mit 48 Dateien (24h)
- **Automatische Rotation**: Älteste Datei wird nach 24h überschrieben
- **Dual-Kamera**: Separate Qualitäts-Einstellungen für Front- und Rückkamera
- **Kamera-Wahl**: Umschaltung zwischen Front- (Selfie) und Rückkamera
- **Bitrate-Einstellung**: 500 - 15.000 kbps (Kompressionskontrolle)
- **Wake Lock**: Verhindert Sleep-Modus während Aufnahme
- **Foreground Service**: Laufende Aufnahme auch bei App-Wechsel
- **Speicher-Überwachung**: Stopp bei <100MB freiem Speicher
- **Persistenter Ringpuffer**: Überlebt App-Neustart
- **Freier Speicher**: Anzeige im UI
- **Multi-Language Support**: 7 Sprachen (Englisch, Deutsch, Französisch, Chinesisch, Indisch, Russisch, Japanisch)

## Unterstützte Sprachen

| Sprache | Code |
|---------|------|
| English | en |
| Deutsch | de |
| Français | fr |
| 中文 (Chinese) | zh |
| हिन्दी (Hindi) | hi |
| Русский (Russian) | ru |
| 日本語 (Japanese) | ja |

Die App wählt automatisch die Sprache basierend auf den Systemeinstellungen des Geräts.

## Anforderungen

- Android 6.0+ (API 23)
- Kamera-Berechtigung
- Mikrofon-Berechtigung
- Speicher-Berechtigung
- Permanente Stromversorgung empfohlen
- Battery Optimization für App deaktivieren

## Installation

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Nutzung

1. App starten
2. Über ⋮ Menü Einstellungen vornehmen:
   - **Rückkamera Qualität** / **Frontkamera Qualität**
   - **Kamera Auswahl** (Rückkamera/Frontkamera)
   - **Video Bitrate (Kompression)** (500-15000 kbps)
3. "AUFNAHME STARTEN" drücken
4. Aufnahme läuft im Hintergrund (Notification sichtbar)
5. "STOPP" zum Beenden

## Build

```bash
./gradlew assembleDebug
# oder
./gradlew assembleRelease
```

## Lizenz

MIT
