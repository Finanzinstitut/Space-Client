import { t, setLanguage, applyTranslations } from "./i18n.js";
import { createSkinViewer, renderSkinFlat, renderCape } from "./skinrender.js";

const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;
const { open } = window.__TAURI__.dialog;
const shell = window.__TAURI__.shell;

const $ = (id) => document.getElementById(id);

let config = null;
let account = null;
let allVersions = [];
let instances = [];
let pendingLogin = null;

// ---------------- navigation ----------------
document.querySelectorAll(".nav-item").forEach((btn) => {
  btn.addEventListener("click", () => {
    document.querySelectorAll(".nav-item").forEach((b) => b.classList.remove("active"));
    document.querySelectorAll(".view").forEach((v) => v.classList.remove("active"));
    btn.classList.add("active");
    $("view-" + btn.dataset.view).classList.add("active");
  });
});

function showView(name) {
  document.querySelectorAll(".nav-item").forEach((b) =>
    b.classList.toggle("active", b.dataset.view === name)
  );
  document.querySelectorAll(".view").forEach((v) =>
    v.classList.toggle("active", v.id === "view-" + name)
  );
}

function setStatus(el, msg, kind = "") {
  const node = $(el);
  node.textContent = msg;
  node.className = "status-line " + kind;
}

// ---------------- instances ----------------
function renderInstances() {
  const list = $("instance-list");
  list.innerHTML = "";

  if (instances.length === 0) {
    const empty = document.createElement("p");
    empty.className = "empty-note";
    empty.textContent = t("instances_empty");
    list.appendChild(empty);
    return;
  }

  instances.forEach((inst) => {
    const card = document.createElement("div");
    card.className = "instance-card";

    const installed = !!inst.version_id && inst.version_id.length > 0 && inst.installed !== false;
    const loaderLabel =
      inst.loader === "vanilla"
        ? "Vanilla"
        : inst.loader.charAt(0).toUpperCase() + inst.loader.slice(1);

    card.innerHTML = `
      <div class="instance-main">
        <div class="instance-orb"></div>
        <div>
          <div class="instance-name"></div>
          <div class="instance-meta">
            <span class="tag">${inst.mc_version}</span>
            <span class="tag">${loaderLabel}</span>
            <span class="tag">${inst.ram_mb} MB</span>
          </div>
          <div class="instance-path"></div>
        </div>
      </div>
      <div class="instance-actions"></div>
    `;
    card.querySelector(".instance-name").textContent = inst.name;
    card.querySelector(".instance-path").textContent = inst.path;

    const actions = card.querySelector(".instance-actions");

    const editBtn = document.createElement("button");
    editBtn.className = "btn icon-btn small";
    editBtn.textContent = "✏️";
    editBtn.title = t("btn_edit");
    editBtn.onclick = () => openEditModal(inst);

    const playBtn = document.createElement("button");
    playBtn.className = "btn primary small";
    playBtn.textContent = t("btn_play");
    playBtn.onclick = () => launchInstance(inst);
    actions.appendChild(playBtn);

    const installBtn = document.createElement("button");
    installBtn.className = "btn secondary small";
    installBtn.textContent = t("btn_install");
    installBtn.onclick = () => installInstance(inst);
    actions.appendChild(installBtn);

    const folderBtn = document.createElement("button");
    folderBtn.className = "btn icon-btn small";
    folderBtn.textContent = "📁";
    folderBtn.title = t("btn_open_folder");
    folderBtn.onclick = async () => {
      try {
        await invoke("open_instance_folder", { id: inst.id });
      } catch (e) {
        setStatus("global-status", String(e), "error");
      }
    };
    actions.appendChild(folderBtn);

    const delBtn = document.createElement("button");
    delBtn.className = "btn danger small";
    delBtn.textContent = t("btn_delete");
    delBtn.onclick = async () => {
      if (!confirm(t("confirm_delete", { name: inst.name }))) return;
      await invoke("delete_instance", { id: inst.id, deleteFiles: true });
      await refreshInstances();
    };
    actions.appendChild(editBtn);
    actions.appendChild(delBtn);

    // An empty version_id means the loader profile still has to be built.
    if (!inst.version_id) {
      const tag = document.createElement("span");
      tag.className = "tag warn";
      tag.textContent = t("needs_install");
      card.querySelector(".instance-meta").appendChild(tag);
    }

    list.appendChild(card);
  });
}

async function refreshInstances() {
  instances = await invoke("list_instances");
  renderInstances();
  renderModsInstanceOptions();
  renderRunning();
}

async function installInstance(inst) {
  setStatus("global-status", t("installing", { name: inst.name }));
  $("progress-wrap").classList.remove("hidden");
  try {
    await invoke("install_instance", { id: inst.id });
    setStatus("global-status", t("install_done", { name: inst.name }), "success");
    await refreshInstances();
  } catch (e) {
    setStatus("global-status", String(e), "error");
  }
}

async function launchInstance(inst) {
  if (!account) {
    setStatus("global-status", t("signin_required"), "error");
    showView("account");
    return;
  }
  setStatus("global-status", t("launching", { name: inst.name }));
  if (config?.live_logs) openConsole(inst);

  try {
    await invoke("launch_instance", { id: inst.id });
    setStatus("global-status", t("launched", { name: inst.name }), "success");
  } catch (e) {
    setStatus("global-status", String(e), "error");
    if (config?.live_logs) appendConsoleLine(String(e), "err");
  }
}






// ---------------- accounts ----------------
let accounts = [];

async function refreshAccounts() {
  try {
    accounts = await invoke("list_accounts");
  } catch {
    accounts = [];
  }
  renderAccountList();
}

function renderAccountList() {
  const list = $("account-list");
  list.innerHTML = "";
  if (accounts.length === 0) return;

  const heading = document.createElement("label");
  heading.textContent = t("accounts_title");
  list.appendChild(heading);

  accounts.forEach((entry) => {
    const row = document.createElement("div");
    row.className = "account-row" + (entry.active ? " active" : "");

    const avatar = document.createElement("canvas");
    avatar.className = "account-avatar";
    avatar.width = 32;
    avatar.height = 32;
    drawHead(avatar, entry);

    const name = document.createElement("div");
    name.className = "account-name-row";
    name.textContent = entry.username;
    if (entry.offline) {
      const badge = document.createElement("span");
      badge.className = "badge";
      badge.textContent = t("offline_badge");
      name.appendChild(badge);
    }

    const actions = document.createElement("div");
    actions.className = "account-actions";

    if (entry.active) {
      const tag = document.createElement("span");
      tag.className = "tag";
      tag.textContent = t("account_active");
      actions.appendChild(tag);
    } else {
      const swap = document.createElement("button");
      swap.className = "btn secondary small";
      swap.textContent = t("btn_switch");
      swap.onclick = () => switchAccount(entry);
      actions.appendChild(swap);
    }

    const remove = document.createElement("button");
    remove.className = "btn danger small";
    remove.textContent = t("btn_remove_account");
    remove.onclick = async () => {
      if (!confirm(t("confirm_remove_account", { name: entry.username }))) return;
      account = await invoke("remove_account", { uuid: entry.uuid });
      skinProfile = null;
      await refreshAccounts();
      renderAccount();
      loadSkinProfile();
    };
    actions.appendChild(remove);

    row.appendChild(avatar);
    row.appendChild(name);
    row.appendChild(actions);
    list.appendChild(row);
  });
}

async function switchAccount(entry) {
  try {
    account = await invoke("switch_account", { uuid: entry.uuid });
    skinProfile = null;
    await refreshAccounts();
    renderAccount();
    refreshAccounts();
    loadSkinProfile();
    setStatus("account-status", t("account_switched", { name: entry.username }), "success");
  } catch (e) {
    setStatus("account-status", String(e), "error");
  }
}

/// Small head render for the account rows. Offline profiles have no texture,
/// so they get a plain placeholder instead of a failed request.
async function drawHead(canvas, entry) {
  const ctx = canvas.getContext("2d");
  ctx.imageSmoothingEnabled = false;

  if (entry.offline) {
    ctx.fillStyle = "#241f57";
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    return;
  }

  try {
    const profile = entry.active && skinProfile ? skinProfile : null;
    const url = profile?.skin_url;
    if (!url) {
      ctx.fillStyle = "#241f57";
      ctx.fillRect(0, 0, canvas.width, canvas.height);
      return;
    }
    const image = new Image();
    image.crossOrigin = "anonymous";
    image.onload = () => {
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      ctx.drawImage(image, 8, 8, 8, 8, 0, 0, canvas.width, canvas.height);
      ctx.drawImage(image, 40, 8, 8, 8, 0, 0, canvas.width, canvas.height);
    };
    image.src = url;
  } catch {
    // A missing avatar is cosmetic; leave the canvas blank.
  }
}

// ---------------- skin & capes ----------------
let skinProfile = null;

function renderSkinView() {
  const warning = $("skin-warning");
  const panel = $("skin-panel");

  if (!account) {
    warning.textContent = t("signin_required");
    warning.classList.remove("hidden");
    panel.classList.add("hidden");
    return;
  }
  if (account.offline) {
    warning.textContent = t("skin_needs_ms");
    warning.classList.remove("hidden");
    panel.classList.add("hidden");
    return;
  }
  warning.classList.add("hidden");
  panel.classList.remove("hidden");

  if (!skinProfile) return;

  $("skin-name").textContent = skinProfile.username;

  const isSlimModel = (skinProfile.variant || "").toUpperCase() === "SLIM";
  const activeCape = skinProfile.capes.find((c) => c.active);
  if (skinProfile.skin_url) {
    skinViewer()
      .setSkin(skinProfile.skin_url, isSlimModel, activeCape ? activeCape.url : "")
      .catch(() => {
        setStatus("skin-status", t("skin_render_failed"), "error");
      });
  }

  const isSlim = (skinProfile.variant || "").toUpperCase() === "SLIM";
  $("model-classic").classList.toggle("active", !isSlim);
  $("model-slim").classList.toggle("active", isSlim);

  renderCapes();
}

/**
 * The 3D preview is built once and kept, so dragging it does not get reset
 * every time the profile is re-rendered after a cape or model change.
 */
let viewer = null;
function skinViewer() {
  if (!viewer) viewer = createSkinViewer($("skin-canvas"));
  return viewer;
}

/** Everything in the local skin library, newest first. */
let savedSkins = [];

async function loadSavedSkins() {
  try {
    savedSkins = await invoke("list_saved_skins");
    renderSkinLibrary();
  } catch (e) {
    setStatus("skin-status", String(e), "error");
  }
}

function renderSkinLibrary() {
  const grid = $("skin-library");
  grid.innerHTML = "";

  if (savedSkins.length === 0) {
    const note = document.createElement("p");
    note.className = "empty-note";
    note.textContent = t("skin_library_empty");
    grid.appendChild(note);
    return;
  }

  savedSkins.forEach((skin) => {
    const item = document.createElement("div");
    item.className = "skin-item";

    const button = document.createElement("button");
    button.className = "skin-item-apply";
    button.title = t("skin_apply_hint", { name: skin.name });

    const thumb = document.createElement("canvas");
    thumb.className = "skin-thumb";
    // The PNG arrives as a data URL, so the thumbnail works with no network.
    renderSkinFlat(thumb, skin.data_url, skin.variant === "slim", 4).catch(() => {});

    const label = document.createElement("span");
    label.textContent = skin.name;

    button.appendChild(thumb);
    button.appendChild(label);
    button.onclick = () => applySavedSkin(skin);
    item.appendChild(button);

    const del = document.createElement("button");
    del.className = "skin-item-remove";
    del.textContent = "×";
    del.title = t("skin_forget_hint");
    del.onclick = async (event) => {
      event.stopPropagation();
      try {
        await invoke("delete_saved_skin", { id: skin.id });
        setStatus("skin-status", t("skin_forgotten", { name: skin.name }), "success");
        await loadSavedSkins();
      } catch (e) {
        setStatus("skin-status", String(e), "error");
      }
    };
    item.appendChild(del);

    // Renaming is the only edit worth having, and a prompt keeps it out of the
    // way of the one-click apply that the grid is really for.
    item.ondblclick = async () => {
      const name = window.prompt(t("skin_rename_prompt"), skin.name);
      if (name === null) return;
      try {
        await invoke("rename_saved_skin", { id: skin.id, name });
        await loadSavedSkins();
      } catch (e) {
        setStatus("skin-status", String(e), "error");
      }
    };

    grid.appendChild(item);
  });
}

async function applySavedSkin(skin) {
  setStatus("skin-status", t("skin_uploading", { name: skin.name }));
  try {
    skinProfile = await invoke("apply_saved_skin", { id: skin.id, variant: "" });
    setStatus("skin-status", t("skin_uploaded"), "success");
    renderSkinView();
  } catch (e) {
    setStatus("skin-status", String(e), "error");
  }
}

$("btn-save-current-skin").addEventListener("click", async () => {
  const suggested = skinProfile?.username ? `${skinProfile.username} skin` : "";
  const name = window.prompt(t("skin_save_prompt"), suggested);
  if (name === null) return;
  try {
    await invoke("save_current_skin", { name });
    setStatus("skin-status", t("skin_saved"), "success");
    await loadSavedSkins();
  } catch (e) {
    setStatus("skin-status", String(e), "error");
  }
});

function renderCapes() {
  const list = $("cape-list");
  list.innerHTML = "";

  const none = document.createElement("button");
  none.className = "cape-item" + (skinProfile.capes.every((c) => !c.active) ? " active" : "");
  none.innerHTML = `<div class="cape-thumb empty"></div><span></span>`;
  none.querySelector("span").textContent = t("cape_none");
  none.onclick = () => applyCape("");
  list.appendChild(none);

  if (skinProfile.capes.length === 0) {
    const note = document.createElement("p");
    note.className = "empty-note";
    note.textContent = t("skin_no_capes");
    list.appendChild(note);
    return;
  }

  skinProfile.capes.forEach((cape) => {
    const item = document.createElement("button");
    item.className = "cape-item" + (cape.active ? " active" : "");

    const thumb = document.createElement("canvas");
    thumb.className = "cape-thumb";
    if (cape.url) renderCape(thumb, cape.url).catch(() => {});

    const label = document.createElement("span");
    label.textContent = cape.alias;

    item.appendChild(thumb);
    item.appendChild(label);
    item.onclick = () => applyCape(cape.id);
    list.appendChild(item);
  });
}

async function loadSkinProfile() {
  if (!account || account.offline) {
    renderSkinView();
    return;
  }
  setStatus("skin-status", t("skin_loading"));
  try {
    skinProfile = await invoke("get_skin_profile");
    setStatus("skin-status", "");
    renderSkinView();
    renderAccountList();
    await loadSavedSkins();
  } catch (e) {
    setStatus("skin-status", String(e), "error");
  }
}

async function uploadSkinFile(path) {
  const fileName = path.split(/[\\/]/).pop();
  const variant = $("model-slim").classList.contains("active") ? "slim" : "classic";
  setStatus("skin-status", t("skin_uploading", { name: fileName }));
  try {
    skinProfile = await invoke("upload_skin", { path, variant });
    setStatus("skin-status", t("skin_uploaded"), "success");
    renderSkinView();
    // The backend filed a copy on the way through, so the grid has a new entry.
    await loadSavedSkins();
  } catch (e) {
    setStatus("skin-status", String(e), "error");
  }
}

async function applyVariant(variant) {
  setStatus("skin-status", "");
  try {
    skinProfile = await invoke("set_skin_variant", { variant });
    setStatus("skin-status", t("skin_model_changed"), "success");
    renderSkinView();
  } catch (e) {
    setStatus("skin-status", String(e), "error");
  }
}

async function applyCape(capeId) {
  setStatus("skin-status", "");
  try {
    skinProfile = await invoke("set_cape", { capeId });
    setStatus("skin-status", t("skin_cape_changed"), "success");
    renderSkinView();
  } catch (e) {
    setStatus("skin-status", String(e), "error");
  }
}

$("model-classic").addEventListener("click", () => applyVariant("classic"));
$("model-slim").addEventListener("click", () => applyVariant("slim"));

$("skin-drop-zone").addEventListener("click", async () => {
  const selected = await open({
    multiple: false,
    filters: [{ name: "Skin", extensions: ["png"] }],
  });
  if (selected) uploadSkinFile(selected);
});

/**
 * Installs Cosmetica into a freshly created instance.
 *
 * Goes through the same install_mod path as any other mod, so the right build
 * for the instance's version and loader is chosen and dependencies come along
 * on their own - rather than hardcoding a download url that would rot the
 * moment a new Minecraft version lands.
 *
 * Failure is reported and swallowed. The instance is already made and playable
 * by this point, so a cosmetics mod that could not be fetched is a note, not a
 * reason to leave the player with a broken creation.
 */
async function installCosmetica(inst) {
  try {
    setStatus("global-status", t("installing_cosmetica"));
    await invoke("install_mod", {
      instanceId: inst.id,
      projectId: "cosmetica",
      projectType: "mod",
    });
    setStatus("global-status", t("cosmetica_done"), "ok");
  } catch (e) {
    setStatus("global-status", t("cosmetica_failed") + " " + String(e), "error");
  }
}

// ---------------- running instances ----------------

/** Ids currently running, so the strip can be drawn without asking per frame. */
let runningIds = new Set();

/**
 * Refreshes which instances are running and redraws the strip.
 *
 * Polled rather than pushed: the backend already tracks children and exposes
 * is_running, and a poll every couple of seconds is cheaper to get right than
 * a new event channel for something that changes a handful of times a session.
 */
async function refreshRunning() {
  const found = new Set();
  for (const inst of instances) {
    try {
      if (await invoke("is_running", { id: inst.id })) found.add(inst.id);
    } catch {
      // An instance that cannot be asked is treated as stopped
    }
  }
  runningIds = found;
  renderRunning();
}

function renderRunning() {
  const strip = $("running-strip");
  const list = $("running-list");
  const button = $("btn-running");
  const label = $("btn-running-label");

  // refreshInstances calls this, and refreshInstances is on the path to
  // installing anything. Missing markup must degrade to no strip, not to a
  // broken launcher.
  if (!strip || !list || !button) return;

  const active = instances.filter((i) => runningIds.has(i.id));

  button.classList.toggle("hidden", active.length === 0);
  if (label) {
    label.textContent = t("running_button") + " (" + active.length + ")";
  }

  if (active.length === 0) {
    strip.classList.add("hidden");
    list.innerHTML = "";
    return;
  }

  list.innerHTML = "";
  for (const inst of active) {
    const row = document.createElement("button");
    row.className = "running-item";
    row.innerHTML =
      '<span class="run-dot"></span>' +
      '<span class="running-name"></span>' +
      '<span class="running-version"></span>' +
      '<span class="running-open" data-i18n="running_open"></span>';
    row.querySelector(".running-name").textContent = inst.name;
    row.querySelector(".running-version").textContent =
      inst.mc_version + " " + inst.loader;
    row.querySelector(".running-open").textContent = t("running_open");
    row.addEventListener("click", () => openConsole(inst));
    list.appendChild(row);
  }
}

// Guarded because this runs the moment the file loads. An unguarded call on a
// missing element throws here, and a throw at the top level takes the whole
// script with it - which would stop instances installing, mods downloading and
// everything else, for the sake of a button.
const runningButton = $("btn-running");
if (runningButton) {
  runningButton.addEventListener("click", () => {
    const strip = $("running-strip");
    if (strip) strip.classList.toggle("hidden");
  });
}

// A game can also exit on its own, so the strip is polled rather than only
// refreshed when the launcher does something.
setInterval(() => {
  try {
    refreshRunning().catch(() => {});
  } catch {
    // Never let the poll be the reason something else stops working
  }
}, 3000);

// ---------------- crash analysis ----------------

/**
 * Sends this instance's newest crash report to mclo.gs and shows what it found.
 *
 * The log goes to a third party, which is the whole point - their analyser is
 * what turns a wall of stack traces into a sentence - but it is worth being
 * plain about, so the panel says where the log went and links to it.
 */
async function analyseCrash() {
  var panel = $("crash-report");
  var button = $("btn-crash-analyse");
  if (!panel || !consoleInstanceId) return;

  panel.classList.remove("hidden");
  panel.innerHTML = '<div class="crash-busy">' + t("crash_working") + "</div>";
  if (button) button.disabled = true;

  try {
    var report = await invoke("analyse_crash", { id: consoleInstanceId });
    renderCrashReport(report);
  } catch (e) {
    panel.innerHTML =
      '<div class="crash-head"><b>' + t("crash_failed") + "</b></div>" +
      '<div class="crash-note"></div>';
    panel.querySelector(".crash-note").textContent = String(e);
  } finally {
    if (button) button.disabled = false;
  }
}

function renderCrashReport(report) {
  var panel = $("crash-report");
  panel.innerHTML = "";

  var head = document.createElement("div");
  head.className = "crash-head";
  var title = document.createElement("b");
  title.textContent = report.title || t("crash_result");
  head.appendChild(title);

  var src = document.createElement("span");
  src.className = "crash-source";
  src.textContent = report.source;
  head.appendChild(src);

  var link = document.createElement("a");
  link.className = "crash-link";
  link.href = report.url;
  link.target = "_blank";
  link.rel = "noopener";
  link.textContent = t("crash_open");
  head.appendChild(link);

  var close = document.createElement("button");
  close.className = "crash-close";
  close.textContent = "\u00d7";
  close.addEventListener("click", function () {
    panel.classList.add("hidden");
  });
  head.appendChild(close);

  panel.appendChild(head);

  if (!report.problems || report.problems.length === 0) {
    var none = document.createElement("div");
    none.className = "crash-note";
    // Nothing recognised is genuinely useful to know, and not the same as a
    // failure - it means look at the log yourself, or share the link.
    none.textContent = t("crash_none");
    panel.appendChild(none);
  }

  (report.problems || []).forEach(function (problem) {
    var item = document.createElement("div");
    item.className = "crash-item";

    var msg = document.createElement("div");
    msg.className = "crash-msg";
    msg.textContent = problem.message;
    item.appendChild(msg);

    if (problem.excerpt) {
      var code = document.createElement("code");
      code.className = "crash-excerpt";
      code.textContent = problem.excerpt;
      item.appendChild(code);
    }

    (problem.solutions || []).forEach(function (solution) {
      var fix = document.createElement("div");
      fix.className = "crash-fix";
      fix.textContent = solution;
      item.appendChild(fix);
    });

    panel.appendChild(item);
  });

  if (report.information && report.information.length) {
    var info = document.createElement("div");
    info.className = "crash-info";
    info.textContent = report.information.slice(0, 6).join("  ·  ");
    panel.appendChild(info);
  }
}

var crashButton = $("btn-crash-analyse");
if (crashButton) crashButton.addEventListener("click", analyseCrash);

// ---------------- live console ----------------
let consoleInstanceId = null;
const MAX_CONSOLE_LINES = 2000;

function openConsole(inst) {
  consoleInstanceId = inst.id;
  $("console-instance").textContent = inst.name;
  $("console-output").innerHTML = "";
  $("console-backdrop").classList.remove("hidden");
  $("btn-console-kill").disabled = false;
}

function closeConsole() {
  $("console-backdrop").classList.add("hidden");
  consoleInstanceId = null;
}

function appendConsoleLine(text, kind = "") {
  const out = $("console-output");
  const line = document.createElement("div");
  line.className = "console-line " + kind;
  line.textContent = text;
  out.appendChild(line);

  // Keep the DOM from growing without bound during long sessions
  while (out.childElementCount > MAX_CONSOLE_LINES) {
    out.removeChild(out.firstChild);
  }
  if ($("console-autoscroll").checked) {
    out.scrollTop = out.scrollHeight;
  }
}

listen("game://log", (event) => {
  const p = event.payload;
  if (!consoleInstanceId || p.instance_id !== consoleInstanceId) return;
  appendConsoleLine(p.line, p.error ? "err" : "");
});

listen("game://exit", (event) => {
  const p = event.payload;
  if (!consoleInstanceId || p.instance_id !== consoleInstanceId) return;
  appendConsoleLine(t("console_exited", { code: p.code }), "info");
  $("btn-console-kill").disabled = true;
});

$("btn-console-close").addEventListener("click", closeConsole);

$("btn-console-clear").addEventListener("click", () => {
  $("console-output").innerHTML = "";
});

$("btn-console-kill").addEventListener("click", async () => {
  if (!consoleInstanceId) return;
  try {
    await invoke("kill_instance", { id: consoleInstanceId });
    appendConsoleLine(t("console_killed"), "info");
    $("btn-console-kill").disabled = true;
  } catch (e) {
    appendConsoleLine(String(e), "err");
  }
});

// ---------------- modpack import ----------------
const PACK_EXTENSIONS = ["mrpack", "noriskpack", "nrc", "zip"];

async function importModpack(archivePath) {
  const fileName = archivePath.split(/[\\/]/).pop();
  setStatus("global-status", t("import_running", { name: fileName }));
  $("progress-wrap").classList.remove("hidden");

  try {
    const clientModBox = $("import-client-mod");
    const cosmeticaBox = $("import-cosmetica");
    const result = await invoke("import_modpack", {
      archivePath,
      parentPath: "",
      installClientMod: clientModBox ? clientModBox.checked : true,
      installCosmetica: cosmeticaBox ? cosmeticaBox.checked : false,
    });

    await refreshInstances();

    if (result.note) {
      setStatus(
        "global-status",
        t("import_partial", { name: result.instance.name }) + " " + result.note,
        "error"
      );
    } else {
      setStatus("global-status", t("import_done", { name: result.instance.name }), "success");
    }

    // The pack brought its own mods, but the loader profile still has to be built.
    await installInstance(result.instance);
  } catch (e) {
    setStatus("global-status", String(e), "error");
  } finally {
    $("progress-wrap").classList.add("hidden");
  }
}

$("btn-import-modpack").addEventListener("click", async () => {
  const selected = await open({
    multiple: false,
    title: t("import_choose"),
    filters: [{ name: "Modpack", extensions: PACK_EXTENSIONS }],
  });
  if (selected) importModpack(selected);
});

// Native drag and drop: the webview reports real file paths, which is what the
// Rust side needs - a browser File object would not carry one.
(async () => {
  try {
    const webview = window.__TAURI__.webview.getCurrentWebview();
    const zone = $("drop-zone");

    await webview.onDragDropEvent((event) => {
      const type = event.payload.type;

      if (type === "over" || type === "enter") {
        zone.classList.add("active");
        $("skin-drop-zone").classList.add("active");
        return;
      }
      if (type === "leave") {
        zone.classList.remove("active");
        $("skin-drop-zone").classList.remove("active");
        return;
      }
      if (type === "drop") {
        zone.classList.remove("active");
        $("skin-drop-zone").classList.remove("active");
        const paths = event.payload.paths || [];
        // A dropped .png is a skin; anything archive-shaped is a modpack.
        const skin = paths.find((p) => p.toLowerCase().endsWith(".png"));
        if (skin) {
          showView("skin");
          uploadSkinFile(skin);
          return;
        }

        const pack = paths.find((p) =>
          PACK_EXTENSIONS.some((ext) => p.toLowerCase().endsWith("." + ext))
        );
        if (pack) importModpack(pack);
      }
    });
  } catch (e) {
    // Drag and drop is a convenience - the button always works.
    console.warn("Drag and drop unavailable:", e);
  }
})();

// ---------------- loader versions ----------------
/// Fills a <select> with the loader builds available for a Minecraft version.
/// The first entry always means "let the launcher pick the newest stable one".
async function fillLoaderVersions(selectId, loader, mcVersion, preselect = "") {
  const select = $(selectId);
  select.innerHTML = "";

  const auto = document.createElement("option");
  auto.value = "";
  auto.textContent = t("loader_auto");
  select.appendChild(auto);

  if (!loader || loader === "vanilla" || !mcVersion) return;

  const loading = document.createElement("option");
  loading.textContent = t("loader_loading");
  loading.disabled = true;
  select.appendChild(loading);
  select.value = "";

  try {
    const versions = await invoke("list_loaders", {
      loaderName: loader,
      mcVersion: mcVersion,
    });
    select.innerHTML = "";
    select.appendChild(auto);

    if (versions.length === 0) {
      const none = document.createElement("option");
      none.textContent = t("loader_none");
      none.disabled = true;
      select.appendChild(none);
      return;
    }

    versions.forEach((v) => {
      const opt = document.createElement("option");
      opt.value = v.version;
      opt.textContent = v.stable ? v.version : `${v.version} (beta)`;
      select.appendChild(opt);
    });

    if (preselect && versions.some((v) => v.version === preselect)) {
      select.value = preselect;
    }
  } catch (e) {
    select.innerHTML = "";
    select.appendChild(auto);
    const err = document.createElement("option");
    err.textContent = t("loader_none");
    err.disabled = true;
    select.appendChild(err);
  }
}

function toggleLoaderVersionField(fieldId, loader) {
  $(fieldId).classList.toggle("hidden", !loader || loader === "vanilla");
}

// ---------------- edit instance ----------------
let editingInstance = null;

async function openEditModal(inst) {
  editingInstance = inst;
  $("edit-name").value = inst.name;
  $("edit-mc").value = `${inst.mc_version} — ${inst.loader}`;
  $("edit-ram").value = inst.ram_mb;
  $("edit-ram-value").textContent = inst.ram_mb + " MB";
  setStatus("edit-status", "");

  toggleLoaderVersionField("edit-loader-version-field", inst.loader);

  // The companion mod is a Fabric mod; Forge and NeoForge cannot load it.
  const modSupported = inst.loader === "fabric" || inst.loader === "quilt";
  $("edit-clientmod-field").classList.toggle("hidden", !modSupported);
  $("edit-client-mod").checked = inst.install_client_mod !== false;
  $("edit-backdrop").classList.remove("hidden");

  await fillLoaderVersions(
    "edit-loader-version",
    inst.loader,
    inst.mc_version,
    inst.loader_version
  );
}

$("edit-ram").addEventListener("input", (e) => {
  $("edit-ram-value").textContent = e.target.value + " MB";
});

$("btn-cancel-edit").addEventListener("click", () => {
  $("edit-backdrop").classList.add("hidden");
  editingInstance = null;
});

$("btn-confirm-edit").addEventListener("click", async () => {
  if (!editingInstance) return;
  try {
    const result = await invoke("update_instance", {
      id: editingInstance.id,
      name: $("edit-name").value.trim(),
      ramMb: parseInt($("edit-ram").value, 10),
      loaderVersion: $("edit-loader-version").value,
      installClientMod: $("edit-client-mod").checked,
    });

    $("edit-backdrop").classList.add("hidden");
    await refreshInstances();
    setStatus("global-status", t("edit_saved"), "success");

    // A new loader version means the profile has to be rebuilt.
    if (result.needs_reinstall) {
      await installInstance(result.instance);
    }
    editingInstance = null;
  } catch (e) {
    setStatus("edit-status", String(e), "error");
  }
});

// ---------------- create instance modal ----------------
function renderVersionOptions() {
  const showAll = $("new-snapshots").checked;
  const select = $("new-version");
  const previous = select.value;
  select.innerHTML = "";

  allVersions
    // NOTE: serde serialises this field as "type", not "kind"
    .filter((v) => (showAll ? true : v.type === "release"))
    .forEach((v) => {
      const opt = document.createElement("option");
      opt.value = v.id;
      opt.textContent = v.type === "release" ? v.id : `${v.id} (${v.type})`;
      select.appendChild(opt);
    });

  if (previous && [...select.options].some((o) => o.value === previous)) {
    select.value = previous;
  }
}

$("new-snapshots").addEventListener("change", () => {
  renderVersionOptions();
  updateHudCompatNote();
});


/// The HUD mod is a Fabric mod built against 26.2 only, so any other
/// combination silently gets an instance without it. Say so up front.
const HUD_MC_VERSION = "26.2";
const HUD_LOADERS = ["fabric", "quilt"];

function updateHudCompatNote() {
  const note = $("hud-compat-note");
  const version = $("new-version").value;
  const loader = $("new-loader").value;
  if (!version || !loader) {
    note.classList.add("hidden");
    return;
  }

  const supported = version === HUD_MC_VERSION && HUD_LOADERS.includes(loader);
  note.textContent = supported ? t("hud_supported") : t("hud_unsupported");
  note.className = "compat-note " + (supported ? "ok" : "warn");
}

async function refreshNewLoaderVersions() {
  const loader = $("new-loader").value;
  toggleLoaderVersionField("new-loader-version-field", loader);
  await fillLoaderVersions("new-loader-version", loader, $("new-version").value);
  updateHudCompatNote();
}

$("new-loader").addEventListener("change", refreshNewLoaderVersions);
$("new-version").addEventListener("change", refreshNewLoaderVersions);

$("btn-new-instance").addEventListener("click", () => {
  $("new-name").value = "";
  $("new-path").value = "";
  $("new-ram").value = config?.max_ram_mb ?? 4096;
  $("new-ram-value").textContent = $("new-ram").value + " MB";
  const cosmeticaBox = $("new-cosmetica");
  if (cosmeticaBox) cosmeticaBox.checked = true;
  const clientModBox = $("new-client-mod");
  if (clientModBox) clientModBox.checked = true;
  setStatus("create-status", "");
  $("modal-backdrop").classList.remove("hidden");
  refreshNewLoaderVersions();
});

$("btn-cancel-create").addEventListener("click", () => {
  $("modal-backdrop").classList.add("hidden");
});

$("new-ram").addEventListener("input", (e) => {
  $("new-ram-value").textContent = e.target.value + " MB";
});

$("btn-pick-instance-path").addEventListener("click", async () => {
  const selected = await open({ directory: true, multiple: false });
  if (selected) $("new-path").value = selected;
});

$("btn-confirm-create").addEventListener("click", async () => {
  const name = $("new-name").value.trim();
  if (!name) {
    setStatus("create-status", t("field_name"), "error");
    return;
  }
  try {
    const clientModBox = $("new-client-mod");
    const inst = await invoke("create_instance", {
      name,
      mcVersion: $("new-version").value,
      loaderName: $("new-loader").value,
      loaderVersion: $("new-loader-version").value,
      ramMb: parseInt($("new-ram").value, 10),
      parentPath: $("new-path").value,
      installClientMod: clientModBox ? clientModBox.checked : true,
    });
    const cosmeticaBox = $("new-cosmetica");
    const wantsCosmetica = cosmeticaBox ? cosmeticaBox.checked : false;

    $("modal-backdrop").classList.add("hidden");
    await refreshInstances();
    await installInstance(inst);

    // After the instance itself, so the mods folder exists and the loader is
    // known. Vanilla instances are skipped rather than failed: there is nowhere
    // for a mod to go, and refusing to create the instance over a checkbox
    // would be a poor trade.
    if (wantsCosmetica && $("new-loader").value !== "vanilla") {
      await installCosmetica(inst);
    }
  } catch (e) {
    setStatus("create-status", String(e), "error");
  }
});

// ---------------- account ----------------
function renderAccount() {
  if (account) {
    $("account-signed-in").classList.remove("hidden");
    $("account-signed-out").classList.add("hidden");
    $("account-username").textContent = account.username;
    $("account-name").textContent = account.username;
    $("account-badge").classList.toggle("hidden", !account.offline);

    // Offline profiles have no Mojang skin to look up
    if (account.offline) {
      $("account-skin").removeAttribute("src");
      $("account-skin").classList.add("placeholder");
      $("account-dot").classList.remove("online");
      $("account-dot").classList.add("offline");
    } else {
      $("account-skin").classList.remove("placeholder");
      $("account-skin").src = `https://crafatar.com/avatars/${account.uuid}?size=48&overlay`;
      $("account-dot").classList.add("online");
      $("account-dot").classList.remove("offline");
    }
  } else {
    $("account-signed-in").classList.add("hidden");
    $("account-signed-out").classList.remove("hidden");
    $("account-name").textContent = t("not_signed_in");
    $("account-dot").classList.remove("online", "offline");
    $("account-badge").classList.add("hidden");
  }
}

$("btn-signin").addEventListener("click", async () => {
  setStatus("account-status", "");
  try {
    pendingLogin = await invoke("start_login");
    $("login-url").textContent = pendingLogin.verification_uri;
    $("login-code").textContent = pendingLogin.user_code;
    $("login-flow").classList.remove("hidden");
    $("account-signed-out").classList.add("hidden");

    // Opens the browser right away so the user only has to paste the code
    shell.open(pendingLogin.verification_uri).catch(() => {});

    account = await invoke("complete_login", { info: pendingLogin });
    $("login-flow").classList.add("hidden");
    renderAccount();
    refreshAccounts();
    loadSkinProfile();
    setStatus("account-status", t("login_success"), "success");
  } catch (e) {
    $("login-flow").classList.add("hidden");
    $("account-signed-out").classList.remove("hidden");
    setStatus("account-status", String(e), "error");
  }
});

$("btn-offline-login").addEventListener("click", async () => {
  const name = $("offline-username").value.trim();
  setStatus("account-status", "");
  try {
    account = await invoke("login_offline", { username: name });
    renderAccount();
    setStatus("account-status", t("login_success"), "success");
  } catch (e) {
    setStatus("account-status", String(e), "error");
  }
});

$("offline-username").addEventListener("keydown", (e) => {
  if (e.key === "Enter") $("btn-offline-login").click();
});

$("btn-open-browser").addEventListener("click", () => {
  if (pendingLogin) shell.open(pendingLogin.verification_uri).catch(() => {});
});

$("btn-copy-code").addEventListener("click", async () => {
  if (!pendingLogin) return;
  try {
    await navigator.clipboard.writeText(pendingLogin.user_code);
    setStatus("account-status", t("copied"), "success");
  } catch {}
});

$("btn-signout").addEventListener("click", async () => {
  await invoke("logout");
  account = null;
  skinProfile = null;
  renderAccount();
  renderSkinView();
});


// ---------------- mods (Modrinth) ----------------
let installedCache = [];
let availableUpdates = [];
let currentType = "mod";
let selectedCategories = [];

// Modrinth's official tag lists per project type. There is no "pvp" category
// on Modrinth - typing "pvp" into the search box is the way to find those.
const CATEGORIES = {
  mod: [
    "adventure", "cursed", "decoration", "economy", "equipment", "food",
    "game-mechanics", "library", "magic", "management", "minigame", "mobs",
    "optimization", "social", "storage", "technology", "transportation",
    "utility", "worldgen",
  ],
  resourcepack: [
    "8x-", "16x", "32x", "48x", "64x", "128x", "256x", "512x+",
    "audio", "blocks", "combat", "decoration", "entities", "fonts", "gui",
    "items", "locale", "modded", "models", "realistic", "simplistic",
    "themed", "tweaks", "vanilla-like",
  ],
  shader: [
    "atmosphere", "bloom", "cartoon", "colored-lighting", "fantasy",
    "foliage", "path-tracing", "pbr", "realistic", "reflections",
    "semi-realistic", "shadows", "vanilla-like", "potato", "low", "medium",
    "high", "screenshot",
  ],
};

function currentModsInstance() {
  const id = $("mods-instance").value;
  return instances.find((i) => i.id === id) || null;
}

function renderModsInstanceOptions() {
  const select = $("mods-instance");
  const previous = select.value;
  select.innerHTML = "";
  instances.forEach((inst) => {
    const opt = document.createElement("option");
    opt.value = inst.id;
    opt.textContent = `${inst.name} — ${inst.mc_version} (${inst.loader})`;
    select.appendChild(opt);
  });
  if (previous && instances.some((i) => i.id === previous)) select.value = previous;
  updateModsPanel();
}

function updateModsPanel() {
  const inst = currentModsInstance();
  const warning = $("mods-warning");
  const panel = $("mods-panel");

  if (!inst) {
    warning.textContent = t("mods_no_instance");
    warning.classList.remove("hidden");
    panel.classList.add("hidden");
    return;
  }
  // Vanilla instances can still take resource packs, just not mods.
  if (inst.loader === "vanilla" && currentType === "mod") {
    warning.textContent = t("mods_vanilla_warning");
    warning.classList.remove("hidden");
    panel.classList.add("hidden");
    return;
  }
  warning.classList.add("hidden");
  panel.classList.remove("hidden");
  renderCategoryChips();
  updateTypeNote();
  loadInstalledMods();
  searchMods();
}

function updateTypeNote() {
  const note = $("type-note");
  if (currentType === "shader") note.textContent = t("shader_note");
  else if (currentType === "resourcepack") note.textContent = t("packs_note");
  else note.textContent = "";
}

function renderCategoryChips() {
  const row = $("category-chips");
  row.innerHTML = "";

  const all = document.createElement("button");
  all.className = "chip" + (selectedCategories.length === 0 ? " active" : "");
  all.textContent = t("cat_all");
  all.onclick = () => {
    selectedCategories = [];
    renderCategoryChips();
    searchMods();
  };
  row.appendChild(all);

  (CATEGORIES[currentType] || []).forEach((cat) => {
    const chip = document.createElement("button");
    chip.className = "chip" + (selectedCategories.includes(cat) ? " active" : "");
    chip.textContent = cat;
    chip.onclick = () => {
      if (selectedCategories.includes(cat)) {
        selectedCategories = selectedCategories.filter((c) => c !== cat);
      } else {
        selectedCategories.push(cat);
      }
      renderCategoryChips();
      searchMods();
    };
    row.appendChild(chip);
  });
}

document.querySelectorAll(".type-btn").forEach((btn) => {
  btn.addEventListener("click", () => {
    document.querySelectorAll(".type-btn").forEach((b) => b.classList.remove("active"));
    btn.classList.add("active");
    currentType = btn.dataset.type;
    selectedCategories = [];
    $("mods-query").value = "";
    updateModsPanel();
  });
});

$("mods-instance").addEventListener("change", () => {
  $("mods-results").innerHTML = "";
  availableUpdates = [];
  $("btn-update-all-mods").classList.add("hidden");
  updateModsPanel();
});

document.querySelectorAll(".tab").forEach((tab) => {
  tab.addEventListener("click", () => {
    document.querySelectorAll(".tab").forEach((x) => x.classList.remove("active"));
    document.querySelectorAll(".tab-panel").forEach((x) => x.classList.remove("active"));
    tab.classList.add("active");
    $("tab-" + tab.dataset.tab).classList.add("active");
    if (tab.dataset.tab === "installed") loadInstalledMods();
  });
});

function modCard(data, actions) {
  const card = document.createElement("div");
  card.className = "mod-card";
  card.innerHTML = `
    <img class="mod-icon" alt="" />
    <div class="mod-body">
      <div class="mod-title"></div>
      <div class="mod-desc"></div>
      <div class="mod-meta"></div>
    </div>
  `;
  const icon = card.querySelector(".mod-icon");
  if (data.icon_url) icon.src = data.icon_url;
  else icon.classList.add("placeholder");
  card.querySelector(".mod-title").textContent = data.title;
  card.querySelector(".mod-desc").textContent = data.description || "";
  card.querySelector(".mod-meta").textContent = data.meta || "";
  card.appendChild(actions);
  return card;
}

/// Runs with an empty query too - Modrinth then returns its popular listing,
/// so the browser shows something useful the moment it opens.
async function searchMods() {
  const inst = currentModsInstance();
  if (!inst) return;
  const query = $("mods-query").value.trim();
  const list = $("mods-results");
  list.innerHTML = `<p class="empty-note">${t("loading")}</p>`;
  setStatus("mods-status", "");

  try {
    const hits = await invoke("search_mods", {
      query,
      instanceId: inst.id,
      projectType: currentType,
      categories: selectedCategories,
      offset: 0,
    });

    list.innerHTML = "";
    if (hits.length === 0) {
      const p = document.createElement("p");
      p.className = "empty-note";
      p.textContent = t("mods_no_results");
      list.appendChild(p);
      return;
    }

    hits.forEach((hit) => {
      const already = installedCache.some((m) => m.project_id === hit.project_id);

      const actions = document.createElement("div");
      actions.className = "mod-actions";

      const addBtn = document.createElement("button");
      addBtn.className = already ? "btn secondary small" : "btn primary small";
      addBtn.textContent = already ? t("btn_added") : t("btn_add");
      addBtn.disabled = already;
      addBtn.onclick = () => addMod(hit, addBtn);
      actions.appendChild(addBtn);

      const verBtn = document.createElement("button");
      verBtn.className = "btn secondary small";
      verBtn.textContent = t("btn_versions");
      verBtn.onclick = () => openVersionPicker(hit);
      actions.appendChild(verBtn);

      list.appendChild(
        modCard(
          {
            title: hit.title,
            description: hit.description,
            icon_url: hit.icon_url,
            meta: `${hit.author} · ${hit.downloads.toLocaleString()} ${t("downloads")}`,
          },
          actions
        )
      );
    });
  } catch (e) {
    list.innerHTML = "";
    setStatus("mods-status", String(e), "error");
  }
}

$("btn-mods-search").addEventListener("click", searchMods);
$("mods-query").addEventListener("keydown", (e) => {
  if (e.key === "Enter") searchMods();
});

// ---------------- version picker ----------------
async function openVersionPicker(hit) {
  const inst = currentModsInstance();
  if (!inst) return;

  $("version-project").textContent = hit.title;
  $("version-list").innerHTML = `<p class="empty-note">${t("versions_loading")}</p>`;
  $("version-backdrop").classList.remove("hidden");

  try {
    const versions = await invoke("list_project_versions", {
      projectId: hit.project_id,
      instanceId: inst.id,
      projectType: currentType,
    });

    const list = $("version-list");
    list.innerHTML = "";

    if (versions.length === 0) {
      const p = document.createElement("p");
      p.className = "empty-note";
      p.textContent = t("versions_none");
      list.appendChild(p);
      return;
    }

    versions.forEach((v) => {
      const row = document.createElement("div");
      row.className = "version-row";

      const info = document.createElement("div");
      info.className = "version-info";

      const name = document.createElement("div");
      name.className = "version-name";
      name.textContent = v.version_number;

      const badge = document.createElement("span");
      badge.className = "vtype " + v.version_type;
      badge.textContent = v.version_type;
      name.appendChild(badge);

      const meta = document.createElement("div");
      meta.className = "version-meta";
      const loaderPart = v.loaders.length ? v.loaders.join(", ") + " · " : "";
      meta.textContent = `${loaderPart}${t("version_compat", {
        versions: v.game_versions.join(", "),
      })} · ${v.downloads.toLocaleString()} ${t("downloads")}`;

      info.appendChild(name);
      info.appendChild(meta);
      row.appendChild(info);

      const btn = document.createElement("button");
      btn.className = "btn primary small";
      btn.textContent = t("btn_install_version");
      btn.onclick = async () => {
        btn.disabled = true;
        setStatus("mods-status", t("mods_installing", { name: hit.title }));
        try {
          await invoke("install_project_version", {
            instanceId: inst.id,
            projectId: hit.project_id,
            versionId: v.id,
            projectType: currentType,
          });
          $("version-backdrop").classList.add("hidden");
          setStatus("mods-status", t("mods_installed_msg", { count: 1 }), "success");
          await loadInstalledMods();
          await searchMods();
        } catch (e) {
          btn.disabled = false;
          setStatus("mods-status", String(e), "error");
        }
      };
      row.appendChild(btn);
      list.appendChild(row);
    });
  } catch (e) {
    $("version-list").innerHTML = "";
    setStatus("mods-status", String(e), "error");
    $("version-backdrop").classList.add("hidden");
  }
}

$("btn-close-versions").addEventListener("click", () => {
  $("version-backdrop").classList.add("hidden");
});

async function addMod(hit, btn) {
  const inst = currentModsInstance();
  if (!inst) return;
  btn.disabled = true;
  setStatus("mods-status", t("mods_installing", { name: hit.title }));
  try {
    const added = await invoke("install_mod", {
      instanceId: inst.id,
      projectId: hit.project_id,
      projectType: currentType,
    });
    btn.textContent = t("btn_added");
    btn.className = "btn secondary small";
    setStatus("mods-status", t("mods_installed_msg", { count: added.length }), "success");
    await loadInstalledMods();
  } catch (e) {
    btn.disabled = false;
    setStatus("mods-status", String(e), "error");
  }
}

/** Free-text filter for the installed list. Kept out of installedCache so the
 *  browse tab still knows about everything that is installed. */
let installedFilter = "";

function renderInstalledMods() {
  const inst = currentModsInstance();
  const list = $("mods-installed-list");
  list.innerHTML = "";

  const needle = installedFilter.trim().toLowerCase();
  const shown = needle
    ? installedCache.filter(
        (m) =>
          m.title.toLowerCase().includes(needle) ||
          m.filename.toLowerCase().includes(needle)
      )
    : installedCache;

  const count = $("mods-installed-count");
  if (count) {
    count.textContent = needle
      ? t("installed_count_filtered", {
          shown: shown.length,
          total: installedCache.length,
        })
      : t("installed_count", { total: installedCache.length });
  }

  if (installedCache.length === 0) {
    const p = document.createElement("p");
    p.className = "empty-note";
    p.textContent = t("mods_none_installed");
    list.appendChild(p);
    return;
  }
  if (shown.length === 0) {
    const p = document.createElement("p");
    p.className = "empty-note";
    p.textContent = t("mods_no_results");
    list.appendChild(p);
    return;
  }

  shown.forEach((m) => {
    const pending = availableUpdates.find(
      (u) => m.project_id && u.project_id === m.project_id
    );

    const actions = document.createElement("div");
    actions.className = "mod-actions";

    if (pending) {
      const upd = document.createElement("button");
      upd.className = "btn primary small";
      upd.textContent = t("btn_update");
      upd.onclick = () => updateOne(pending, upd);
      actions.appendChild(upd);
    }

    // Disabling renames the file to <name>.disabled, so the loader ignores it
    // while the file - and any config it wrote - stays put.
    const toggle = document.createElement("button");
    toggle.className = "btn secondary small";
    toggle.textContent = m.enabled ? t("btn_disable") : t("btn_enable");
    toggle.onclick = async () => {
      toggle.disabled = true;
      try {
        await invoke("set_mod_enabled", {
          instanceId: inst.id,
          filename: m.filename,
          projectType: currentType,
          enabled: !m.enabled,
        });
        setStatus(
          "mods-status",
          m.enabled
            ? t("mod_disabled", { name: m.title })
            : t("mod_enabled", { name: m.title }),
          "success"
        );
        await loadInstalledMods();
      } catch (e) {
        toggle.disabled = false;
        setStatus("mods-status", String(e), "error");
      }
    };
    actions.appendChild(toggle);

    const btn = document.createElement("button");
    btn.className = "btn danger small";
    btn.textContent = t("btn_remove");
    btn.onclick = async () => {
      btn.disabled = true;
      // The old version had no error handling at all, so a locked jar - the
      // usual case being the game still running on Windows - looked like a
      // dead button.
      try {
        await invoke("remove_mod", {
          instanceId: inst.id,
          filename: m.filename,
          projectType: currentType,
        });
        availableUpdates = availableUpdates.filter(
          (u) => u.project_id !== m.project_id
        );
        setStatus("mods-status", t("mod_removed", { name: m.title }), "success");
        await loadInstalledMods();
        await searchMods();
      } catch (e) {
        btn.disabled = false;
        setStatus("mods-status", String(e), "error");
      }
    };
    actions.appendChild(btn);

    const meta = pending
      ? t("update_arrow", { old: m.version_number, new: pending.new_version })
      : m.version_number === "manual"
      ? t("mods_manual")
      : m.version_number;

    const card = modCard(
      {
        title: m.title,
        description: m.filename,
        icon_url: m.icon_url,
        meta: m.enabled ? meta : `${meta} · ${t("mod_is_disabled")}`,
      },
      actions
    );
    if (pending) card.classList.add("has-update");
    if (!m.enabled) card.classList.add("is-disabled");
    list.appendChild(card);
  });
}

async function loadInstalledMods() {
  const inst = currentModsInstance();
  if (!inst) return;
  try {
    installedCache = await invoke("list_installed_mods", {
      instanceId: inst.id,
      projectType: currentType,
    });
    renderInstalledMods();
  } catch (e) {
    setStatus("mods-status", String(e), "error");
  }
}

$("mods-installed-query").addEventListener("input", (e) => {
  installedFilter = e.target.value;
  renderInstalledMods();
});

$("btn-fix-deps").addEventListener("click", async () => {
  const inst = currentModsInstance();
  if (!inst) return;
  $("btn-fix-deps").disabled = true;
  setStatus("mods-status", t("deps_running"));
  try {
    const added = await invoke("install_missing_dependencies", {
      instanceId: inst.id,
    });
    setStatus(
      "mods-status",
      added.length ? t("deps_added", { count: added.length }) : t("deps_none"),
      "success"
    );
    await loadInstalledMods();
  } catch (e) {
    setStatus("mods-status", String(e), "error");
  } finally {
    $("btn-fix-deps").disabled = false;
  }
});

async function checkModUpdates() {
  const inst = currentModsInstance();
  if (!inst) return;
  setStatus("mods-status", t("mods_checking"));
  try {
    availableUpdates = await invoke("check_mod_updates", { instanceId: inst.id });
    if (availableUpdates.length === 0) {
      setStatus("mods-status", t("mods_no_updates"), "success");
      $("btn-update-all-mods").classList.add("hidden");
    } else {
      setStatus("mods-status", t("mods_updates_found", { count: availableUpdates.length }));
      $("btn-update-all-mods").classList.remove("hidden");
    }
    await loadInstalledMods();
  } catch (e) {
    setStatus("mods-status", String(e), "error");
  }
}

async function updateOne(pending, btn) {
  const inst = currentModsInstance();
  if (!inst) return;
  btn.disabled = true;
  setStatus("mods-status", t("mods_updating", { name: pending.title }));
  try {
    await invoke("update_mod", { instanceId: inst.id, projectId: pending.project_id });
    availableUpdates = availableUpdates.filter((u) => u.project_id !== pending.project_id);
    if (availableUpdates.length === 0) $("btn-update-all-mods").classList.add("hidden");
    setStatus("mods-status", t("mods_updated", { count: 1 }), "success");
    await loadInstalledMods();
  } catch (e) {
    btn.disabled = false;
    setStatus("mods-status", String(e), "error");
  }
}

$("btn-check-mod-updates").addEventListener("click", checkModUpdates);

$("btn-repair-mods").addEventListener("click", async () => {
  const inst = currentModsInstance();
  if (!inst) return;
  $("btn-repair-mods").disabled = true;
  setStatus("mods-status", t("repair_running"));
  try {
    const report = await invoke("repair_instance_mods", { instanceId: inst.id });
    const clean = report.replaced.length === 0 && report.incompatible.length === 0;
    setStatus(
      "mods-status",
      clean
        ? t("repair_clean", { checked: report.checked })
        : t("repair_done", {
            checked: report.checked,
            replaced: report.replaced.length,
            incompatible: report.incompatible.length,
          }),
      report.incompatible.length > 0 ? "error" : "success"
    );
    availableUpdates = [];
    await loadInstalledMods();
  } catch (e) {
    setStatus("mods-status", String(e), "error");
  } finally {
    $("btn-repair-mods").disabled = false;
  }
});

$("btn-update-all-mods").addEventListener("click", async () => {
  const inst = currentModsInstance();
  if (!inst) return;
  $("btn-update-all-mods").disabled = true;
  try {
    const count = await invoke("update_all_mods", { instanceId: inst.id });
    availableUpdates = [];
    $("btn-update-all-mods").classList.add("hidden");
    setStatus("mods-status", t("mods_updated", { count }), "success");
    await loadInstalledMods();
  } catch (e) {
    setStatus("mods-status", String(e), "error");
  } finally {
    $("btn-update-all-mods").disabled = false;
  }
});

// ---------------- settings ----------------
$("btn-pick-path").addEventListener("click", async () => {
  const selected = await open({ directory: true, multiple: false });
  if (!selected) return;
  config = await invoke("set_install_path", { path: selected });
  $("install-path").value = config.install_path;
});

$("ram-slider").addEventListener("input", (e) => {
  $("ram-value").textContent = e.target.value + " MB";
});

$("btn-save-settings").addEventListener("click", async () => {
  try {
    config = await invoke("set_settings", {
      maxRamMb: parseInt($("ram-slider").value, 10),
      customJavaPath: $("java-path").value.trim(),
      language: $("language-select").value,
      checkUpdates: $("check-updates").checked,
      liveLogs: $("live-logs").checked,
    });
    setLanguage(config.language);
    applyTranslations();
    renderInstances();
    renderAccount();
    renderModsInstanceOptions();
    setStatus("settings-status", t("saved"), "success");
  } catch (e) {
    setStatus("settings-status", String(e), "error");
  }
});

// ---------------- progress ----------------
listen("install://progress", (event) => {
  const p = event.payload;
  $("progress-wrap").classList.remove("hidden");
  const pct = p.total > 0 ? Math.round((p.current / p.total) * 100) : 0;
  $("progress-label").textContent = `${t("stage_" + p.stage)} ${pct}% — ${p.file}`;
  $("progress-fill").style.width = pct + "%";
  if (p.stage === "done") setTimeout(() => $("progress-wrap").classList.add("hidden"), 1500);
});

// ---------------- updates ----------------
async function checkUpdate() {
  try {
    const info = await invoke("check_update");
    $("version-label").textContent = "v" + info.current_version;
    if (info.update_available) {
      $("update-text").textContent = t("update_text", {
        v: info.latest_version,
        c: info.current_version,
      });
      $("update-banner").classList.remove("hidden");
      $("btn-update-download").onclick = () => installUpdate(info);
      $("btn-update-later").onclick = () => $("update-banner").classList.add("hidden");
    }
  } catch {
    // A failed update check must never block playing.
  }
}

/**
 * Downloads the new installer and offers to start it.
 *
 * The launcher cannot overwrite itself while running, so the installer is
 * fetched first and only then started - at which point this window closes and
 * the installer takes over. Anything already playing keeps playing: the game
 * runs in its own process and does not care what happens to the launcher.
 */
async function installUpdate(info) {
  var button = $("btn-update-download");
  var text = $("update-text");
  var original = text ? text.textContent : "";

  if (button) button.disabled = true;
  if (text) text.textContent = t("update_downloading");

  try {
    var path = await invoke("download_update");
    if (text) text.textContent = t("update_ready");
    await shell.open(path);
  } catch (e) {
    if (text) text.textContent = t("update_failed") + " " + String(e);
    if (info && info.release_url) {
      shell.open(info.release_url).catch(function () {});
    }
  } finally {
    if (button) button.disabled = false;
    if (text && original && text.textContent === t("update_ready")) {
      text.textContent = original;
    }
  }
}

// ---------------- init ----------------
async function init() {
  config = await invoke("get_config");
  setLanguage(config.language || "en");
  applyTranslations();

  $("install-path").value = config.install_path;
  $("java-path").value = config.custom_java_path || "";
  $("ram-slider").value = config.max_ram_mb;
  $("ram-value").textContent = config.max_ram_mb + " MB";
  $("language-select").value = config.language || "en";
  $("check-updates").checked = config.check_updates !== false;
  $("live-logs").checked = config.live_logs === true;

  account = await invoke("get_account");
  renderAccount();
  await refreshAccounts();
  loadSkinProfile();

  await refreshInstances();

  try {
    const data = await invoke("list_versions");
    allVersions = data.versions;
    renderVersionOptions();
    $("new-version").value = data.latest_release;
  } catch (e) {
    setStatus("global-status", String(e), "error");
  }

  checkUpdate();
}

init();
