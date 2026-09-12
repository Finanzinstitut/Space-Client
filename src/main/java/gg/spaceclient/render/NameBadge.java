package gg.spaceclient.render;

import gg.spaceclient.SpaceClient;
import gg.spaceclient.net.Presence;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/**
 * Puts the Space Client mark in front of a name tag.
 *
 * Drawn as a character rather than a texture, which is the whole trick. The
 * name tag pipeline in 26.2 takes a Component and nothing else; getting a
 * texture in beside it would mean a second draw call positioned by hand, in
 * world space, against an API whose parameters are not all understood. A glyph
 * needs none of that - it rides along inside the text that is already being
 * drawn, so it scales, sorts, fades with distance and sits behind blocks
 * exactly like the name does, for free.
 *
 * The glyph lives in a font of its own at a private use code point, and
 * `assets/spaceclient/font/badge.json` maps that code point to the image.
 *
 * This class is called from a mixin and must therefore never call back into
 * one: mixin classes are consumed by the transformer and are not loadable as
 * ordinary classes.
 */
public final class NameBadge {

    /** Matches the "chars" entry in assets/spaceclient/font/badge.json. */
    private static final String STANDARD = "\uE000";

    private static final Identifier BADGE_FONT =
            Identifier.fromNamespaceAndPath(SpaceClient.MOD_ID, "badge");

    /**
     * Built once and reused.
     *
     * Style is immutable, and this runs for every name tag on screen every
     * frame - there is no reason to allocate one per tag.
     */
    private static final Style BADGE_STYLE =
            Style.EMPTY.withFont(new FontDescription.Resource(BADGE_FONT));

    /**
     * Returns the name with the badge in front, or the name untouched.
     *
     * The original component is handed back by identity when there is no badge
     * to add, so the common case allocates nothing at all.
     */
    public static Component decorate(EntityRenderState state, Component name) {
        try {
            if (name == null) return name;
            if (!(state instanceof AvatarRenderState avatar)) return name;

            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return name;

            // AvatarRenderState carries no UUID, so the entity id it does
            // carry is turned back into the player it came from
            Entity entity = mc.level.getEntity(avatar.id);
            if (!(entity instanceof Player player)) return name;

            UUID uuid = player.getUUID();
            if (!Presence.hasBadge(uuid)) return name;

            // Three parts rather than one string, because the separating space
            // must not be in the badge font - that font has a single glyph and
            // anything else in it renders as a missing character box.
            MutableComponent out = Component.literal("");
            out.append(Component.literal(STANDARD).setStyle(BADGE_STYLE));
            out.append(Component.literal(" "));
            out.append(name);
            return out;

        } catch (Throwable ignored) {
            // A badge is never worth a broken frame
            return name;
        }
    }

    private NameBadge() {}
}
