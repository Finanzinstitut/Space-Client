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

$("new-snapshots").addEventListener("change", renderVersionOptions);

async function refreshNewLoaderVersions() {
  const loader = $("new-loader").value;
  toggleLoaderVersionField("new-loader-version-field", loader);
  await fillLoaderVersions("new-loader-version", loader, $("new-version").value);
}

$("new-loader").addEventListener("change", refreshNewLoaderVersions);
$("new-version").addEventListener("change", refreshNewLoaderVersions);

$("btn-new-instance").addEventListener("click", () => {
  $("new-name").value = "";
  $("new-path").value = "";
  $("new-ram").value = config?.max_ram_mb ?? 4096;
  $("new-ram-value").textContent = $("new-ram").value + " MB";
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
    const inst = await invoke("create_instance", {
      name,
      mcVersion: $("new-version").value,
      loaderName: $("new-loader").value,
      loaderVersion: $("new-loader-version").value,
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
  renderAccount();
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

async function loadInstalledMods() {
  const inst = currentModsInstance();
  if (!inst) return;
  const list = $("mods-installed-list");
  try {
    installedCache = await invoke("list_installed_mods", {
      instanceId: inst.id,
      projectType: currentType,
    });
    list.innerHTML = "";

    if (installedCache.length === 0) {
      const p = document.createElement("p");
      p.className = "empty-note";
      p.textContent = t("mods_none_installed");
      list.appendChild(p);
      return;
    }

    installedCache.forEach((m) => {
      const pending = availableUpdates.find((u) => u.project_id === m.project_id);

      const actions = document.createElement("div");
      actions.className = "mod-actions";

      if (pending) {
        const upd = document.createElement("button");
        upd.className = "btn primary small";
        upd.textContent = t("btn_update");
        upd.onclick = () => updateOne(pending, upd);
        actions.appendChild(upd);
      }

      const btn = document.createElement("button");
      btn.className = "btn danger small";
      btn.textContent = t("btn_remove");
      btn.onclick = async () => {
        await invoke("remove_mod", {
          instanceId: inst.id,
          filename: m.filename,
          projectType: currentType,
        });
        availableUpdates = availableUpdates.filter((u) => u.project_id !== m.project_id);
        await loadInstalledMods();
        await searchMods();
      };
      actions.appendChild(btn);

      const meta = pending
        ? t("update_arrow", { old: m.version_number, new: pending.new_version })
        : m.version_number === "manual"
        ? t("mods_manual")
        : m.version_number;

      const card = modCard(
        { title: m.title, description: m.filename, icon_url: "", meta },
        actions
      );
      if (pending) card.classList.add("has-update");
      list.appendChild(card);
    });
  } catch (e) {
    setStatus("mods-status", String(e), "error");
  }
}

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
