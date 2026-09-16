fn main() {
    // The CurseForge key is baked in at build time from the workflow's secret,
    // never from a file in the tree - the repository is public, and a key in
    // public source is a key that is revoked within the day.
    //
    // Without this line cargo would not notice the variable changing and would
    // hand back a cached binary carrying the old key.
    println!("cargo:rerun-if-env-changed=CURSEFORGE_KEY");
    tauri_build::build()
}
