package gg.spaceclient.modules;

import gg.spaceclient.module.Module;
import gg.spaceclient.setting.BooleanSetting;
import gg.spaceclient.setting.IntSetting;
import gg.spaceclient.setting.SettingGroup;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/**
 * Stops the game drawing things nobody can read anyway.
 *
 * Deliberately not a render distance slider with a different label. Turning
 * chunks off is a trade - you see less world and you get frames back - and
 * anyone who wants that trade already has the option. What this does instead
 * is find work the game does whose result was never worth anything: a name tag
 * eighty blocks away that is two pixels tall, or four hundred dropped items in
 * a pile where the ones at the back are entirely behind the ones at the front.
 *
 * <h2>Where the frames actually are</h2>
 *
 * On a crowded server the cost is rarely the terrain. It is entities, and
 * mostly two kinds. Dropped items are the worst offender because each one is a
 * separate model with its own pose, and because they arrive in hundreds at
 * once when somebody dies. Name tags are the second, because each is a piece
 * of text laid out and drawn in world space, and a busy spawn has one per
 * player whether or not you can make out a single letter.
 *
 * Neither of those is a quality setting. A tag you cannot read and an item
 * hidden behind another item are not things you lose.
 */
public class FpsBoostModule extends Module {

    // --- dropped items ---

    private final BooleanSetting cullItems = new BooleanSetting(
            "cull_items", "Cull far items",
            "Stop drawing dropped items past a distance", true);

    private final IntSetting itemDistance = new IntSetting(
            "item_distance", "Item distance",
            "How far away dropped items stop being drawn, in blocks", 40, 8, 128);

    /**
     * A ceiling on how many dropped items are drawn at once.
     *
     * The one that matters after a death. Past a few dozen the pile is a solid
     * mass and every further item is drawn entirely behind another one - the
     * frames go and nothing appears on screen for them.
     */
    private final BooleanSetting capItems = new BooleanSetting(
            "cap_items", "Limit item count",
            "Draw only so many dropped items per frame", true);

    private final IntSetting itemBudget = new IntSetting(
            "item_budget", "Item budget",
            "How many dropped items may be drawn in one frame", 96, 16, 512);

    // --- name tags ---

    private final BooleanSetting cullTags = new BooleanSetting(
            "cull_tags", "Cull far name tags",
            "Stop drawing name tags that are too small to read", true);

    private final IntSetting tagDistance = new IntSetting(
            "tag_distance", "Tag distance",
            "How far away name tags stop being drawn, in blocks", 32, 8, 64);

    public FpsBoostModule() {
        super("fpsboost", "FPS Boost",
                "Skips drawing work whose result nobody could see", false);
        addGroups(
                SettingGroup.of("Dropped items", "The heaviest thing on a busy server",
                        cullItems, itemDistance, capItems, itemBudget),
                SettingGroup.of("Name tags", "Text too small to read still costs the same",
                        cullTags, tagDistance)
        );
    }

    // --- the frame budget ---

    /**
     * Items drawn so far this frame.
     *
     * Reset when the frame changes rather than on a timer, because two draws
     * in the same frame must share one budget and two draws a frame apart must
     * not. The frame is identified by the game's own counter.
     */
    private int itemsThisFrame = 0;
    private long frameToken = -1L;

    private void newFrameIfNeeded() {
        long now = frameOf();
        if (now != frameToken) {
            frameToken = now;
            itemsThisFrame = 0;
        }
    }

    /**
     * Something that changes once per frame.
     *
     * Taken from the render clock rather than a frame counter because nothing
     * here has compiled against one, and any value that is stable within a
     * frame and different between frames does the job. Nanosecond time divided
     * down to a millisecond is stable across the few microseconds a frame's
     * entity pass takes.
     */
    private static long frameOf() {
        return System.nanoTime() / 1_000_000L;
    }

    /**
     * Whether a dropped item is worth drawing.
     *
     * Distance first, budget second: the budget should be spent on the items
     * closest to the camera, and entities arrive in roughly that order because
     * the game walks them by section. Spending it on whatever was furthest
     * would be worse than not having one.
     */
    public boolean allowItem(Entity entity) {
        if (!isEnabled() || entity == null) return true;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return true;

        if (cullItems.get()) {
            double limit = itemDistance.get();
            if (entity.distanceTo(mc.player) > limit) return false;
        }

        if (capItems.get()) {
            newFrameIfNeeded();
            if (itemsThisFrame >= itemBudget.get()) return false;
            itemsThisFrame++;
        }

        return true;
    }

    /** Whether a name tag at this distance is worth laying out. */
    public boolean allowNameTag(double distanceSquared) {
        if (!isEnabled() || !cullTags.get()) return true;

        double limit = tagDistance.get();
        return distanceSquared <= limit * limit;
    }

    /** What the module is currently doing, for the diagnostics screen. */
    public String status() {
        if (!isEnabled()) return "off";

        StringBuilder out = new StringBuilder();
        if (cullItems.get()) out.append("items past ").append(itemDistance.get()).append("m");
        if (capItems.get()) {
            if (out.length() > 0) out.append(", ");
            out.append("max ").append(itemBudget.get()).append(" items");
        }
        if (cullTags.get()) {
            if (out.length() > 0) out.append(", ");
            out.append("tags past ").append(tagDistance.get()).append("m");
        }
        return out.length() == 0 ? "nothing enabled" : out.toString();
    }
}
