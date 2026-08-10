const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;
const { open } = window.__TAURI__.dialog;

let config = null;
let allVersions = [];

// ---------- navigation ----------
document.querySelectorAll(".nav-item").forEach((btn) => {
  btn.addEventListener("click", () => {
    document.querySelectorAll(".nav-item").forEach((b) => b.classList.remove("active"));
    document.querySelectorAll(".view").forEach((v) => v.classList.remove("active"));
    btn.classList.add("active");
    document.getElementById("view-" + btn.dataset.view).classList.add("active");
  });
});

// ---------- helpers ----------
const $ = (id) => document.getElementById(id);

function setStatus(msg, kind = "") {
  const el = $("status-line");
  el.textContent = msg;
  el.className = "status-line " + kind;
}

function renderVersions() {
  const showSnapshots = $("show-snapshots").checked;
  const select = $("version-select");
  const previous = select.value;
  select.innerHTML = "";

  allVersions
    .filter((v) => (showSnapshots ? true : v.kind === "release"))
    .forEach((v) => {
      const opt = document.createElement("option");
      opt.value = v.id;
      opt.textContent = v.kind === "release" ? v.id : `${v.id}  (${v.kind})`;
      select.appendChild(opt);
    });

  if (previous && [...select.options].some((o) => o.value === previous)) {
    select.value = previous;
  }
}

// ---------- init ----------
async function init() {
  try {
    config = await invoke("get_config");
    $("install-path").value = config.install_path;
    $("username").value = config.default_username;
    $("ram-slider").value = config.max_ram_mb;
    $("java-path").value = config.custom_java_path || "";
    $("ram-value").textContent = config.max_ram_mb + " MB";
  } catch (e) {
    setStatus("Konnte Konfiguration nicht laden: " + e, "error");
  }

  setStatus("Lade Versionsliste ...");
  try {
    const data = await invoke("list_versions");
    allVersions = data.versions;
    renderVersions();
    $("version-select").value = data.latest_release;
    setStatus(`${allVersions.length} Versionen geladen. Neueste Release: ${data.latest_release}`, "success");
  } catch (e) {
    setStatus("Versionsliste fehlgeschlagen: " + e, "error");
  }
}

$("show-snapshots").addEventListener("change", renderVersions);

// ---------- install progress ----------
listen("install://progress", (event) => {
  const p = event.payload;
  const wrap = $("progress-wrap");
  wrap.classList.remove("hidden");

  const labels = {
    manifest: "Lade Versions-Manifest ...",
    java: "Lade passende Java-Runtime ...",
    client: "Lade Client-JAR ...",
    libraries: "Lade Bibliotheken ...",
    assets: "Lade Assets ...",
    done: "Fertig!",
  };

  const pct = p.total > 0 ? Math.round((p.current / p.total) * 100) : 0;
  $("progress-label").textContent = `${labels[p.stage] || p.stage} ${pct}%  —  ${p.file}`;
  $("progress-fill").style.width = pct + "%";

  if (p.stage === "done") {
    setTimeout(() => wrap.classList.add("hidden"), 1500);
  }
});

// ---------- install ----------
$("btn-install").addEventListener("click", async () => {
  const versionId = $("version-select").value;
  if (!versionId) return;

  $("btn-install").disabled = true;
  $("btn-launch").disabled = true;
  setStatus(`Installiere ${versionId} ...`);

  try {
    await invoke("install_version", { versionId });
    setStatus(`${versionId} erfolgreich installiert.`, "success");
  } catch (e) {
    setStatus("Installation fehlgeschlagen: " + e, "error");
  } finally {
    $("btn-install").disabled = false;
    $("btn-launch").disabled = false;
  }
});

// ---------- launch ----------
$("btn-launch").addEventListener("click", async () => {
  const versionId = $("version-select").value;
  const username = $("username").value.trim() || "Player";
  if (!versionId) return;

  $("btn-launch").disabled = true;
  try {
    const installed = await invoke("is_installed", { versionId });
    if (!installed) {
      setStatus(`${versionId} ist noch nicht installiert — starte Installation ...`);
      await invoke("install_version", { versionId });
    }
    setStatus(`Starte ${versionId} ...`);
    await invoke("launch_version", { versionId, username });
    setStatus(`${versionId} gestartet. Viel Spaß! 🚀`, "success");
  } catch (e) {
    setStatus("Start fehlgeschlagen: " + e, "error");
  } finally {
    $("btn-launch").disabled = false;
  }
});

// ---------- settings ----------
$("btn-pick-path").addEventListener("click", async () => {
  const selected = await open({ directory: true, multiple: false, title: "Installationsordner wählen" });
  if (!selected) return;
  try {
    config = await invoke("set_install_path", { path: selected });
    $("install-path").value = config.install_path;
  } catch (e) {
    alert("Pfad konnte nicht gesetzt werden: " + e);
  }
});

$("ram-slider").addEventListener("input", (e) => {
  $("ram-value").textContent = e.target.value + " MB";
});

$("btn-save-settings").addEventListener("click", async () => {
  try {
    config = await invoke("set_settings", {
      username: $("username").value.trim() || "Player",
      maxRamMb: parseInt($("ram-slider").value, 10),
      customJavaPath: $("java-path").value.trim(),
    });
    alert("Einstellungen gespeichert.");
  } catch (e) {
    alert("Speichern fehlgeschlagen: " + e);
  }
});

init();
