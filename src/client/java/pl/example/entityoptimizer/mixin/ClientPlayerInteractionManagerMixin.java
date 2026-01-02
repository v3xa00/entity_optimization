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
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
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

    private static boolean placingPainting = false;

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
        if (placingPainting) return;
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

        // 1. Raycast od oczu, ignorujący WSZYSTKIE cobweby/bannery
        BlockHitResult wallHit = findWallIgnoringThroughBlocks(player);
        if (wallHit == null || wallHit.getType() != HitResult.Type.BLOCK) {
            return;
        }

        BlockPos wallPos = wallHit.getBlockPos();
        BlockState wallState = minecraft.level.getBlockState(wallPos);
        Direction wallFace = wallHit.getDirection();

        // 2. Jeśli ściana trafiona od góry/dół, zamieniamy na pionową ścianę po stronie gracza
        if (wallFace == Direction.UP || wallFace == Direction.DOWN) {
            wallFace = player.getDirection().getOpposite(); // ściana widoczna dla gracza
        }

        // 3. Ściana musi być solidna na tej twarzy
        if (!wallState.isFaceSturdy(minecraft.level, wallPos, wallFace)) {
            return;
        }

        // 4. Udajemy kliknięcie na tej ścianie (środek + pół bloku w stronę wallFace)
        Vec3 center = Vec3.atCenterOf(wallPos);
        Vec3 
