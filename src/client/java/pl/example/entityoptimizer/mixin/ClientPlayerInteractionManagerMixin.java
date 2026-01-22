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
                              BlockHitResult originalHit,
                              CallbackInfoReturnable<InteractionResult> cir) {
        if (placingPainting) return;
        if (minecraft == null || minecraft.level == null) return;

        BlockPos clickedPos = originalHit.getBlockPos();
        BlockState clickedState = minecraft.level.getBlockState(clickedPos);

        // blokada craftingu wg /kraft
        if (EntityOptimizerMod.craftingDisabled && clickedState.is(Blocks.CRAFTING_TABLE)) {
            cir.setReturnValue(InteractionResult.FAIL);
            cir.cancel();
            return;
        }

        if (hand != InteractionHand.MAIN_HAND) return;

        ItemStack stack = player.getItemInHand(hand);

        // blokada wkładania itemów do decorated pot – jeśli trzymasz item, klik na wazon nic nie robi
        if (clickedState.is(Blocks.DECORATED_POT) && !stack.isEmpty()) {
            cir.setReturnValue(InteractionResult.FAIL);
            cir.cancel();
            return;
        }

        // dalej logika obrazów – tylko jeśli trzymasz painting
        if (!stack.is(Items.PAINTING)) return;

        // 1. Raycast od oczu, IGNORUJĄCY wszystkie cobweby/bannery
        BlockHitResult wallHit = findWallIgnoringThroughBlocks(player);
        if (wallHit == null || wallHit.getType() != HitResult.Type.BLOCK) {
            return;
        }

        BlockPos wallPos = wallHit.getBlockPos();
        BlockState wallState = minecraft.level.getBlockState(wallPos);

        // 2. Oblicz stronę ściany na podstawie położenia gracza względem bloku
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 center = Vec3.atCenterOf(wallPos);
        Vec3 diff = eye.subtract(center); // wektor: blok -> gracz

        double ax = Math.abs(diff.x);
        double az = Math.abs(diff.z);

        Direction wallFace;
        if (ax > az) {
            wallFace = diff.x > 0 ? Direction.EAST : Direction.WEST;
        } else {
            wallFace = diff.z > 0 ? Direction.SOUTH : Direction.NORTH;
        }

        // 3. Ściana musi być solidna na tej twarzy
        if (!wallState.isFaceSturdy(minecraft.level, wallPos, wallFace)) {
            return;
        }

        // 4. Udajemy kliknięcie na tej ścianie (środek bloku + pół bloku w stronę wallFace)
        Vec3 offset = new Vec3(
                wallFace.getStepX() * 0.5,
                wallFace.getStepY() * 0.5,
                wallFace.getStepZ() * 0.5
        );
        Vec3 hitPos = center.add(offset);

        BlockHitResult newHit = new BlockHitResult(hitPos, wallFace, wallPos, false);

        placingPainting = true;
        InteractionResult result = ((MultiPlayerGameMode) (Object) this)
                .useItemOn(player, hand, newHit);
        placingPainting = false;

        cir.setReturnValue(result);
        cir.cancel();
    }

    /**
     * Raycast od oczu gracza w kierunku wzroku:
     * - jeśli trafiamy cobweb/banner -> przesuwamy FROM wyraźnie ZA ten blok (0.6 w kierunku wzroku)
     *   i szukamy dalej z nowym FROM,
     * - pierwszy inny blok (OUTLINE) to ściana docelowa.
     */
    private BlockHitResult findWallIgnoringThroughBlocks(LocalPlayer player) {
        final int MAX_STEPS = 8;
        final double STEP_REACH = 6.0D;

        Vec3 look = player.getViewVector(1.0F).normalize();
        Vec3 from = player.getEyePosition(1.0F);

        for (int i = 0; i < MAX_STEPS; i++) {
            Vec3 to = from.add(look.scale(STEP_REACH));

            ClipContext ctx = new ClipContext(
                    from,
                    to,
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    player
            );

            HitResult generic = minecraft.level.clip(ctx);
            if (generic.getType() != HitResult.Type.BLOCK) {
                return null;
            }

            BlockHitResult res = (BlockHitResult) generic;
            BlockPos pos = res.getBlockPos();
            BlockState state = minecraft.level.getBlockState(pos);

            if (isThroughBlock(state)) {
                // przeskocz wyraźnie za ten blok, żeby nie trafiać go ponownie
                from = res.getLocation().add(look.scale(0.6D));
                continue;
            }

            return res;
        }

        return null;
    }

    // Czy blok jest cobwebem lub bannerem (stojącym/ściennym)
    private static boolean isThroughBlock(BlockState state) {
        if (state.is(Blocks.COBWEB)) {
            return true;
        }
        return state.getBlock() instanceof BannerBlock
                || state.getBlock() instanceof WallBannerBlock;
    }
}
