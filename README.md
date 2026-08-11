# Space Client 🚀

A custom Minecraft: Java Edition launcher with a space theme, built with **Tauri 2 (Rust + web UI)**.

## Features

- **Microsoft account login** (device code flow). Signing in is mandatory — there is no offline mode, so every session is a legitimately authenticated one and works on normal online-mode servers.
- **Independent instances.** Every instance is its own folder with its own mods, worlds, RAM allocation and — importantly — its own storage location. You can put one instance on `D:\`, another on an external drive.
- **Every Minecraft version**, pulled live from Mojang's official version manifest (releases, snapshots, betas, alphas).
- **Mod loaders**: Fabric and Quilt are installed automatically when you create an instance.
- **Modrinth browser** built in: search, one-click install into a chosen instance, automatic download of required dependencies, and removal of installed mods. Results are filtered to the instance's Minecraft version and loader, so nothing incompatible shows up.
- **Mod updates**: check all installed mods against Modrinth and update them individually or all at once. The old jar is only deleted after the new one is written.
- **Automatic Java runtime download.** The launcher reads the `javaVersion` field of each Minecraft version and fetches the matching Mojang runtime (Java 8, 17 or 21). No manual JDK install needed.
- **Free choice of the shared data folder**, so nothing has to sit on `C:`.
- **Update check on startup** against this repository's GitHub releases.
- **English by default**, with German available in Settings.

## Azure application

The launcher authenticates through its own registered Azure application; the client ID sits in `src-tauri/src/launcher/auth.rs` and is already configured. A client ID is not a secret — it identifies the app, it does not authorise anything.

If you ever need to register a replacement, the app must use **Personal Microsoft accounts only** and have **Allow public client flows** enabled under *Authentication*, otherwise Microsoft rejects logins with `unauthorized_client`.

## Current limitations

- **Forge and NeoForge are not supported yet.** Their installers run bytecode-patching processors, which is a much larger job than Fabric's simple profile JSON. Only Vanilla, Fabric and Quilt work today.
- **CurseForge is not integrated yet** — it needs a personal API key, unlike Modrinth's open API.
- **The launcher update check only notifies**, it does not install anything — it opens the release page in your browser. A real auto-updater needs `tauri-plugin-updater` plus a signing key pair, since Tauri only accepts signed updates.
- Skin avatars in the account view are loaded from Crafatar, a third-party service.

## Data layout

```
<shared data folder>/          # chosen in Settings, shared by all instances
├── versions/                  # version JSONs + client jars
├── libraries/                 # all library jars
├── assets/                    # textures, sounds, language files
├── runtimes/                  # downloaded Java runtimes
└── natives/

<instance folder>/             # chosen per instance, fully independent
├── instance.json              # metadata, so the folder is self-describing
└── .minecraft/
    ├── mods/
    ├── saves/
    ├── options.txt
    └── resourcepacks/
```

Version and library files are deliberately shared between instances so that ten instances of 1.21.1 don't download the same 400 MB ten times. Everything that makes an instance *an instance* — mods, worlds, configs, options — lives in its own folder.

## Building (via GitHub Actions, no local toolchain needed)

1. Push this repository to GitHub.
2. `.github/workflows/build.yml` runs automatically on `main`, or manually via **Run workflow**.
3. Installers (`.msi`/`.exe` for Windows, `.deb`/`.AppImage` for Linux) appear as artifacts on the workflow run.

To make the update check useful, publish a **GitHub Release** with a tag like `v0.2.0` whenever you ship a build. The launcher compares that tag against its own version.

## Project structure

```
space-client/
├── src/                        # frontend (static HTML/CSS/JS, no bundler)
│   ├── index.html
│   ├── style.css
│   ├── main.js
│   └── i18n.js                 # English + German strings
├── src-tauri/
│   ├── Cargo.toml
│   ├── tauri.conf.json
│   ├── capabilities/default.json
│   ├── icons/
│   └── src/
│       ├── main.rs             # Tauri commands
│       └── launcher/
│           ├── auth.rs         # Microsoft → Xbox Live → XSTS → Minecraft
│           ├── config.rs       # settings, shared paths, language
│           ├── instance.rs     # instance registry and folders
│           ├── loader.rs       # Fabric / Quilt installation
│           ├── mods.rs         # Modrinth search, install, dependencies
│           ├── java.rs         # automatic JRE download
│           ├── manifest.rs     # Mojang version manifest
│           ├── download.rs     # client, libraries, assets
│           ├── launch.rs       # classpath, natives, process start
│           ├── progress.rs     # shared progress events
│           └── update.rs       # GitHub release check
└── .github/workflows/build.yml
```

## Roadmap

| Step | Content |
|---|---|
| 1 ✅ | Core launcher: versions, download, launch, custom path |
| 2 ✅ | Automatic JRE download per version |
| 3 ✅ | Microsoft account login (device code flow) |
| 4 ✅ | Instances with own folder, RAM and loader |
| 5 ✅ | Fabric / Quilt installation |
| 6 ✅ | Update check on startup |
| 7 ✅ | Modrinth API: search and one-click install into an instance |
| 8 | CurseForge API (needs an API key) |
| 9 | Forge / NeoForge support |
| 10 | Cosmetics, client mods, in-game HUD |

## License

All Rights Reserved.
