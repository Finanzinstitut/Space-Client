import { t, setLanguage, applyTranslations } from "./i18n.js";

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
    folderBtn.className = "btn secondary small";
    folderBtn.textContent = t("btn_folder");
    folderBtn.onclick = async () => {
      try {
        const path = await invoke("open_instance_folder", { id: inst.id });
        await shell.open(path);
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
    actions.appendChild(delBtn);

    list.appendChild(card);
  });
}

async function refreshInstances() {
  instances = await invoke("list_instances");
  renderInstances();
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
  try {
    await invoke("launch_instance", { id: inst.id });
    setStatus("global-status", t("launched", { name: inst.name }), "success");
  } catch (e) {
    setStatus("global-status", String(e), "error");
  }
}

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

$("new-snapshots").addEventListener("change", renderVersionOptions);

$("btn-new-instance").addEventListener("click", () => {
  $("new-name").value = "";
  $("new-path").value = "";
  $("new-ram").value = config?.max_ram_mb ?? 4096;
  $("new-ram-value").textContent = $("new-ram").value + " MB";
  setStatus("create-status", "");
  $("modal-backdrop").classList.remove("hidden");
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
    const inst = await invoke("create_instance", {
      name,
      mcVersion: $("new-version").value,
      loaderName: $("new-loader").value,
      ramMb: parseInt($("new-ram").value, 10),
      parentPath: $("new-path").value,
    });
    $("modal-backdrop").classList.add("hidden");
    await refreshInstances();
    await installInstance(inst);
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
    $("account-skin").src = `https://crafatar.com/avatars/${account.uuid}?size=48&overlay`;
    $("account-name").textContent = account.username;
    $("account-dot").classList.add("online");
  } else {
    $("account-signed-in").classList.add("hidden");
    $("account-signed-out").classList.remove("hidden");
    $("account-name").textContent = t("not_signed_in");
    $("account-dot").classList.remove("online");
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
    setStatus("account-status", t("login_success"), "success");
  } catch (e) {
    $("login-flow").classList.add("hidden");
    $("account-signed-out").classList.remove("hidden");
    setStatus("account-status", String(e), "error");
  }
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
  renderAccount();
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
    });
    setLanguage(config.language);
    applyTranslations();
    renderInstances();
    renderAccount();
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
      $("btn-update-download").onclick = () => shell.open(info.release_url).catch(() => {});
      $("btn-update-later").onclick = () => $("update-banner").classList.add("hidden");
    }
  } catch {
    // A failed update check must never block playing.
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

  account = await invoke("get_account");
  renderAccount();

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
