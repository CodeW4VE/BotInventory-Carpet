package froyln.botinventory.gui;

import net.minecraft.inventory.Inventory;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.slot.Slot;
import net.minecraft.item.ItemStack;

public class UnmodifiableSlot extends Slot {
    public UnmodifiableSlot(Inventory inventory, int index) {
        super(inventory, index, 0, 0);
    }

    @Override
    public boolean canInsert(ItemStack stack) {
        return false;
    }

    @Override
    public boolean canTakeItems(PlayerEntity player) {
        return false;
    }

    @Override
    public ItemStack takeStack(int amount) {
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack insertStack(ItemStack stack, int count) {
        return stack;
    }

    @Override
    public void onTakeItem(PlayerEntity player, ItemStack stack) {
    }

    @Override
    public void setStackNoCallbacks(ItemStack stack) {
    }
}
