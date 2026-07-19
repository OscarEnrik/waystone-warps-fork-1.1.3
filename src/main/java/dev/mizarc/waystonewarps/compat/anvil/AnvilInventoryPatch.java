package dev.mizarc.waystonewarps.compat.anvil;

import com.github.stefvanschie.inventoryframework.abstraction.AnvilInventory;
import com.github.stefvanschie.inventoryframework.util.version.Version;
import com.github.stefvanschie.inventoryframework.util.version.VersionMatcher;

import java.lang.reflect.Field;
import java.util.EnumMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Swaps {@link FixedAnvilInventoryImpl} into InventoryFramework's internal version-dispatch table
 * in place of IF's own (broken) {@code nms.v26_1.AnvilInventoryImpl}, so every
 * {@code AnvilGui.show()} call on Minecraft 26.1+ uses ours instead. See
 * {@link FixedAnvilInventoryImpl} for why this is necessary.
 * <p>
 * Call {@link #apply(Logger)} once, as early as possible in {@code onEnable()} - before any
 * anvil-based menu (warp naming/renaming, group create/rename, search, etc.) can possibly open.
 *
 * @since 1.1.1
 */
public final class AnvilInventoryPatch {

    private AnvilInventoryPatch() {}

    @SuppressWarnings("unchecked")
    public static void apply(Logger logger) {
        try {
            Field field = VersionMatcher.class.getDeclaredField("ANVIL_INVENTORIES");
            field.setAccessible(true);

            EnumMap<Version, Class<? extends AnvilInventory>> anvilInventories =
                    (EnumMap<Version, Class<? extends AnvilInventory>>) field.get(null);

            if (!anvilInventories.containsKey(Version.V26_1)) {
                // Nothing to patch - either IF dropped 26.1 support or this server isn't 26.1+.
                return;
            }

            anvilInventories.put(Version.V26_1, FixedAnvilInventoryImpl.class);
            logger.info(
                "Patched InventoryFramework's anvil GUI implementation for Minecraft 26.1+ " +
                "(see FixedAnvilInventoryImpl - works around an upstream IF bug: " +
                "https://github.com/stefvanschie/IF)."
            );
        } catch (ReflectiveOperationException | ClassCastException exception) {
            logger.log(
                Level.WARNING,
                "Could not patch InventoryFramework's anvil GUI implementation. Anvil-based menus " +
                "(warp naming/renaming, group create/rename, search, etc.) may crash with " +
                "IllegalAccessError on this server version. This likely means IF's internal " +
                "structure changed and AnvilInventoryPatch needs updating.",
                exception
            );
        }
    }
}
