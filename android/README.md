# Space Client for Android

An experiment, not a product. It exists to answer one question:

**Does Minecraft 26.2 with Space Client run on a phone at all?**

## What this build does

- Works out the newest stable Fabric loader for 26.2
- Downloads the Space Client mod for that version and checks it against the
  release's `SHA256SUMS`
- Writes a touch control layout with a **Menu** button bound to Right Shift
- Hands the start over to an installed runtime

## What it does not do

**It does not run Java.** Desktop Minecraft on Android needs a JVM port and a
translation layer from OpenGL to what mobile GPUs speak. Those exist, they took
years, and they are GPL-3.0. This app does not contain them.

So you still need PojavLauncher, Zalith or Fold Craft installed. This prepares
the instance; that starts it.

## Before building anything further

Two things have to be true, and neither is known yet:

1. **Java 25.** The mod requires it. Android JVM ports have historically
   shipped 8, 17 and 21. Without a runtime that has 25, 26.2 will not start and
   nothing in this app changes that.
2. **The renderer.** 26.2 reworked the render pipeline. On Android that runs
   through a translation layer, and Space Client's post effects already failed
   once on desktop under VulkanMod for the same kind of reason.

If either fails, that is the answer, and it is worth knowing now rather than
after a launcher has been written.

## If it does work

Then the next step is a real launcher, which means forking one of the existing
ones. **They are GPL-3.0, and a fork has to be published under GPL-3.0 with its
source.** Space Client is currently `ARR`. That is a decision to make before
writing code, not after.

## Building

CI does it on every push that touches `android/`. By hand:

```
cd android
gradle assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.
