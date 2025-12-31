package pl.example.entityoptimizer.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
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

    /**
     * Blokuje PPM na tabliczkach (już postawionych).
     * Działa na:
     * - zwykłe tabliczki (SignBlock – w tym ścienne),
     * - wiszące tabliczki: CeilingHangingSignBlock, WallHangingSignBlock.
     */
    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void entity_optimizer$cancelSignUse(Player player,
                                                InteractionHand hand,
                                                BlockHitResult hit,
                                                CallbackInfoReturnable<InteractionResult> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }

        BlockState state = mc.level.getBlockState(hit.getBlockPos());

        if (state.getBlock() instanceof SignBlock
                || state.getBlock() instanceof CeilingHangingSignBlock
                || state.getBlock() instanceof WallHangingSignBlock) {

            // Nie wysyłaj pakietu użycia bloku – PPM na tabliczce nic nie zrobi,
            // nie otworzy/nie pozwoli edytować, jakbyś klikał w powietrze.
            cir.setReturnValue(InteractionResult.FAIL);
            cir.cancel();
        }
    }
}
