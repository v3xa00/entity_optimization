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

    // flaga, żeby uniknąć rekurencji przy naszym ponownym wywołaniu useItemOn
    private static boolean entity_optimizer$placingPainting = false;

    /**
     * - SHIFT + PPM na graczu: tooltip miecza
     * - blokada wagoników
     * - blokada villagerów bez profesji
     */
    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void entity_optimizer$interact(Player player,
                                           Entity entity,
                                           InteractionHand hand,
                                           CallbackInfoReturnable<InteractionResult> cir) {

        // SHIFT + PPM na innym graczu -> tooltip miecza
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

    /**
     * - blokada craftingu (zależna od EntityOptimizerMod.craftingDisabled)
     * - PPM obrazem w cobweb/banner:
     *   robimy własny raycast od oczu, IGNORUJĄC WSZYSTKIE COBWEBY/BANNERY,
     *   pierwszy normalny blok, który trafi ray, traktujemy jako ścianę
     *   (dokładnie ten, na który patrzysz za cobwebami/bannerami).
     */
    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void entity_optimizer$placePaintingThroughBlocks(LocalPlayer player,
                                                             InteractionHand hand,
                                                             BlockHitResult hit,
                                                             CallbackInfoReturnable<InteractionResult> cir) {
        if (entity_optimizer$placingPainting) {
            return;
        }
        if (minecraft == null || minecraft.level == null) {
            return;
        }

        BlockPos clickedPos = hit.getBlockPos();
        BlockState clickedState = minecraft.level.getBlockState(clickedPos);

        // blokada craftingu
        if (EntityOptimizerMod.craftingDisabled && clickedState.is(Blocks.CRAFTING_TABLE)) {
            cir.setReturnValue(InteractionResult.FAIL);
            cir.cancel();
            return;
        }

        if (hand != InteractionHand.MAIN_HAND) {
            return;
        }

        ItemStack stack = player.getItemInHand(hand);
        if (!stack.is(Items.PAINTING)) {
            return;
        }

        // reagujemy tylko jeśli GUI mówi, że kliknęliśmy w cobweb lub banner
        if (!isThroughBlock(clickedState)) {
            return;
        }

        // Własny raycast od oczu, ignorujący cobweby/bannery
        BlockHitResult wallHit = findWallIgnoringThroughBlocks(player);
        if (wallHit == null || wallHit.getType() != HitResult.Type.BLOCK) {
            return;
        }

        BlockPos wallPos = wallHit.getBlockPos();
        BlockState wallState = minecraft.level.getBlockState(wallPos);
        Direction wallFace = wallHit.getDirection();

        // ściana musi być solidna na tej twarzy
        if (!wallState.isFaceSturdy(minecraft.level, wallPos, wallFace)) {
            return;
        }

        // udajemy kliknięcie dokładnie tej ściany, którą widzisz
        BlockHitResult newHit = new BlockHitResult(
                wallHit.getLocation(),
                wallFace,
                wallPos,
                false
        );

        entity_optimizer$placingPainting = true;
        InteractionResult result = ((MultiPlayerGameMode) (Object) this)
                .useItemOn(player, hand, newHit);
        entity_optimizer$placingPainting = false;

        cir.setReturnValue(result);
        cir.cancel();
    }

    /**
     * Raycast od oczu gracza w kierunku wzroku:
     * - jeśli trafiamy cobweb/banner -> przesuwamy start tuż za niego i szukamy dalej,
     * - pierwszy inny blok (OUTLINE) to nasza ściana.
     */
    private BlockHitResult findWallIgnoringThroughBlocks(LocalPlayer player) {
        final int MAX_STEPS = 8;
        final double REACH = 6.0D;

        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 look = player.getViewVector(1.0F);

        Vec3 from = eye;

        for (int i = 0; i < MAX_STEPS; i++) {
            Vec3 to = eye.add(look.scale(REACH));

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
                // ignorujemy ten blok – przesuwamy start tuż za nim i szukamy dalej
                from = res.getLocation().add(look.scale(0.05D));
                continue;
            }

            // trafiliśmy normalny blok – to będzie nasza ściana
            return res;
        }

        return null;
    }

    // Czy blok jest takim, przez który "przekładamy" obrazy
    private static boolean isThroughBlock(BlockState state) {
        if (state.is(Blocks.COBWEB)) {
            return true;
        }
        return state.getBlock() instanceof BannerBlock
                || state.getBlock() instanceof WallBannerBlock;
    }
}
