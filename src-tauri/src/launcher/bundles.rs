//! Fertige Zusammenstellungen: ein Knopf, mehrere Sachen.
//!
//! Der Mod-Browser installiert eine Sache, die jemand gesucht hat. Hier geht es
//! um das Gegenteil - ein paar Dinge, die zusammengehoeren, ohne dass man
//! wissen muss, wie sie heissen oder wo sie liegen.
//!
//! Zwei Quellen, weil die Sachen wirklich verschieden sind:
//!
//! * `Modrinth` laeuft ueber den vorhandenen Weg samt Abhaengigkeiten. Er sagt
//!   von sich aus Bescheid, wenn es fuer diese Minecraft-Version nichts gibt.
//! * `Direct` ist eine Datei an einer Adresse, fuer das, was nicht auf
//!   Modrinth liegt. Hier steht die Liste der Minecraft-Versionen im Eintrag,
//!   denn eine Datei kann nicht gefragt werden, wozu sie passt.
//!
//! Es gab kurz einen dritten Weg ueber CurseForge, fuer No Soundcap. Der Mod
//! liegt dort nicht, also ist er auch eine Datei geworden und der Weg wieder
//! weg - ein Zweig, den nichts nimmt, ist keine Vorsorge, sondern Ballast.
//!
//! Nichts hier laedt etwas, das nicht in dieser Datei steht. Die Adressen sind
//! fest verdrahtet, damit die Oberflaeche nur einen Namen schickt und nicht
//! eine Adresse - sonst waere das hier ein Befehl, der alles herunterlaedt,
//! was man ihm nennt, mit einem Paketnamen davor.

use serde::{Deserialize, Serialize};
use tauri::AppHandle;

use crate::launcher::{instance, mods};

#[derive(Clone, Copy, PartialEq)]
enum Source {
    Modrinth,
    Direct,
}

struct Item {
    name: &'static str,
    source: Source,
    /// Modrinth-Kuerzel, CurseForge-Kennung oder Adresse.
    id: &'static str,
    /// "mod" | "resourcepack" | "shader"
    project_type: &'static str,
    /// Nur fuer `Direct`: leer heisst "passt ueberall".
    ///
    /// "26.2" heisst genau diese Version. "26.2.x" heisst 26.2 und alles
    /// darunter - das ist der Unterschied zwischen "minecraft": "26.2" und
    /// "~26.2" in einer fabric.mod.json, und wer ihn hier einebnet, legt
    /// entweder eine Datei hin, die Fabric ablehnt, oder verweigert eine, die
    /// laufen wuerde.
    mc_versions: &'static [&'static str],
}

pub struct Bundle {
    pub id: &'static str,
    /// Solange das gesetzt ist, wird nichts installiert und der Text gesagt.
    pub blocked: Option<&'static str>,

    /// Woran das ganze Paket haengt, nicht der einzelne Eintrag.
    ///
    /// Die Eintraege pruefen sich ohnehin selbst, und bei falscher Instanz
    /// kaeme dabei dreimal dieselbe Absage heraus - untereinander, jede mit
    /// ihrem eigenen Grund, als waeren es drei Probleme. Ist es aber nicht:
    /// es ist die Instanz. Also wird einmal vorne gefragt und einmal vorne
    /// geantwortet, und die Oberflaeche kann es schon sagen, bevor jemand auf
    /// Installieren drueckt.
    ///
    /// Leer heisst: keine Einschraenkung.
    pub requires_mc: &'static [&'static str],
    pub requires_loader: &'static [&'static str],

    items: &'static [Item],
}

/// Wo die beiden Dateien liegen, die es weder auf Modrinth noch auf
/// CurseForge gibt. Dieselbe Stelle, die auch den Installer ausliefert.
const HOST: &str = "https://finanzinstitut.github.io/space-client-website/download";

pub const BUNDLES: &[Bundle] = &[
    Bundle {
        id: "umbaria",
        blocked: None,
        // Ein Ressourcenpaket braucht keinen Loader, und ein falsches Format
        // meckert das Spiel selbst an, statt abzustuerzen.
        requires_mc: &[],
        requires_loader: &[],
        items: &[Item {
            name: "Vanilla PvP v5",
            source: Source::Direct,
            id: "packs/Vanilla_PvP_v5.zip",
            project_type: "resourcepack",
            // Das Paket nennt pack_format 84 bis 97, also alles ab 26.2.
            // Eine leere Liste, weil ein Ressourcenpaket mit falschem Format
            // im Spiel angemeckert wird und nicht abstuerzt - das darf das
            // Spiel selbst sagen.
            mc_versions: &[],
        }],
    },
    Bundle {
        id: "finanzinstitut",
        blocked: None,
        // Alle drei sind Fabric-Mods fuer 26.2. Auf allem anderen gibt es
        // nichts zu installieren, also wird es gar nicht erst versucht.
        // "26.2.x" und nicht "26.2": eine 26.2.1 ist immer noch 26.2, und
        // jemanden dort auszusperren waere ueberraschend.
        requires_mc: &["26.2.x"],
        requires_loader: &["fabric"],
        items: &[
            Item {
                name: "Low Health Warning",
                source: Source::Modrinth,
                id: "low-health-warning-finanzinstitut",
                project_type: "mod",
                mc_versions: &[],
            },
            Item {
                name: "No Soundcap",
                source: Source::Direct,
                id: "mods/no-soundcap-1.0.0.jar",
                project_type: "mod",
                // Die fabric.mod.json sagt "~26.2", also 26.2 und dessen
                // Unterversionen - daher die Familie und nicht die eine Zahl.
                mc_versions: &["26.2.x"],
            },
            Item {
                name: "PvP Item Highlighter",
                source: Source::Direct,
                id: "mods/pvp-item-highlighter-1.0.0.jar",
                project_type: "mod",
                // Steht so in der fabric.mod.json des Jars. Auf einer anderen
                // Version wuerde Fabric beim Start aussteigen, also wird hier
                // gesagt, dass es nicht passt, statt es hinzulegen.
                mc_versions: &["26.2"],
            },
        ],
    },
    Bundle {
        id: "doktorsam",
        blocked: Some("Currently not available"),
        requires_mc: &[],
        requires_loader: &[],
        items: &[
            Item {
                name: "Mace PvP Perfected",
                source: Source::Modrinth,
                id: "mace-pvp-perfected-orange",
                project_type: "resourcepack",
                mc_versions: &[],
            },
            Item {
                name: "Low Food",
                source: Source::Modrinth,
                id: "small-food",
                project_type: "resourcepack",
                mc_versions: &[],
            },
            Item {
                name: "No Wind Charge Particles",
                source: Source::Modrinth,
                id: "no-gust-particles",
                project_type: "resourcepack",
                mc_versions: &[],
            },
            Item {
                name: "Better Wind Charge Particles",
                source: Source::Modrinth,
                id: "transparent-gust-particles",
                project_type: "resourcepack",
                mc_versions: &[],
            },
        ],
    },
];

#[derive(Serialize, Deserialize, Clone)]
pub struct ItemResult {
    pub name: String,
    /// "installed" | "incompatible" | "failed"
    pub outcome: String,
    pub detail: String,
}

#[derive(Serialize, Deserialize, Clone, Default)]
pub struct BundleReport {
    pub installed: u32,
    pub items: Vec<ItemResult>,
}

fn find(id: &str) -> Option<&'static Bundle> {
    BUNDLES.iter().find(|b| b.id == id)
}

#[derive(Serialize, Deserialize, Clone, Default)]
pub struct BundleInfo {
    pub items: Vec<String>,
    /// Warum es hier nicht geht, oder leer. Vom Fenster gelesen, bevor
    /// jemand zusagt - eine Absage, die erst nach dem Klick kommt, ist eine
    /// Absage, die man sich haette sparen koennen.
    pub blocked: String,
}

/// Was in einem Paket steckt und ob es in diese Instanz passt.
///
/// Die Instanz wird mitgegeben, weil die Antwort von ihr abhaengt: dasselbe
/// Paket geht in die eine und nicht in die andere, und das Fenster hat oben
/// eine Auswahl, die sich waehrenddessen aendern kann.
pub fn info(id: &str, instance_id: &str) -> BundleInfo {
    let Some(bundle) = find(id) else {
        return BundleInfo::default();
    };

    let mut out = BundleInfo {
        items: bundle.items.iter().map(|i| i.name.to_string()).collect(),
        blocked: bundle.blocked.unwrap_or("").to_string(),
    };

    if out.blocked.is_empty() {
        if let Some(inst) = instance::get(instance_id) {
            if let Err(reason) = fits(bundle, &inst) {
                out.blocked = reason;
            }
        }
    }
    out
}

/// Ob ein Paket in eine Instanz gehoert, und wenn nicht, warum.
fn fits(bundle: &Bundle, inst: &instance::Instance) -> Result<(), String> {
    let mc_ok = bundle.requires_mc.is_empty()
        || bundle
            .requires_mc
            .iter()
            .any(|want| version_fits(want, &inst.mc_version));

    let loader_ok = bundle.requires_loader.is_empty()
        || bundle
            .requires_loader
            .iter()
            .any(|want| want.eq_ignore_ascii_case(&inst.loader));

    if mc_ok && loader_ok {
        return Ok(());
    }

    // Beide Anforderungen in einem Satz, und dahinter, was die Instanz
    // tatsaechlich ist. Nur "geht nicht" zu sagen laesst jemanden raten,
    // welche der beiden Halften daneben liegt.
    let wants = match (bundle.requires_mc.first(), bundle.requires_loader.first()) {
        (Some(mc), Some(loader)) => format!(
            "Minecraft {} mit {}",
            mc.trim_end_matches(".x"),
            capitalise(loader)
        ),
        (Some(mc), None) => format!("Minecraft {}", mc.trim_end_matches(".x")),
        (None, Some(loader)) => capitalise(loader),
        (None, None) => String::new(),
    };

    Err(format!(
        "Geht nur in einer Instanz mit {} — diese ist {} ({}).",
        wants,
        inst.mc_version,
        capitalise(&inst.loader)
    ))
}

fn capitalise(word: &str) -> String {
    let mut chars = word.chars();
    match chars.next() {
        Some(first) => first.to_uppercase().collect::<String>() + chars.as_str(),
        None => String::new(),
    }
}

pub async fn install(
    app: &AppHandle,
    instance_id: String,
    bundle_id: String,
) -> anyhow::Result<BundleReport> {
    let bundle = find(&bundle_id).ok_or_else(|| anyhow::anyhow!("Unknown bundle"))?;

    // Der Riegel sitzt hier und nicht nur am ausgegrauten Knopf. Ein Knopf,
    // der nicht gedrueckt werden kann, ist eine Bitte an die Oberflaeche;
    // dieser Zweig ist die Zusage.
    if let Some(reason) = bundle.blocked {
        anyhow::bail!("{}", reason);
    }

    let inst =
        instance::get(&instance_id).ok_or_else(|| anyhow::anyhow!("Instance not found"))?;

    // Auch hier und nicht nur im Fenster. Ein ausgegrauter Knopf ist eine
    // Bitte an die Oberflaeche; dieser Zweig ist die Zusage.
    if let Err(reason) = fits(bundle, &inst) {
        anyhow::bail!("{}", reason);
    }

    let mut report = BundleReport::default();

    for item in bundle.items {
        let result = install_one(app, &inst, item).await;
        if result.outcome == "installed" {
            report.installed += 1;
        }
        report.items.push(result);
    }

    Ok(report)
}

async fn install_one(
    app: &AppHandle,
    inst: &instance::Instance,
    item: &Item,
) -> ItemResult {
    let fits = item.mc_versions.is_empty()
        || item
            .mc_versions
            .iter()
            .any(|want| version_fits(want, &inst.mc_version));

    if !fits {
        return ItemResult {
            name: item.name.into(),
            outcome: "incompatible".into(),
            detail: format!(
                "Needs Minecraft {}, this instance is {}",
                item.mc_versions.join(" or "),
                inst.mc_version
            ),
        };
    }

    if item.project_type == "mod" && inst.loader == "vanilla" {
        return ItemResult {
            name: item.name.into(),
            outcome: "incompatible".into(),
            detail: "This instance has no mod loader".into(),
        };
    }

    let outcome = match item.source {
        Source::Modrinth => mods::install_mod(
            app,
            inst.id.clone(),
            item.id.to_string(),
            item.project_type.to_string(),
        )
        .await
        .map(|_| ()),
        Source::Direct => install_direct(inst, item).await,
    };

    match outcome {
        Ok(()) => ItemResult {
            name: item.name.into(),
            outcome: "installed".into(),
            detail: String::new(),
        },
        Err(error) => {
            let text = error.to_string();
            // Der Modrinth-Weg sagt "Nothing available for Minecraft x" -
            // das ist keine Stoerung, sondern die Antwort auf die Frage.
            let missing = text.contains("Nothing available")
                || text.contains("No file for")
                || text.contains("no file");
            ItemResult {
                name: item.name.into(),
                outcome: if missing { "incompatible" } else { "failed" }.into(),
                detail: text,
            }
        }
    }
}

/// Ob eine Instanz-Version zu einem Eintrag passt.
fn version_fits(want: &str, have: &str) -> bool {
    match want.strip_suffix(".x") {
        Some(base) => have == base || have.starts_with(&format!("{}.", base)),
        None => want == have,
    }
}

async fn install_direct(inst: &instance::Instance, item: &Item) -> anyhow::Result<()> {
    let url = format!("{}/{}", HOST, item.id);
    let name = item
        .id
        .rsplit('/')
        .next()
        .ok_or_else(|| anyhow::anyhow!("Bad file name"))?;

    let response = reqwest::Client::new()
        .get(&url)
        .header("User-Agent", "SpaceClientLauncher")
        .send()
        .await?;

    if !response.status().is_success() {
        anyhow::bail!("Download failed: HTTP {}", response.status());
    }
    let bytes = response.bytes().await?;

    // Eine Antwort, die keine Datei ist, waere sonst eine Fehlerseite unter
    // einem Namen, der nach Mod aussieht - und die faende man erst, wenn das
    // Spiel nicht mehr startet.
    if bytes.len() < 1024 || &bytes[..2] != b"PK" {
        anyhow::bail!("What came back is not an archive");
    }

    let folder = inst.content_dir(item.project_type);
    std::fs::create_dir_all(&folder)?;
    std::fs::write(folder.join(name), &bytes)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exact_means_exact() {
        // "minecraft": "26.2" in einer fabric.mod.json - 26.2.1 lehnt Fabric ab.
        assert!(version_fits("26.2", "26.2"));
        assert!(!version_fits("26.2", "26.2.1"));
        assert!(!version_fits("26.2", "26.1"));
        assert!(!version_fits("26.2", "1.21.4"));
    }

    #[test]
    fn family_includes_the_base_and_its_patches() {
        // "~26.2" - die Version selbst gehoert dazu, nicht nur ihre Nachkommen.
        assert!(version_fits("26.2.x", "26.2"));
        assert!(version_fits("26.2.x", "26.2.1"));
        assert!(version_fits("26.2.x", "26.2.10"));
        assert!(!version_fits("26.2.x", "26.1"));
        assert!(!version_fits("26.2.x", "26.21"));
    }

    #[test]
    fn every_bundle_is_reachable_and_whole() {
        for bundle in BUNDLES {
            assert!(!bundle.items.is_empty(), "{} ist leer", bundle.id);
            assert!(!info(bundle.id, "gibt-es-nicht").items.is_empty());
            for item in bundle.items {
                assert!(!item.name.is_empty());
                assert!(!item.id.is_empty());
                // Ein Direkteintrag ist ein Pfad, kein Kuerzel - sonst wuerde
                // er gegen die Wurzel der Seite laufen und eine HTML-Seite
                // unter einem Jar-Namen ablegen.
                if matches!(item.source, Source::Direct) {
                    assert!(item.id.contains('/'), "{} hat keinen Pfad", item.name);
                }
            }
        }
    }

    #[test]
    fn the_blocked_bundle_stays_blocked() {
        let doktor = BUNDLES.iter().find(|b| b.id == "doktorsam").unwrap();
        assert!(doktor.blocked.is_some());
    }
}

#[cfg(test)]
mod gate_tests {
    use super::*;

    // Von Hand gebaut statt ueber Default: Instance hat keins, und eins nur
    // fuer diesen Test einzufuehren hiesse, Produktionscode fuer einen Test
    // zu aendern.
    fn instance(mc: &str, loader: &str) -> instance::Instance {
        instance::Instance {
            id: "test".into(),
            name: "Test".into(),
            path: String::new(),
            mc_version: mc.into(),
            loader: loader.into(),
            loader_version: String::new(),
            version_id: String::new(),
            ram_mb: 4096,
            install_client_mod: true,
            created: String::new(),
            last_played: 0,
        }
    }

    fn best() -> &'static Bundle {
        BUNDLES.iter().find(|b| b.id == "finanzinstitut").unwrap()
    }

    #[test]
    fn the_right_instance_passes() {
        assert!(fits(best(), &instance("26.2", "fabric")).is_ok());
        // Eine 26.2.1 ist immer noch 26.2.
        assert!(fits(best(), &instance("26.2.1", "fabric")).is_ok());
    }

    #[test]
    fn the_wrong_version_is_refused_and_says_both_halves() {
        let err = fits(best(), &instance("1.21.4", "fabric")).unwrap_err();
        assert!(err.contains("26.2"), "{}", err);
        assert!(err.contains("Fabric"), "{}", err);
        // Und was die Instanz wirklich ist, sonst raet man, welche Haelfte
        // danebenliegt.
        assert!(err.contains("1.21.4"), "{}", err);
    }

    #[test]
    fn the_wrong_loader_is_refused() {
        assert!(fits(best(), &instance("26.2", "vanilla")).is_err());
        assert!(fits(best(), &instance("26.2", "forge")).is_err());
        assert!(fits(best(), &instance("26.2", "quilt")).is_err());
    }

    #[test]
    fn the_resource_pack_bundle_takes_anything() {
        let umbaria = BUNDLES.iter().find(|b| b.id == "umbaria").unwrap();
        assert!(fits(umbaria, &instance("1.21.4", "vanilla")).is_ok());
    }

    #[test]
    fn info_says_why_before_anyone_presses_install() {
        // Ohne Instanz kann nichts geprueft werden - dann bleibt es leer,
        // statt etwas zu behaupten.
        let blind = info("finanzinstitut", "gibt-es-nicht");
        assert_eq!(blind.items.len(), 3);
        assert!(blind.blocked.is_empty());

        // Das gesperrte Paket sagt seinen eigenen Grund, nicht den der Instanz.
        let doktor = info("doktorsam", "gibt-es-nicht");
        assert_eq!(doktor.blocked, "Currently not available");
    }
}
