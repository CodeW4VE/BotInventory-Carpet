package froyln.botinventory.mixin;

import carpet.patches.EntityPlayerMPFake;
import froyln.botinventory.BotInventoryRules;
import froyln.botinventory.ViewCommand;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerEntity.class)
public abstract class PlayerEntityInteractMixin {

    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void onInteract(Entity target, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        if (target instanceof EntityPlayerMPFake fakePlayer) {
            if ((Object) this instanceof ServerPlayerEntity viewer) {
                if (ViewCommand.isViewAllowed(viewer.getCommandSource(), BotInventoryRules.viewFakePlayerInventoryRightClick)) {
                    ViewCommand.openInventory(viewer, fakePlayer);
                    cir.setReturnValue(ActionResult.SUCCESS);
                }
            }
        }
    }
}
