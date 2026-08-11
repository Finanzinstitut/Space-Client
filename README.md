# Space Client 🚀

A custom Minecraft: Java Edition launcher with a space theme, built with **Tauri 2 (Rust + web UI)**.

## Features

- **Microsoft account login** (device code flow), implemented end to end: Microsoft → Xbox Live → XSTS → Minecraft Services, with refresh tokens so you stay signed in. ⚠️ *Currently blocked pending Mojang API approval — see Status below.*
- **Offline profile** as a fallback while approval is pending. Uses Mojang's own name-based UUID scheme, so worlds keep their player data if you later switch to a real account with the same name. Singleplayer and `online-mode=false` servers only.
- **Independent instances.** Every instance is its own folder with its own mods, worlds, RAM allocation and — importantly — its own storage location. You can put one instance on `D:\`, another on an external drive. Instances can be renamed, re-sized and re-pointed at a different loader build after creation.
- **The Space Client companion mod** is installed automatically into every Fabric and Quilt instance, including imported modpacks, and refreshed from its own GitHub releases. Each instance has a toggle to opt out. Forge and NeoForge instances skip it, since it is a Fabric mod.
- **Live console** (optional, in Settings): opens when a game starts, streams stdout and stderr in the launcher's own styling, and can kill an instance that hangs without going to Task Manager.
- **Modpack import** by button or drag and drop. Modrinth `.mrpack` and NoRisk `.noriskpack` files import fully — Minecraft version, loader, memory setting, every mod, resource pack and shader, plus the pack's overrides. Imported content is registered so it joins the normal update checks.
- **Pick the exact loader build** when creating or editing an instance, or leave it on automatic to get the newest stable one.
- **Every Minecraft version**, pulled live from Mojang's official version manifest (releases, snapshots, betas, alphas).
- **Mod loaders**: Fabric, Quilt, Forge and NeoForge. Fabric and Quilt install from their meta profiles; Forge and NeoForge run their official installers headlessly, because their bytecode-patching processors cannot be reproduced from a profile alone.
- **Modrinth browser** for **mods, resource packs and shaders**. Opens straight onto Modrinth's popular listing (no search term needed), filterable by the official category tags, and everything is restricted to the instance's Minecraft version and — for mods — its loader.
- **Pick a specific version** of any project instead of always taking the latest, listing only releases that fit the instance. Required dependencies are pulled in either way.
- **Mod updates**: check all installed mods against Modrinth and update them individually or all at once. The old jar is only deleted after the new one is written.
- **Automatic Java runtime download.** The launcher reads the `javaVersion` field of each Minecraft version and fetches the matching Mojang runtime (Java 8, 17 or 21). No manual JDK install needed.
- **Free choice of the shared data folder**, so nothing has to sit on `C:`.
- **Update check on startup** against this repository's GitHub releases.
- **English by default**, with German available in Settings.

## Status: waiting for Mojang API approval ⏳

**Signing in does not work yet.** Everything up to the final step succeeds — Microsoft login, Xbox Live, XSTS — but `api.minecraftservices.com/authentication/login_with_xbox` returns **403 Forbidden**.

This is not a bug in the launcher. Since Mojang tightened access, newly registered Azure applications must be approved before they may use the Minecraft API. Launchers that existed before that change (Prism, MultiMC and others) kept their access; new ones have to apply.

**An approval request for this application has been submitted** via the official form at https://aka.ms/mce-reviewappid. Until it is granted, the login will keep failing with 403 no matter what the code does. There is no legitimate way around it — using another project's client ID would violate Microsoft's terms.

Everything else in the launcher — instances, versions, Java runtimes, all four mod loaders, the Modrinth browser — works independently of this. An **offline profile** is available in the Account view as a stopgap; it covers singleplayer and servers running `online-mode=false`.

## Azure application

The launcher authenticates through its own registered Azure application; the client ID sits in `src-tauri/src/launcher/auth.rs` and is already configured. A client ID is not a secret — it identifies the app, it does not authorise anything. (A client *secret* would be different; this flow does not use one.)

If you ever need to register a replacement, the app must use **Personal Microsoft accounts only** and have **Allow public client flows** enabled under *Authentication*, otherwise Microsoft rejects logins with `unauthorized_client` — and it will need its own approval request as described above.

## Current limitations

- **Forge and NeoForge depend on their official installers.** The launcher downloads and runs them headlessly with `--installClient`. If an installer changes its command line or fails, the error output is passed straight through to the UI.
- **CurseForge is not integrated yet** — it needs a personal API key, unlike Modrinth's open API. Importing a CurseForge `.zip` therefore brings in the configs and overrides and sets up the right Minecraft version and loader, but **not the mods themselves**: the manifest only lists numeric project and file ids, which cannot be resolved to downloads without the API. The import reports how many mods were skipped.
- **NoRisk packs**: the profile lists every entry under `mods`, whether it is a mod, a resource pack or a shader, without saying which. Space Client asks Modrinth in bulk to classify them so each file lands in the right folder. Entries disabled in the pack are written with a `.disabled` suffix. The pack's custom JVM arguments are not imported — there is no per-instance JVM argument field yet.
- **The launcher update check only notifies**, it does not install anything — it opens the release page in your browser. A real auto-updater needs `tauri-plugin-updater` plus a signing key pair, since Tauri only accepts signed updates.
- Skin avatars in the account view are loaded from Crafatar, a third-party service.

## Troubleshooting a launch

If the game does not appear, the launcher now reports the failure directly and writes the full game output to `latest-launch.log` inside the instance folder (the 📁 button opens it). The last lines of that file usually name the cause outright — a missing library, a Java version mismatch, or a mod that refuses to load.

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
│           ├── mods.rs         # Modrinth search, versions, packs, shaders
│           ├── modpack.rs      # .mrpack / CurseForge / .nrc import
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
| 8 | CurseForge API (needs an API key) — would also complete CurseForge pack import |
| 9 ✅ | Forge / NeoForge support |
| 10 | Modpack import (.mrpack ✅, CurseForge partial, .nrc pending format) |
| 11 | Cosmetics, client mods, in-game HUD |

## License

All Rights Reserved.
