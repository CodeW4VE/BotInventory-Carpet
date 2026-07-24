package froyln.botinventory.gui;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class UnmodifiableSlot extends Slot {
    public UnmodifiableSlot(Container inventory, int index) {
        super(inventory, index, 0, 0);
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return false;
    }

    @Override
    public boolean mayPickup(Player player) {
        return false;
    }

    @Override
    public boolean allowModification(Player player) {
        return false;
    }

    @Override
    public ItemStack remove(int amount) {
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack safeInsert(ItemStack stack, int count) {
        return stack;
    }

    @Override
    public void setByPlayer(ItemStack stack) {
    }

    @Override
    public void set(ItemStack stack) {
    }
}
