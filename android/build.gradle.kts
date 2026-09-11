// Versions are pinned rather than left open. This project is built by CI on a
// machine that starts empty every time, so "latest" means the build can change
// under you between two pushes with no commit to explain it.
plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
