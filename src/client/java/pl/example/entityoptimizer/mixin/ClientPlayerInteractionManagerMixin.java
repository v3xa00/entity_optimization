package pl.example.entityoptimizer.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.example.entityoptimizer.EntityOptimizerMod;

@Mixin(MultiPlayerGameMode.class)
public class ClientPlayerInteractionManagerMixin {

    @Shadow private Minecraft minecraft;

    private static boolean entity_optimizer$placingPainting = false;

    // ========= INTERACT (entity) =========

    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void eo$interact(Player player,
                             Entity entity,
                             InteractionHand hand,
                             CallbackInfoReturnable<InteractionResult> cir) {

        // SHIFT + PPM na graczu -> tooltip miecza
        if (entity instanceof Player target
                && target != player
                && player.isCrouching()
                && hand == InteractionHand.MAIN_HAND) {

            EntityOptimizerMod.onShiftRightClickPlayer(target);
        }

        // blokada wagoników
        if (entity instanceof AbstractMinecart) {
            cir.setReturnValue(InteractionResult.FAIL);
            cir.cancel();
            return;
        }

        // blokada villagerów bez profesji
        if (entity instanceof Villager villager) {
            VillagerProfession profession = villager.getVillagerData().getProfession();
            if (profession == VillagerProfession.NONE || profession == VillagerProfession.NITWIT) {
                cir.setReturnValue(InteractionResult.FAIL);
                cir.cancel();
            }
        }
    }

    // ========= useItemOn (bloki) =========

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void eo$useItemOn(LocalPlayer player,
                              InteractionHand hand,
                              BlockHitResult hit,
                              CallbackInfoReturnable<InteractionResult> cir) {
        if (entity_optimizer$placingPainting) return;
        if (minecraft == null || minecraft.level == null) return;

        BlockPos clickedPos = hit.getBlockPos();
        BlockState clickedState = minecraft.level.getBlockState(clickedPos);

        // blokada craftingu sterowana /kraft
        if (EntityOptimizerMod.craftingDisabled && clickedState.is(Blocks.CRAFTING_TABLE)) {
            cir.setReturnValue(InteractionResult.FAIL);
            cir.cancel();
            return;
        }

        if (hand != InteractionHand.MAIN_HAND) return;

        ItemStack stack = player.getItemInHand(hand);
        if (!stack.is(Items.PAINTING)) return;

        // reagujemy tylko na cobweb / bannery
        if (!isThroughBlock(clickedState)) return;

        // 1. wybierz twarz, na której ma wisieć obraz
        Direction hitFace = hit.getDirection();
        Direction wallFace;
        Direction toWall;

        if (hitFace == Direction.UP || hitFace == Direction.DOWN) {
            // z góry / z dołu: weź poziomy kierunek gracza
            wallFace = player.getDirection();         // NORTH/EAST/SOUTH/WEST
            toWall = wallFace.getOpposite();          // w głąb od tego cobweb'a
        } else {
            // z boku – jak wcześniej
            wallFace = hitFace;
            toWall = wallFace.getOpposite();
        }

        // 2. idziemy w głąb za klikniętym blokiem, omijając kolejne cobweby/bannery
        BlockPos cur = clickedPos;
        BlockPos wallPos = null;

        for (int i = 0; i < 8; i++) {
            cur = cur.relative(toWall);
            BlockState s = minecraft.level.getBlockState(cur);

            if (isThroughBlock(s)) {
                // kolejny cobweb/banner – idziemy dalej
                continue;
            }

            wallPos = cur;
            break;
        }

        if (wallPos == null) {
            // brak ściany w zasięgu
            return;
        }

        BlockState wallState = minecraft.level.getBlockState(wallPos);
        // ściana musi być solidna na tej twarzy
        if (!wallState.isFaceSturdy(minecraft.level, wallPos, wallFace)) {
            return;
        }

        // 3. udajemy kliknięcie na środku tej ściany (trochę „w stronę” wallFace)
        Vec3 center = Vec3.atCenterOf(wallPos);
        Vec3 offset = new Vec3(
                wallFace.getStepX() * 0.5,
                wallFace.getStepY() * 0.5,
                wallFace.getStepZ() * 0.5
        );
        Vec3 hitVec = center.add(offset);

        BlockHitResult newHit = new BlockHitResult(hitVec, wallFace, wallPos, false);

        entity_optimizer$placingPainting = true;
        InteractionResult result = ((MultiPlayerGameMode) (Object) this)
                .useItemOn(player, hand, newHit);
        entity_optimizer$placingPainting = false;

        cir.setReturnValue(result);
        cir.cancel();
    }

    private static boolean isThroughBlock(BlockState state) {
        if (state.is(Blocks.COBWEB)) {
            return true;
        }
        return state.getBlock() instanceof BannerBlock
                || state.getBlock() instanceof WallBannerBlock;
    }
}
