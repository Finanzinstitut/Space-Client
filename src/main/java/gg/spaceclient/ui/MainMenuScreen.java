package gg.spaceclient.ui;

import gg.spaceclient.util.Reflect;
import gg.spaceclient.util.Screens;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The main menu, as a short sequence rather than a wall of buttons.
 *
 * Vanilla's title screen answers a question nobody asked - it presents six
 * options with equal weight before you have even sat down. This opens with a
 * greeting, hands over to the time, and only then puts the five things you
 * might actually want within reach.
 *
 * <h2>The timing</h2>
 *
 * The whole run is under four seconds, which is the ceiling for something that
 * plays on every launch: the second time you see an animation you are already
 * waiting for it to finish, and the tenth time you resent it. Clicking or
 * pressing a key jumps straight to the end for exactly that reason - the
 * sequence is a welcome, not a toll.
 *
 * The clock does not fade out and reappear at the top. It travels, because the
 * two moments are the same object and cutting between them would read as two
 * separate screens rather than one settling into place.
 */
public class MainMenuScreen extends Screen {

    // --- the run, in milliseconds from opening ---

    private static final long GREET_IN = 700;
    private static final long GREET_HOLD = 2900;
    private static final long GREET_OUT = 3500;
    private static final long CLOCK_IN = 3250;
    private static final long CLOCK_SETTLED = 4050;
    private static final long CLOCK_RISE = 5400;
    private static final long CLOCK_UP = 6400;
    private static final long ICONS_IN = 6000;
    private static final long DONE = 7200;

    /** Stagger between one icon appearing and the next. */
    private static final long ICON_STEP = 90;

    private static final int ICON_SIZE = 40;
    private static final int ICON_GAP = 16;

    private long openedAt = 0L;

    /** Whether the introduction has already run since the game started. */
    private static boolean introPlayed = false;

    /**
     * Chosen once per screen rather than per frame, so the greeting does not
     * change while it is being read.
     */
    private String greeting = "";

    private final java.util.List<MenuIcon> icons = new java.util.ArrayList<>();

    public MainMenuScreen() {
        super(Component.literal("Space Client"));
    }

    @Override
    protected void init() {
        // Only on the first build. rebuildWidgets runs on every resize, and
        // replaying the introduction because someone dragged the window edge
        // is the kind of thing that makes people turn a menu off.
        if (openedAt == 0L) {
            greeting = pickGreeting();

            // Played once per session, not once per visit. Coming back from
            // the server list to watch the same four seconds again is the
            // point at which an introduction becomes a toll booth, and every
            // way of skipping it goes through the input API - which changed
            // shape in this version and is not worth guessing at.
            openedAt = introPlayed
                    ? System.currentTimeMillis() - DONE
                    : System.currentTimeMillis();
            introPlayed = true;
        }

        icons.clear();

        int count = 5;
        int total = count * ICON_SIZE + (count - 1) * ICON_GAP;
        int left = (this.width - total) / 2;
        int y = Math.round(this.height * 0.52f);

        add(left, y, MenuIcon.Kind.WORLDS, "Singleplayer", this::openWorlds);
        add(left + (ICON_SIZE + ICON_GAP), y, MenuIcon.Kind.SERVERS, "Multiplayer",
                () -> Screens.open(new ServersScreen(this)));
        add(left + (ICON_SIZE + ICON_GAP) * 2, y, MenuIcon.Kind.ACCOUNT, "Accounts",
                () -> Screens.open(new AccountsScreen(this)));
        add(left + (ICON_SIZE + ICON_GAP) * 3, y, MenuIcon.Kind.SETTINGS, "Settings",
                this::openSettings);
        add(left + (ICON_SIZE + ICON_GAP) * 4, y, MenuIcon.Kind.QUIT, "Quit", this::quit);
    }

    private void add(int x, int y, MenuIcon.Kind kind, String label, Runnable action) {
        MenuIcon icon = new MenuIcon(x, y, ICON_SIZE, kind, label, action);
        icon.setAppear(0f);
        icons.add(icon);
        this.addRenderableWidget(icon);
    }

    // --- what the icons do ---

    /**
     * The world list.
     *
     * Falls back to the game's own screen if the custom one cannot be built,
     * because a menu whose first button does nothing is worse than one that
     * looks inconsistent for a moment.
     */
    private void openWorlds() {
        try {
            Screens.open(new WorldsScreen(this));
        } catch (Throwable ignored) {
            Object screen = construct("net.minecraft.client.gui.screens.worldselection.SelectWorldScreen", this);
            if (screen instanceof Screen fallback) Screens.open(fallback);
        }
    }

    private void openSettings() {
        // The client's own menu, which is what somebody opening this menu
        // almost always wants. Vanilla's options are one step further in.
        Screens.open(new SettingsHubScreen(this));
    }

    private void quit() {
        // void either way, so asked whether it ran rather than what it gave
        if (!Construct.invokedOn(Minecraft.getInstance(), "stop")) {
            Construct.invokedOn(Minecraft.getInstance(), "close");
        }
    }

    private Object construct(String className, Screen parent) {
        try {
            Class<?> type = Class.forName(className);
            for (var constructor : type.getConstructors()) {
                Class<?>[] params = constructor.getParameterTypes();
                if (params.length == 1 && params[0].isAssignableFrom(Screen.class)) {
                    return constructor.newInstance(parent);
                }
            }
        } catch (Throwable ignored) {
            // Nothing usable on this version
        }
        return null;
    }

    // --- the greeting ---

    private static final String[] GREETINGS = {
            "How is your day, %s?",
            "Welcome back, %s",
            "Good to see you, %s",
            "Ready when you are, %s",
            "Hello again, %s",
            "Take your time, %s",
            "Long time no see, %s",
            "Let's build something, %s"
    };

    private String pickGreeting() {
        String name = playerName();
        int hour = LocalDateTime.now().getHour();

        // A quarter of the time, say something that fits the clock underneath -
        // often enough to be noticed, rarely enough not to become the norm
        if (Math.random() < 0.25) {
            String part = hour < 5 ? "Still up" 
                    : hour < 12 ? "Good morning"
                    : hour < 18 ? "Good afternoon"
                    : "Good evening";
            return part + ", " + name;
        }

        String template = GREETINGS[(int) (Math.random() * GREETINGS.length)];
        return String.format(template, name);
    }

    /** Called directly: the accounts screen already compiles against this. */
    private String playerName() {
        try {
            String name = Minecraft.getInstance().getUser().getName();
            return name == null || name.isEmpty() ? "there" : name;
        } catch (Throwable ignored) {
            return "there";
        }
    }

    // --- the sequence ---

    private long elapsed() {
        return openedAt == 0L ? 0L : System.currentTimeMillis() - openedAt;
    }

    private static float span(long now, long from, long to) {
        if (now <= from) return 0f;
        if (now >= to) return 1f;
        return (now - from) / (float) (to - from);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        long now = elapsed();

        // Held still through the greeting, then eased in with the icons
        MenuWallpaper.setInfluence(Ease.outCubic(span(now, ICONS_IN, ICONS_IN + 900)));

        // The photograph if it can be drawn, the drawn backdrop if not -
        // never nothing, because a menu on black looks broken rather than plain
        if (!MenuWallpaper.draw(graphics, this.width, this.height, mouseX, mouseY, delta)) {
            Backdrop.draw(graphics, this.width, this.height);
        }

        // A wash over the picture so white text stays readable wherever the
        // drift happens to have put a bright patch
        graphics.fill(0, 0, this.width, this.height, 0x50000000);

        drawGreeting(graphics, now);
        drawClock(graphics, now);

        // Handed to the icons before the widgets draw themselves, so their
        // entrance is part of the same clock as everything else
        for (int i = 0; i < icons.size(); i++) {
            long start = ICONS_IN + i * ICON_STEP;
            icons.get(i).setAppear(span(now, start, start + 450));
        }

        super.extractRenderState(graphics, mouseX, mouseY, delta);

        drawIconLabel(graphics, mouseX, mouseY, now);
    }

    private void drawGreeting(GuiGraphicsExtractor graphics, long now) {
        if (now >= GREET_OUT) return;

        float in = Ease.outCubic(span(now, 0, GREET_IN));
        float out = span(now, GREET_HOLD, GREET_OUT);

        // Rises on the way in and keeps rising on the way out, so it leaves in
        // the direction it arrived from rather than reversing
        float alpha = in * (1f - Ease.outCubic(out));
        if (alpha <= 0.01f) return;

        int lift = Math.round((1f - in) * 14f + Ease.inOutCubic(out) * 26f);

        float scale = 1.6f;
        int textWidth = Math.round(this.font.width(greeting) * scale);
        int x = (this.width - textWidth) / 2;
        int y = Math.round(this.height * 0.44f) - lift;

        int colour = MenuIcon.withAlpha(0xFFFFFF, Math.round(255 * alpha));

        boolean scaled = Scale.push(graphics, x, y, scale);
        graphics.text(this.font, greeting, scaled ? 0 : x, scaled ? 0 : y, colour, false);
        if (scaled) Scale.pop(graphics);
    }

    /**
     * The time, and the date above it.
     *
     * Real time, not the world's - the point is the clock you are ignoring
     * while you play, not the one in the sky.
     */
    private void drawClock(GuiGraphicsExtractor graphics, long now) {
        if (now < CLOCK_IN) return;

        float in = Ease.outCubic(span(now, CLOCK_IN, CLOCK_SETTLED));
        float rise = Ease.inOutCubic(span(now, CLOCK_RISE, CLOCK_UP));

        LocalDateTime moment = LocalDateTime.now();
        String time = moment.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH));
        String date = moment.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH));

        // From the middle of the screen to the upper third
        int restY = Math.round(this.height * 0.42f);
        int topY = Math.round(this.height * 0.24f);
        int baseY = Math.round(restY + (topY - restY) * rise);

        // Slightly larger while it is the only thing on screen, settling to a
        // heading once the icons arrive
        float scale = 3.4f - 0.6f * rise;
        int alpha = Math.round(255 * in);

        int timeWidth = Math.round(this.font.width(time) * scale);
        int timeX = (this.width - timeWidth) / 2;
        int timeY = baseY + Math.round((1f - in) * 10f);

        int dateColour = MenuIcon.withAlpha(0xFFFFFF, Math.round(200 * in));
        int dateWidth = this.font.width(date);
        graphics.text(this.font, date,
                (this.width - dateWidth) / 2, timeY - 12, dateColour, false);

        boolean scaled = Scale.push(graphics, timeX, timeY, scale);
        graphics.text(this.font, time,
                scaled ? 0 : timeX, scaled ? 0 : timeY,
                MenuIcon.withAlpha(0xFFFFFF, alpha), false);
        if (scaled) Scale.pop(graphics);

        // The client's name under the clock, as small as it can be and still
        // be read - it is a signature, not a banner
        float nameFade = Ease.outCubic(span(now, CLOCK_SETTLED, CLOCK_SETTLED + 700));
        if (nameFade > 0.01f) {
            String name = "Space Client";
            int nameY = timeY + Math.round(this.font.lineHeight * scale) + 4;
            graphics.text(this.font, name,
                    (this.width - this.font.width(name)) / 2, nameY,
                    MenuIcon.withAlpha(0xFFFFFF, Math.round(160 * nameFade * (1f - rise * 0.4f))),
                    false);
        }
    }

    /** The name of whichever icon the cursor is over, under the row. */
    private void drawIconLabel(GuiGraphicsExtractor graphics, int mouseX, int mouseY, long now) {
        if (now < ICONS_IN) return;

        for (MenuIcon icon : icons) {
            if (!icon.isHovered() || icon.appear() < 0.9f) continue;

            String text = icon.label();
            int y = icon.getY() + ICON_SIZE + 12;
            graphics.text(this.font, text,
                    (this.width - this.font.width(text)) / 2, y, 0xFFFFFFFF, false);
            return;
        }
    }

    /** Nothing to go back to: this is the bottom of the stack. */
    @Override
    public void onClose() { }

    /**
     * Deliberately without @Override: nothing else in this mod has compiled
     * against this method, so if it has been renamed this quietly becomes an
     * unused method rather than a failed build. If the name still stands, Java
     * treats it as an override with or without the annotation.
     */
    public boolean isPauseScreen() { return false; }
}
