package dev.mizarc.waystonewarps.compat.anvil;

import com.github.stefvanschie.inventoryframework.abstraction.AnvilInventory;
import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder;
import com.github.stefvanschie.inventoryframework.nms.v26_1.util.TextHolderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.bukkit.craftbukkit.inventory.CraftInventoryAnvil;
import org.bukkit.craftbukkit.inventory.view.CraftAnvilView;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;

/**
 * Drop-in replacement for InventoryFramework's (broken) {@code nms.v26_1.AnvilInventoryImpl}.
 * <p>
 * IF's shipped implementation reads the private NMS field {@code Slot.slot} with a raw,
 * compile-time field access (no reflection):
 * <pre>{@code Slot newSlot = new Slot(container, slot.slot, slot.x, slot.y);}</pre>
 * That only compiles - and only works at runtime - against IF's own build-time dependency
 * ({@code org.spigotmc:spigot:26.1.2-R0.1-SNAPSHOT}, a BuildTools-produced jar whose access
 * transformers widen that field's visibility). On a real Paper/Purpur 26.1+ runtime, which is
 * genuinely Mojang-mapped with no more spigot-style AT pipeline, {@code Slot.slot} is truly
 * private, so IF's compiled bytecode throws:
 * <pre>
 * java.lang.IllegalAccessError: tried to access private field net.minecraft.world.inventory.Slot.slot
 * </pre>
 * Verified against both the published {@code v0.12.0} tag and current {@code master}
 * (0.12.1-SNAPSHOT) of https://github.com/stefvanschie/IF - unfixed upstream as of this writing.
 * <p>
 * This class is a behavioral copy of IF's implementation, with that one field read going through
 * reflection instead. {@code Slot.x}, {@code Slot.y} and {@code Slot.index} are genuinely public
 * fields in vanilla Minecraft (that's why only {@code .slot} shows up in the crash), so those are
 * still read directly, exactly as IF does.
 * <p>
 * Wired in at plugin startup by {@link AnvilInventoryPatch}, which swaps this class into IF's
 * internal version-dispatch table in place of the broken one. Once IF ships an upstream fix, this
 * whole {@code compat.anvil} package and the {@code AnvilInventoryPatch.apply(...)} call in
 * {@code WaystoneWarps.onEnable()} can be deleted.
 *
 * @since 1.1.1
 */
public class FixedAnvilInventoryImpl extends AnvilInventory {

    private static final Field SLOT_FIELD;

    static {
        Field field;

        try {
            field = Slot.class.getDeclaredField("slot");
            field.setAccessible(true);
        } catch (NoSuchFieldException exception) {
            // Mojang mappings changed the field name/layout - this patch needs updating to match.
            throw new ExceptionInInitializerError(
                "FixedAnvilInventoryImpl: Slot no longer has a 'slot' field (" + exception.getMessage() + ")"
            );
        }

        SLOT_FIELD = field;
    }

    private static int readSlotIndex(@NotNull Slot slot) {
        try {
            return SLOT_FIELD.getInt(slot);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Could not reflectively read Slot.slot", exception);
        }
    }

    @NotNull
    @Override
    public Inventory createInventory(@NotNull TextHolder title) {
        SimpleContainer inputSlots = new SimpleContainer(2);
        SimpleContainer resultSlot = new SimpleContainer(1);

        return new CraftInventoryAnvil(null, inputSlots, resultSlot) {
            @NotNull
            @Override
            public InventoryType getType() {
                return InventoryType.ANVIL;
            }

            @Override
            public Container getInventory() {
                return new InventoryViewProvider() {
                    @NotNull
                    @Override
                    public AbstractContainerMenu createMenu(
                            int containerId,
                            @Nullable net.minecraft.world.entity.player.Inventory inventory,
                            @NotNull Player player
                    ) {
                        return new ContainerAnvilImpl(containerId, player, inputSlots, resultSlot);
                    }

                    @NotNull
                    @Override
                    public Component getDisplayName() {
                        return TextHolderUtil.toComponent(title);
                    }
                };
            }
        };
    }

    /**
     * Same trick IF uses: a container that is also a menu provider lets CraftBukkit build a
     * custom menu instead of picking a built-in one.
     */
    private abstract static class InventoryViewProvider extends SimpleContainer implements MenuProvider {}

    private class ContainerAnvilImpl extends AnvilMenu {

        @NotNull
        private final SimpleContainer inputSlots;

        @NotNull
        private final SimpleContainer resultSlot;

        @Nullable
        private CraftAnvilView bukkitEntity;

        ContainerAnvilImpl(
                int containerId,
                @NotNull Player player,
                @NotNull SimpleContainer inputSlots,
                @NotNull SimpleContainer resultSlot
        ) {
            super(containerId, player.getInventory(), ContainerLevelAccess.create(player.level(), BlockPos.ZERO));

            this.inputSlots = inputSlots;
            this.resultSlot = resultSlot;

            this.checkReachable = false;
            this.cost.set(FixedAnvilInventoryImpl.super.cost);

            CompoundContainer compoundContainer = new CompoundContainer(inputSlots, resultSlot);

            updateSlot(0, compoundContainer);
            updateSlot(1, compoundContainer);
            updateSlot(2, compoundContainer);
        }

        @Override
        public CraftAnvilView getBukkitView() {
            if (this.bukkitEntity != null) {
                return this.bukkitEntity;
            }

            CraftInventoryAnvil inventory = new CraftInventoryAnvil(
                    this.access.getLocation(),
                    this.inputSlots,
                    this.resultSlot
            );

            this.bukkitEntity = new CraftAnvilView(this.player.getBukkitEntity(), inventory, this);
            this.bukkitEntity.updateFromLegacy(inventory);

            return this.bukkitEntity;
        }

        @Override
        public void broadcastChanges() {
            if (super.cost.checkAndClearUpdateFlag()) {
                broadcastFullState();
            } else {
                for (int index = 0; index < super.slots.size(); index++) {
                    if (!super.remoteSlots.get(index).matches(super.slots.get(index).getItem())) {
                        broadcastFullState();
                        return;
                    }
                }
            }
        }

        @Override
        public boolean setItemName(@Nullable String name) {
            name = name == null ? "" : name;

            if (!name.equals(FixedAnvilInventoryImpl.super.observableText.get())) {
                FixedAnvilInventoryImpl.super.observableText.set(name);
            }

            //the client predicts the output result, so we broadcast the state again to override it
            broadcastFullState();
            return true;
        }

        @Override
        public void slotsChanged(Container container) {
            broadcastChanges();
        }

        @Override
        public void clicked(int index, int dragData, ContainerInput containerInput, Player player) {
            super.clicked(index, dragData, containerInput, player);

            //client predicts first slot, so send data to override
            broadcastFullState();
        }

        @Override
        public void createResult() {}

        @Override
        public void removed(@NotNull Player nmsPlayer) {}

        @Override
        protected void clearContainer(@NotNull Player player, @NotNull Container inventory) {}

        @Override
        protected void onTake(@NotNull Player player, @NotNull ItemStack stack) {}

        /**
         * Same as IF's original updateSlot, except {@code slot.slot} is read reflectively instead
         * of via a raw field access - that's the one line this whole class exists to fix.
         */
        private void updateSlot(int slotIndex, @NotNull Container container) {
            Slot slot = super.slots.get(slotIndex);

            Slot newSlot = new Slot(container, readSlotIndex(slot), slot.x, slot.y);
            newSlot.index = slot.index;

            super.slots.set(slotIndex, newSlot);
        }
    }
}
