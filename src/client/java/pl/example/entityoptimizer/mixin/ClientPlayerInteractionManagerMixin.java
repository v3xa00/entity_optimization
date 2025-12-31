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

@Mixin(MultiPlayerGameMode.class)
public class ClientPlayerInteractionManagerMixin {

    /**
     * Blokuje interakcje z entity:
     * - wszystkie wagoniki (AbstractMinecart),
     * - villagerzy bez profesji (NONE, NITWIT).
     */
    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void entity_optimizer$cancelMinecartAndVillagerInteract(Player player,
                                                                    Entity entity,
                                                                    InteractionHand hand,
                                                                    CallbackInfoReturnable<InteractionResult> cir) {
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
