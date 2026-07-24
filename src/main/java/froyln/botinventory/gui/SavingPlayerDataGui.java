package froyln.botinventory.gui;

import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.screen.ScreenHandlerType;

public class SavingPlayerDataGui extends SimpleGui {
    private final ServerPlayerEntity savedPlayer;

    public SavingPlayerDataGui(ScreenHandlerType<?> type, ServerPlayerEntity player, ServerPlayerEntity savedPlayer) {
        super(type, player, false);
        this.savedPlayer = savedPlayer;
    }

    @Override
    public void onClose() {
    }
}
