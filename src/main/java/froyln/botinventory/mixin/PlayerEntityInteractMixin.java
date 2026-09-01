package froyln.botinventory.mixin;

import carpet.patches.EntityPlayerMPFake;
import froyln.botinventory.BotInventoryRules;
import froyln.botinventory.ViewCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerEntityInteractMixin {

    @Inject(method = "interactOn", at = @At("HEAD"), cancellable = true)
    private void onInteract(Entity target, InteractionHand hand, Vec3 hitPos, CallbackInfoReturnable<InteractionResult> cir) {
        if (target instanceof EntityPlayerMPFake fakePlayer) {
            if ((Object) this instanceof ServerPlayer viewer) {
                if (ViewCommand.isViewAllowed(viewer.createCommandSourceStack(), BotInventoryRules.viewFakePlayerInventoryRightClick)) {
                    ViewCommand.openInventory(viewer, fakePlayer);
                    cir.setReturnValue(InteractionResult.SUCCESS);
                }
            }
        }
    }
}
