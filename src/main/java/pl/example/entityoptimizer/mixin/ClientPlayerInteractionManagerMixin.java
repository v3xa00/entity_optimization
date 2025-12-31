package pl.example.entityoptimizer.mixin;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.village.VillagerProfession;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
public class ClientPlayerInteractionManagerMixin {

    @Inject(method = "interactEntity", at = @At("HEAD"), cancellable = true)
    private void entity_optimizer$cancelMinecartAndVillagerInteract(PlayerEntity player,
                                                                    Entity entity,
                                                                    Hand hand,
                                                                    CallbackInfoReturnable<ActionResult> cir) {
        // 1) Blokuj interakcje z dowolnym wagonikiem (minecart)
        if (entity instanceof AbstractMinecartEntity) {
            cir.setReturnValue(ActionResult.FAIL);
            cir.cancel();
            return;
        }

        // 2) Blokuj interakcje z villagerami bez profesji (NONE, NITWIT)
        if (entity instanceof VillagerEntity villager) {
            VillagerProfession profession = villager.getVillagerData().getProfession();

            if (profession == VillagerProfession.NONE || profession == VillagerProfession.NITWIT) {
                // Nie wysyłaj pakietu interakcji – klient „nic nie robi”,
                // więc nie ma dźwięku i nie blokuje to użycia złotego jabłka.
                cir.setReturnValue(ActionResult.FAIL);
                cir.cancel();
            }
        }
    }
}
