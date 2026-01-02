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
import org.spongepowered.asmination.injection.At;
import org.spongepowered.asmination.injection.Inject;
import org.spongepowered.asmination.injection.callback.CallbackInfoReturnable;
import pl.example.entityoptimizer.EntityOptimizerMod;

@Mixin(MultiPlayerGameMode.class)
public class ClientPlayerInteractionManagerMixin {

    @Shadow private Minecraft minecraft;

    private static boolean entity_optimizer$placingPainting = false;

    // ========== ENTITY INTERACT ==========

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

            System.out.println("[EO-DBG] interact: SHIFT+PPM na graczu " + target.getGameProfile().getName());
            EntityOptimizerMod.onShiftRightClickPlayer(target);
        }

        // blokada wagoników
        if (entity instanceof AbstractMinecart) {
            System.out.println("[EO-DBG] interact: blokuję PPM na minecarcie");
            cir.setReturnValue(InteractionResult.FAIL);
            cir.cancel();
            return;
        }

        // blokada villagerów bez profesji
        if (entity instanceof Villager villager) {
            VillagerProfession profession = villager.getVillagerData().getProfession();
            if (profession == VillagerProfession.NONE || profession == VillagerProfession.NITWIT) {
                System.out.println("[EO-DBG] interact: blokuję PPM na villagerze bez profesji");
                cir.setReturnValue(InteractionResult.FAIL);
                cir.cancel();
            }
        }
    }

    // ========== BLOCK USE (crafting, obrazy) ==========

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

        System.out.println("[EO-DBG] useItemOn: hand=" + hand
                + " stack=" + player.getItemInHand(hand).getItem()
                + " clicked=" + clickedState.getBlock()
                + " pos=" + clickedPos);

        // blokada craftingu
        if (EntityOptimizerMod.craftingDisabled && clickedState.is(Blocks.CRAFTING_TABLE)) {
            System.out.println("[EO-DBG] useItemOn: craftingDisabled=true, blokuję crafting table");
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
            System.out.println("[EO-DBG] useItemOn: to NIE jest cobweb/banner – nie zmieniam nic");
            return;
        }
        System.out.println("[EO-DBG] useItemOn: kliknięto throughBlock=" + clickedState.getBlock());

        // Własny raycast od oczu, ignorujący cobweby/bannery
        BlockHitResult wallHit = findWallIgnoringThroughBlocks(player);
        if (wallHit == null || wallHit.getType() != HitResult.Type.BLOCK) {
            System.out.println("[EO-DBG] useItemOn: wallHit == null lub nie BLOCK – nie stawiam obrazu");
            return;
        }

        BlockPos wallPos = wallHit.getBlockPos();
        BlockState wallState = minecraft.level.getBlockState(wallPos);
        Direction wallFace = wallHit.getDirection();

        System.out.println("[EO-DBG] useItemOn: wallHit pos=" + wallPos + " block=" + wallState.getBlock() + " face=" + wallFace);

        // ściana musi być solidna na tej twarzy
        if (!wallState.isFaceSturdy(minecraft.level, wallPos, wallFace)) {
            System.out.println("[EO-DBG] useItemOn: wallState nie jest sturdy na face=" + wallFace + " – rezygnuję");
            return;
        }

        // udajemy kliknięcie dokładnie tej ściany, którą widzi gracz
        BlockHitResult newHit = new BlockHitResult(
                wallHit.getLocation(),
                wallFace,
                wallPos,
                false
        );

        System.out.println("[EO-DBG] useItemOn: próbuję wywołać useItemOn na wallPos=" + wallPos);

        entity_optimizer$placingPainting = true;
        InteractionResult result = ((MultiPlayerGameMode) (Object) this)
                .useItemOn(player, hand, newHit);
        entity_optimizer$placingPainting = false;

        System.out.println("[EO-DBG] useItemOn: vanilla useItemOn zwrócił " + result);

        cir.setReturnValue(result);
        cir.cancel();
    }

    /**
     * Raycast od oczu gracza w kierunku wzroku:
     * - jeśli trafiamy cobweb/banner -> przesuwamy FROM wyraźnie ZA ten blok (o 0.6 w kierunku wzroku)
     *   i szukamy dalej z nowym FROM,
     * - pierwszy inny blok (OUTLINE) to nasza ściana.
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
                System.out.println("[EO-DBG] findWall: clip -> " + generic.getType() + " – kończę z null");
                return null;
            }

            BlockHitResult res = (BlockHitResult) generic;
            BlockPos pos = res.getBlockPos();
            BlockState state = minecraft.level.getBlockState(pos);
            System.out.println("[EO-DBG] findWall: trafiono pos=" + pos + " block=" + state.getBlock());

            if (isThroughBlock(state)) {
                // PRZESUŃ SIĘ WYRAŹNIE ZA TEN BLOK (0.6 w kierunku wzroku), żeby nie trafiać go ponownie
                from = res.getLocation().add(look.scale(0.6D));
                System.out.println("[EO-DBG] findWall: to throughBlock – nowy FROM=" + from);
                continue;
            }

            System.out.println("[EO-DBG] findWall: znaleziono ścianę pos=" + pos + " block=" + state.getBlock());
            return res;
        }

        System.out.println("[EO-DBG] findWall: nie znaleziono ściany po " + MAX_STEPS + " krokach");
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
