# Space Client — In-Game Mod 🚀

Fabric mod for Minecraft **26.2** that adds HUD modules to the Space Client launcher.

## Status: minimal core

This is deliberately cut back to a core that uses only APIs verified against
Fabric's own 26.2 reference code. An earlier, much larger version was written
against guessed API names and produced 100 compile errors — rather than guessing
again, the scope was reduced to what is known correct, to be expanded once each
addition is confirmed building.

**Included**

| Module | Default | Notes |
|---|---|---|
| FPS | on | |
| CPS | on | shared counter other modules read from |
| Coordinates | on | |
| Keystrokes | on | KEYBINDS / FULL / CUSTOM, laid out like a real keyboard |
| Ping | off | |
| Clock | off | |
| Speedometer | off | also shows % of the theoretical max for your state |
| Session | off | uptime plus an optional break reminder |
| Mouse Tracker | off | drawn from rectangles, every colour configurable |
| Memory | off | bar turns amber then red as the heap fills |
| Compass | off | scrolling strip, so you can hold a heading between cardinals |
| Travelled | off | also converts the distance to Nether equivalents |
| Zoom | off | key chosen in the module's own settings, read from the device |
| Hitbox | off | per-category boxes with own colour and width — see below |
| Chunk | off | chunk coordinates plus position inside the chunk |
| Players Online | off | flags briefly when the count changes |
| Aim | off | yaw and pitch, plus the nearest 45° snap |
| Align | off | only the deviation from the nearest axis; turns green when exact |
| Click Graph | off | rolling graph, so bursts and steady clicking look different |
| Marker | off | sneak + drop stores a spot; shows bearing and distance back |
| Now Playing | off | Spotify or Amazon Music only, with controls in chat |
| Hit Colour | off | tints what you hit, and what is within reach |

Press **Right Shift** to open the menu. The interface deliberately matches the
launcher rather than vanilla Minecraft: the same violet and cyan accents, the
same deep-violet starfield with a glowing planet in the corner, flat panels and
hairline borders instead of Minecraft's bevelled buttons.

**Appearance** changes the background (space, dark, solid black, transparent)
and the accent colour with RGB sliders, with a live preview. Everything is
written to `config/spaceclient.json` as soon as it changes.

**Appearance -> Font** picks the font the whole game draws with: Minecraft's
own, six redrawn bitmap faces (Smooth, Anti-Alias, Small Caps, Square, Doodle,
Blocky) and Open Sans. Minecraft's own is the default.

Each face ships inside the jar as a finished resource pack. Choosing one writes
it into `resourcepacks/` as `spaceclient-font-<name>.zip` and pins it to the top
of the selected list - required, so there is no arrow to take it out, and fixed,
so it cannot be dragged below anything. That is the highest priority slot, which
is what makes it win over a font set by your own resource pack. It is the same
pair of switches Minecraft uses on its own Default pack, only at the other end
of the list.

Only ever one of them is on disk; switching deletes the previous one, so the
resource pack screen never fills up with old fonts. Choosing Minecraft unpins and
removes it entirely and leaves your own packs to decide. Switching triggers a
resource reload, the same one-off pause as toggling any pack.

The selection is stored by Minecraft in `options.txt` alongside every other
pack, so it survives a restart without this mod doing anything at startup. The
copy in `config/spaceclient.json` is only what the menu displays.

Up to 1.8.2 the jar carried `assets/minecraft/font/default.json`, which forced
Open Sans on everybody with no way to switch it off. That file is gone. The same
override is now the Open Sans entry in this list, off unless it is picked.

The middle mouse button has no vanilla binding, so Space Client registers its
own — it appears in the normal Controls screen under "Space Client" and can be
rebound there.

## What 26.2 changed

Worth writing down, because almost every tutorial online is wrong for this version:

- Minecraft ships **unobfuscated**. The Loom plugin id is
  `net.fabricmc.fabric-loom`, there is **no `mappings` line**, and dependencies
  use plain `implementation`.
- `GuiGraphics` is now **`GuiGraphicsExtractor`** (`net.minecraft.client.gui`).
- `ResourceLocation` is **`Identifier`** (`net.minecraft.resources`).
- `HudRenderCallback` is gone. HUD drawing goes through
  `HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, id, element)`,
  where an element takes `(GuiGraphicsExtractor, DeltaTracker)`.
- Text is drawn with `graphics.text(font, str, x, y, argb, shadow)`.
- `Screen#render` is now **`extractRenderState`**, same parameters.
- Custom widgets override **`extractWidgetRenderState`**, not `renderWidget`, and
  it is **`public`** — declaring it `protected` fails to compile.
- On **buttons** that method is `final`. A button subclass overrides
  **`extractContents`** instead, which runs after the vanilla sprite is drawn —
  filling the full bounds there paints over it, giving a flat custom look.
- Key mappings take a registered **`KeyMapping.Category`** object, not a
  translation key string, and register through
  `net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper`.
- **`setScreen` moved onto `Minecraft.gui`** — `minecraft.gui.setScreen(...)`,
  not `minecraft.setScreen(...)`.
- Colours are **ARGB**, not RGB — an RGB value renders fully transparent.

## How risky calls are handled

Every Minecraft API call in this mod falls into one of two groups.

**Proven** — confirmed by a successful compile — is called directly:
`getX/getY/getZ`, `getYRot/getXRot`, `getFps`, `isSprinting`, `getUUID`,
`getConnection().getPlayerInfo()`, `mc.level`, `mc.options.key*`,
`mc.options.sensitivity()`, `mc.font`, `getWindow().getGuiScaled*`.

**Unverified** goes through reflection instead: `mc.options.fov()`, the player
list accessor, the whole world render context, entity bounding boxes and view
vectors, the window handle, and adding a widget to someone else's screen. A
wrong guess there costs a null and one log line rather than a failed build.

That includes **subscribing to Fabric's world render event**. Naming
`WorldRenderEvents` in an import was itself enough to fail the build, because
Fabric API 26.1 renamed it to `LevelRenderEvents` and moved it into a `level`
subpackage. `render/WorldRenderHook` looks the class up by name at runtime,
builds a dynamic proxy against whichever callback interface it declares, and
reports failure to the caller instead of exploding.

Every Fabric and Minecraft import left in the source has now compiled at least
once, which is checked mechanically rather than assumed.

That split exists because each failed build means another upload from a phone,
so the cost of guessing wrong is much higher than the cost of a little
reflection.

## Now Playing

Reads the track from the **local Spotify or Amazon Music app** and shows it as a
normal HUD element — draggable in the HUD editor, colours and options in its own
settings, like every other module.

Detection works off the desktop app's window title, which is the only thing
available without signing into a web API. That has a useful side effect: music
playing in a browser tab has no such process, so YouTube and the like are
ignored, which is the intended behaviour. Only the two player processes are
looked at by name; nothing else is considered.

**Playback controls appear when you open the chat.** The HUD itself cannot take
clicks — it draws underneath everything and the game holds the cursor — but once
a screen has the mouse, ordinary widgets work. Three buttons are placed just
under the element: previous, play/pause, next. They press the system media keys,
which Windows routes to whichever app is currently playing, so no direct
connection to either player is needed.

**Playback controls run through a script file**, not an inline PowerShell
command. That was the original mistake: the key press helper needs double quotes
around the DLL name, and those did not survive being passed as a single argument
— PowerShell saw a broken statement, exited without doing anything, and the
button clicked to no effect. Writing the script to the temp folder once and
calling it with `-File` removes the quoting problem entirely, and a non-zero
exit is now reported instead of ignored.

Two honest limitations:

- **Windows only.** The lookup runs through PowerShell so that nothing has to be
  shipped alongside the mod. On other systems the module says so and stays quiet.
- Every process with a window is listed and matched on this side, rather than
  asked for by name. Amazon Music has shipped under more than one process name,
  and asking PowerShell for a name that does not exist returns nothing at all —
  which looked exactly like "nothing playing". The Diagnostics page lists which
  player processes were actually seen.
- **No cover art.** Window titles carry the track name and artist, nothing else.
  A drawn record stands in for the artwork; real covers would need a signed-in
  web API, which is a separate project of the size the Azure login was.

## Long settings lists

Settings screens split into **pages** when there is more than fits the window,
with `< Page` and `Page >` beside the back button and a counter in the corner.

Paging rather than scrolling is deliberate: a scroll wheel handler needs the
mouse event signature, which changed in this version, while buttons are already
known to work. A colour wheel takes about as much room as four ordinary rows, so
a module with several colours fills a page quickly.

## A crash before the window opened

Enabling Zoom in the config made the game fail on startup with
`GLFW error before init: The GLFW library is not initialized`.

The chain: loading the config switches modules on, which fires their enable
hook, which installed the scroll wheel hook, which asked GLFW for the window —
all during mod initialisation, long before Minecraft starts GLFW itself. The
call queued an error that Minecraft found moments later and turned into a crash.

Everything that talks to GLFW now waits for a flag set on the **first client
tick**, by which point the window certainly exists. Before that the handle is
simply zero and every input feature reports itself unavailable, which they
already knew how to do.

The lesson generalises: a mod's initialiser runs early, and "early" here means
before the game has a window at all.

## Hit Colour

Two cues in one module, because they answer different questions: the reach tint
says whether a swing would connect at all, the hit tint confirms one landed.
Each has its own sub-menu with a switch and a colour, plus a fade for the hit
flash and an adjustable reach distance.

The colour is drawn as a **translucent shell** over the entity rather than by
recolouring its model. Tinting the model itself needs a hook into the entity
renderer whose shape has not been confirmed for this version, and a shell
through the pipeline that already works is worth more than a tint that might
never draw. It shares the hitbox module's render pass, so it costs nothing
extra.

## Mouse only, on purpose

Widgets do not take keyboard focus. That matters most in the chat, where the
music controls sit: the arrow keys are for stepping back through sent messages
and Enter is for sending. A focusable widget steals both, so Enter would skip
the track instead of posting the message.

Refusing focus turned out not to be enough on its own. The screen walks its
widgets looking for a focus path, and one that merely reports itself unfocused
can still be handed one — which is why the problem came back intermittently.
The widgets now return nothing from that walk, taking them out of navigation
altogether.

## Invisible entities

Hitboxes and tints skip anything the game has hidden: an invisible mob, a player
under a potion, a spectator. Drawing a box around them would hand you
information the game deliberately withheld, and on a server that is exactly the
sort of thing that gets a client called a cheat. There is a switch for it in the
hitbox settings, off by default and clearly labelled.

## Shop

Cosmetics are bought with Galaxy Points from the menu's **Shop** button. Click
an item to buy it, click it again to wear it or take it off.

Nothing about the shop is decided in the mod. The balance, the catalogue and
whether a purchase goes through all come from the service, and requests carry
the Minecraft session token so it can confirm who is asking rather than trusting
a uuid. A refusal is shown in the server's own words — "not enough points" and
"already owned" are worth telling apart.

That split is deliberate: these points are meant to cost money, and a balance
kept in `spaceclient.json` would be a number anyone could edit.

**Points are granted manually for now**, through an admin endpoint on the
service. Payments are a separate step, and the code is the small half of it —
see the service's README for what the other half involves.

**The cosmetics do not render yet.** Buying and wearing works end to end, and
the service already knows what everyone is wearing, but drawing something on a
player model needs a hook into the entity renderer whose shape has not been
confirmed for this version. That is the next piece.

## Diagnostics

The last button in the menu opens a page listing every reflective lookup the mod
makes, with OK or a reason it failed: the world render event, the line box
renderer, the raw keyboard handle, the field of view option, the account field
on `Minecraft`, and whether the launcher's accounts can be read.

This exists because a failed lookup otherwise only writes to a log file, and the
symptom in game is simply that a feature does nothing. One screenshot of this
page says which lookup is at fault, instead of another round of guessing.

## Colours

Every colour — the interface accent and each module's own colours — is picked on
a **hue and saturation wheel** with a brightness bar beside it, not by typing a
hex code. Opacity keeps a slider, since it has no place on a hue wheel.

The wheel is drawn as small filled squares and does its own HSB conversion
rather than pulling in `java.awt`, which lives in a module that is not
guaranteed to be on the runtime image.

## Hitboxes

Four categories — **yourself, other players, mobs, items** — each in its own
sub-menu with a switch, a colour and a line width. The look-direction arrows can
be turned off, or limited to players only.

### Why the earlier attempts drew nothing

Every previous version was built on `LevelRenderer.renderLineBox`. **That method
does not exist in 26.2 at all.** The render rework replaced immediate drawing
with a submit based pipeline, so there was nothing to call — no amount of
reflection around a missing method could have helped.

Drawing now goes the way this version expects:

- a mixin on `LevelRenderer.submitFeatures` runs after the world has gathered
  its own geometry,
- boxes are handed to the `SubmitNodeCollector` via `submitCustomGeometry`,
- and each of the twelve edges is drawn as **two thin quads at right angles**,
  using the debug quad render type.

That last point is the part that took longest to get right. There is no line
type with a width in this pipeline, so an edge is a thin slab rather than a
line, and thickness is simply how wide that slab is. Two slabs crossed keep the
edge visible from every direction — a single flat one vanishes when viewed edge
on. The vertices carry a colour and nothing else: no pose, no normal.

If the mixin does not attach on some future version, the module falls back to
switching on Minecraft's own hitbox view, and the Diagnostics page says so.

### Why the boxes jittered

Entities are drawn *between* ticks, at a position interpolated from the previous
one. The bounding box, though, is the position from the last tick — so a box
drawn straight from it trails anything that moves and snaps forward twenty times
a second. On a walking player that reads as the box lagging behind and shaking.

The box is now offset by the same amount the entity's drawn position differs
from its tick position, which locks it to the model. The look arrow uses the
same partial tick, so it no longer swings independently of the head it belongs
to. A teleport is ignored rather than interpolated, or the box would stretch
across the world for one frame.

Two smaller fixes came with it: the outline is nudged fractionally outwards, as
an edge sitting exactly on the model surface flickers against it as the camera
moves, and there is now a switch for hiding boxes behind blocks.

### A crash worth explaining

An early version wrote vertices with a normal, which the debug quad format does
not carry, and the pipeline answered with `Missing elements in vertex` — on the
render thread, which ends the frame and takes the game down with it.

Two things came out of that. The vertex format is now the right one, and more
importantly the drawing callback guards itself: it runs *later* than the code
that submits it, while the frame is being built, so guarding the submit was
never going to catch anything. A failure there now switches the module off and
records why, instead of repeating the crash every frame. A hitbox is not worth
losing the game over.

## Accounts and sessions

The **server list** carries a *Space Client* button in the top left corner,
because that is where switching accounts is actually needed — the in-game menu
cannot be reached from the main menu. It opens the same Accounts screen.

Fabric's usual helper for putting a widget on someone else's screen
(`Screens.getButtons`) does not exist in this version, so the widget is handed
to the screen's own protected `addRenderableWidget` through reflection, found by
name and parameter count. If that ever stops matching, the button quietly does
not appear and a line goes in the log — the rest of the mod is unaffected.

### The bug that made switching accounts fail

The account object is built by filling whichever constructor this version
declares, positionally by parameter type. The values were assigned by counting
String parameters: first the name, then the uuid, then the token.

That is wrong whenever the uuid has a **`UUID`-typed parameter of its own** —
because then the *second* String is the access token, and it was being handed
the uuid instead. The result was an account with the right name and a nonsense
token: the game showed the new username, and every server rejected the login as
an invalid session.

It passed the verification step because that only compared the name. Both halves
are fixed: the token is placed correctly, and after the swap the token is read
back out of the live account and compared, so a wrong slot cannot pass as
success again. The Diagnostics page shows the live token's length and last six
characters, which makes a real token distinguishable from a uuid at a glance.

### Zoom, and why changing the option was not enough

The field of view option is clamped to the slider's range, roughly 30 to 110.
A zoom wants to go well below that: at four times magnification from a base of
110, the target is 27. The setter does not refuse that — it silently clamps to
30, so the mouse sensitivity dropped as configured while the view barely moved.

The zoom therefore takes the same route Zoomify does — confirmed by reading
Zoomify's own source rather than guessing again: a **mixin on
`Camera.calculateFov`** (and `calculateHudFov`, for the held item) divides the
computed value by the zoom factor, past the clamp entirely.

An earlier attempt targeted `GameRenderer.getFov` and failed silently, because
that method does not compute the field of view in this version - it reads the
option, which is exactly the value that was already being clamped. The
computation happens on `Camera`, which is where Zoomify hooks it too.

The mixin names its target as a string, matches methods by name only and uses
`require = 0`, so a signature that moved again is skipped with a log line
instead of stopping the game from starting.

The first time the hook runs it marks itself active and the option-based
fallback stands down, so the two can never both apply and zoom twice.

### Why the zoom stuttered, and scrolling to go further

Two changes make it feel right rather than merely work.

The magnification is advanced by **real elapsed time, not per tick**. Ticking
runs twenty times a second, so a tick driven zoom moves in twenty visible steps
regardless of frame rate — which is precisely what a stuttering zoom looks like.
The value is now recomputed each frame from the wall clock, with separate zoom
in and zoom out durations and a choice of curves.

**Scrolling while zoomed** goes further in or out, in geometric steps: each
notch multiplies the magnification rather than adding to it. Adding makes the
first notch enormous and the last one imperceptible; multiplying makes every
notch feel the same size. How much a notch is worth and how many are allowed are
both configurable, and the steps can optionally be remembered between zooms.

The wheel is taken over only while the zoom key is held. GLFW hands back the
previous callback when a new one is installed, so ours records the movement and
passes it straight on to the game the rest of the time — the hotbar keeps
working exactly as before.

### "invalid_public_key_signature" after switching accounts

Minecraft signs chat with a keypair tied to the signed-in profile. After a
session swap the keypair still belongs to the previous account, and any server
running with `enforce-secure-profile` refuses the join because the signature does
not match the profile presenting it.

The key manager is now rebuilt after a swap, through whichever static factory
its class exposes, with arguments matched by type from the running game. If that
fails it costs chat signing only, so it is logged rather than treated as fatal —
and the Diagnostics page shows whether the manager was found at all.

### Why "invalid session" kept coming back

Microsoft **rotates refresh tokens**: every use hands back a new one and retires
the one just used. The mod read the launcher's copy but never kept the new one,
so the second refresh in a session replayed a token Microsoft had already
invalidated and failed — while still reporting that a refresh had run.

Rotated tokens now live in `config/spaceclient-tokens.json`, preferred over the
launcher's copy and dropped again when Microsoft rejects one, so the launcher's
version gets a turn. The launcher's own file is never written to.

The swap is also **verified instead of assumed**: after writing the new session,
the game is asked who it thinks it is, and a mismatch is reported rather than
passing as success. The Accounts screen shows that live value under the heading,
so a swap that did not take is visible immediately.

### Keeping the session alive

Reacting to a failed join was not enough: an "invalid session" failure happens
*during* the connect attempt, so no connection is ever established and there is
no drop to react to. The token is therefore renewed **before** it can go stale —
once shortly after launch, since the launcher may have been open for hours
before the game started, and then hourly, well inside the roughly one day a
session lasts. The drop trigger is kept as a second chance.

A refresh follows **the account picked in game**, not the one the launcher has
active. Reading only the launcher is what made a refresh after switching jump
back to the account the game started with.

**Accounts** in the menu lists whatever accounts the Space Client launcher has
signed in, switches between them, and refreshes the current session — all
without restarting the game.

Why it needs to exist: a Minecraft session token lasts about a day. Leave the
game running longer and joining a server fails with "invalid session", which
normally means quitting and relaunching. The mod re-runs the token chain
(refresh token → Microsoft → Xbox Live → XSTS → Minecraft) and swaps the result
into the running client.

It also reacts on its own: when a server connection drops, the session is
quietly refreshed in the background, with a 30 second cooldown so a failing
refresh cannot loop. Join again from the server list — no restart.

The disconnect is spotted by watching the network connection rather than the
screen, because reading the disconnect reason would mean reaching into the
screen's private state for a message whose wording differs per server.

### How the swap works, and what could break it

Replacing the live session means replacing Minecraft's `User` instance, which is
a private field. That is done with **reflection, matching the field by its type
rather than its name**, and the `User` itself is built by filling whichever
constructor is present positionally by parameter type. Both choices are
deliberate: names and constructor shapes move between versions, types do not,
and a mismatch here produces a logged warning instead of a broken build.

The mod reads the launcher's `accounts.json` (`%APPDATA%/space-client` on
Windows) but never writes to it — accounts are added and removed in the
launcher. If the game was not started through Space Client, the Accounts screen
says so and does nothing else.

## A note on the keyboard view

`FULL` draws a proper keyboard — number row, TAB/QWERTZUI, CAPS/ASDFGHJ,
SHIFT/YXCVBN, CTRL and space — with the real key proportions and row offsets.
`CUSTOM` keeps that same shape and simply leaves out the keys you did not list,
so the layout stays recognisable.

Pressed state is read from the **physical keyboard**, not from key bindings.
That distinction matters: with hotbar slot 5 bound to F, pressing F should light
up F — a binding-based display would light up the "5" key instead, or nothing at
all. Every key on the layout responds, including letters the game does not bind.

This uses GLFW directly. It needs the window handle, whose accessor has moved
between versions, so the handle is found by type through reflection — it is the
only `long` on the window object. If that lookup ever fails the module falls
back to key bindings and logs a warning, rather than going dark.

The mouse buttons deliberately stay on the bindings, since those genuinely are
controls and may be swapped.

## Deliberately not included yet

Each of these needs API details this version changed and that are not yet
confirmed, so they are left out rather than guessed at:

- **The HUD editor** (dragging elements into place). Dragging needs the mouse
  event API, which changed in this version: `Screen#mouseClicked` now takes a
  `MouseButtonEvent`. The menu itself sidesteps this by being built from Button
  widgets, which handle their own clicks.
- **Mixins** (hitbox filtering, zoom, the Jupiter badge next to player names).
- Modules touching inventory, potion effects, options (`OptionInstance`),
  or entity positions — all of those method names moved.

## Building

Push to GitHub; the workflow builds on `main` and uploads the jar as an artifact.

## License

All Rights Reserved.

## Cosmetics

Space Client has no cosmetics system of its own. Cosmetica is the account and
the catalogue; Space Client draws the interface.

The sidebar entry opens `CosmeticsScreen`, which uses the same layout as the
mods grid - sidebar, chips, cards - and reads Cosmetica's data through
`CosmeticaBridge`. Everything in that bridge is reflection: Cosmetica is a
separate mod that a player may not have, and a direct import would make it a
hard build dependency.

### Why the data is copied instead of the widgets

Cosmetica 2 no longer builds its menus on Minecraft's `Screen`. Its screens are
`cc.cosmetica.kupe.api.Screen` - Kupe is a declarative toolkit with its own
screen stack, its own layout pass and its own renderer. Nothing from it can be
placed inside a Minecraft screen, so `RotatableGUIPlayer` and friends are not
reusable here even though they do exactly the right thing inside Cosmetica.

This also means the previous bridge could never have worked: it cast Kupe's
`HomeScreen` to a Minecraft `Screen`, which throws, which the catch block then
reported as "Cosmetica is installed but its menu could not be opened".

### What is mirrored and what is handed over

Mirrored in Space Client's own interface:

- outfit list, with thumbnails, and which one is worn
- wearing an outfit and taking one off
- the cape, elytra and accessories currently on the player
- whether the Cosmetica account is signed in

Handed over to Cosmetica's Kupe screens, because reimplementing them would mean
reimplementing the search, the per-cosmetic offset and flag editor, and the
delete confirmations against an API this mod does not compile against:

- browsing the catalogue
- creating an outfit and deleting one
- nametag styling
- Cosmetica's own settings

A handover replaces the whole screen. Closing a Kupe screen returns wherever
Kupe decides, not to the Space Client menu.

### Textures

`Textures` resolves the texture drawing call on `GuiGraphicsExtractor` at
runtime rather than naming it. No file in this repository has ever drawn a
texture, so that signature is unverified on 26.2, and a wrong guess in a direct
call would cost a build. It logs the signature it settles on the first time a
thumbnail is drawn:

    Using blit(...) for menu textures (pipeline=..., tint=...)

Send that line back and the reflection can be replaced with a direct call. If it
instead logs that no usable call was found, cards fall back to a dashed
placeholder and everything else still works.

Outfit thumbnails are requested by Cosmetica as an eight frame animation sheet,
so cards draw one frame rather than the whole texture. If the thumbnails come
out squashed or show the wrong slice, that constant (`OUTFIT_FRAMES`) is the
thing to change.
