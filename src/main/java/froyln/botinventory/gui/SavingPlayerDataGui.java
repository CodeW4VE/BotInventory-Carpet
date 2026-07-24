package froyln.botinventory.gui;

import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;

public class SavingPlayerDataGui extends SimpleGui {
    private final ServerPlayer savedPlayer;

    public SavingPlayerDataGui(MenuType<?> type, ServerPlayer player, ServerPlayer savedPlayer) {
        super(type, player, false);
        this.savedPlayer = savedPlayer;
    }

    @Override
    public void onRemoved() {
        // ponytail: save player data when GUI closes
        // TODO: implement player data saving similar to InvView
    }
}
