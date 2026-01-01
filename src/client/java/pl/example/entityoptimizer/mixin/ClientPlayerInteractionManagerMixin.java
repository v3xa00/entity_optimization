package pl.example.entityoptimizer.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.example.entityoptimizer.EntityOptimizerMod;

@Mixin(MultiPlayerGameMode.class)
public class ClientPlayerInteractionManagerMixin {

    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void entity_optimizer$interact(Player player,
                                           Entity entity,
                                           InteractionHand hand,
                                           CallbackInfoReturnable<InteractionResult> cir) {

        // SHIFT + PPM na innym graczu -> pokaż tooltip miecza na czacie
        if (entity instanceof Player target
                && target != player
                && player.isCrouching()   // trzymany Shift (sneak)
                && hand == InteractionHand.MAIN_HAND) {

            EntityOptimizerMod.onShiftRightClickPlayer(target);
            // Nie anulujemy – pozwalamy vanilla dalej działać (i tak zwykle nic nie robi)
        }

        // 1) Blokuj interakcje z dowolnym wagonikiem (minecart)
        if (entity instanceof AbstractMinecart) {
            cir.setReturnValue(InteractionResult.FAIL);
            cir.cancel();
            return;
        }

        // 2) Blokuj interakcje z villagerami bez profesji (NONE, NITWIT)
        if (entity instanceof Villager villager) {
            VillagerProfession profession = villager.getVillagerData().getProfession();

            if (profession == VillagerProfession.NONE || profession == VillagerProfession.NITWIT) {
                cir.setReturnValue(InteractionResult.FAIL);
                cir.cancel();
            }
        }
    }
}
