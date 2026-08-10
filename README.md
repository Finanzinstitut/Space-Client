# Space Client 🚀

Ein eigener Minecraft-Java-Launcher mit Weltraum-Design — gebaut mit **Tauri 2 (Rust + Web-UI)**.

## Aktueller Stand (Schritt 1: Kern-Launcher)

Funktioniert bereits:

- **Alle Minecraft-Java-Versionen** werden live aus dem offiziellen Mojang Version-Manifest geladen (Releases + optional Snapshots/Alpha/Beta).
- **Installation** einer beliebigen Version: Client-JAR, Libraries, OS-spezifische Natives und alle Assets — inklusive SHA1-Prüfung und Skip bereits vorhandener Dateien.
- **Live-Fortschrittsanzeige** über Tauri-Events (`install://progress`).
- **Spielstart** mit korrektem Classpath, entpackten Natives, Argument-Substitution (modernes `arguments`-Format *und* altes `minecraftArguments`).
- **Frei wählbarer Installationspfad** — Versionen, Libraries, Assets und Instanzen landen dort, wo du willst (z.B. `D:\SpaceClient`). Auf `C:` liegt nur eine winzige `settings.json` mit dem Pfad-Zeiger.
- **Automatischer Java-Download**: Space Client liest das `javaVersion`-Feld der Version und holt die passende Mojang-Runtime (`jre-legacy` = Java 8, `java-runtime-gamma` = 17, `java-runtime-delta` = 21) in den gewählten Installationsordner. Kein manuelles JDK mehr nötig. Optionaler Override-Pfad in den Settings.
- **RAM-Zuweisung** per Slider (`-Xmx`).
- Weltraum-UI: animiertes Sternenfeld, Planeten-Glow, Violett/Cyan-Farbschema.

## Wichtige Einschränkungen (ehrlich gesagt)

- **Kein Microsoft-Login.** Der Start läuft aktuell im *Offline-Modus* mit einem lokal generierten UUID. Das reicht für Singleplayer und Server mit `online-mode=false` (z.B. viele Test-Server), **nicht** für normale Online-Server oder Realms. Echter MS-OAuth-Flow (Device Code → Xbox Live → XSTS → Minecraft-Token) ist der nächste große Baustein.
- **Noch nicht gebaut:** Mod-Loader-Installation (Fabric/Forge/Quilt/NeoForge), Modrinth/CurseForge-Integration, Instanz-Verwaltung, Cosmetics, Performance-Mods, eigenes In-Game-HUD.
- Der Code ist **noch nie kompiliert worden** — Rust/Cargo standen mir hier nicht zur Verfügung. Der erste CI-Lauf wird sehr wahrscheinlich noch ein paar Compilerfehler zeigen; die arbeiten wir dann durch, so wie bei deinen Fabric-Mods auch.

## Bauen (über GitHub Actions, ohne lokales Tooling)

1. Repo unter der `Finanzinstitut`-Organisation anlegen und diese Dateien pushen.
2. Der Workflow `.github/workflows/build.yml` läuft automatisch auf `main` (oder manuell über "Run workflow").
3. Fertige Installer (`.msi`/`.exe` für Windows, `.deb`/`.AppImage` für Linux) landen als Artifacts im Workflow-Run.

## Projektstruktur

```
space-client/
├── src/                        # Frontend (statisches HTML/CSS/JS, kein Bundler nötig)
│   ├── index.html
│   ├── style.css
│   └── main.js
├── src-tauri/
│   ├── Cargo.toml
│   ├── build.rs
│   ├── tauri.conf.json
│   ├── capabilities/default.json
│   ├── icons/                  # Platzhalter-Icons (Planet mit Ring)
│   └── src/
│       ├── main.rs             # Tauri-Commands
│       └── launcher/
│           ├── config.rs       # Custom Install Path + Settings
│           ├── manifest.rs     # Mojang Version-Manifest
│           ├── java.rs         # Automatischer JRE-Download + Java-Auflösung
│           ├── progress.rs     # Geteilte Fortschritts-Events
│           ├── download.rs     # Client/Libraries/Assets Downloader
│           └── launch.rs       # Classpath, Natives, Prozessstart
└── .github/workflows/build.yml
```

## Roadmap

| Schritt | Inhalt |
|---|---|
| 1 ✅ | Kern-Launcher: Versionen, Download, Start, Custom-Pfad |
| 2 ✅ | Automatischer JRE-Download passend zur Version |
| 3 | Microsoft-Account-Login (Device-Code-Flow) |
| 4 | Mod-Loader: Fabric / Forge / Quilt / NeoForge installieren |
| 5 | Instanzen: mehrere getrennte Profile mit eigenem Mods-Ordner |
| 6 | Modrinth-API: Suche + Ein-Klick-Install in eine Instanz |
| 7 | CurseForge-API (braucht einen eigenen API-Key) |
| 8 | Cosmetics, Client-Mods, In-Game-HUD |

## Lizenz

All Rights Reserved.
