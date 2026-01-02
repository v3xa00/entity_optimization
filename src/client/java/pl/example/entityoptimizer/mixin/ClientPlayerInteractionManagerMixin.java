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

    // ========== ENTITY INTERACT ==========

    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void eo$interact(Player player,
                             Entity entity,
                             InteractionHand hand,
                             CallbackInfoReturnable<InteractionResult> cir) {

        if (entity instanceof Player target
                && target != player
                && player.isCrouching()
                && hand == InteractionHand.MAIN_HAND) {

            System.out.println("[EO-DBG-1.20] interact: SHIFT+PPM na graczu " + target.getGameProfile().getName());
            EntityOptimizerMod.onShiftRightClickPlayer(target);
        }

        if (entity instanceof AbstractMinecart) {
            System.out.println("[EO-DBG-1.20] interact: blokuję PPM na minecarcie");
            cir.setReturnValue(InteractionResult.FAIL);
            cir.cancel();
            return;
        }

        if (entity instanceof Villager villager) {
            VillagerProfession profession = villager.getVillagerData().getProfession();
            if (profession == VillagerProfession.NONE || profession == VillagerProfession.NITWIT) {
                System.out.println("[EO-DBG-1.20] interact: blokuję PPM na villagerze bez profesji");
                cir.setReturnValue(InteractionResult.FAIL);
                cir.cancel();
            }
        }
    }

    // ========== BLOCK USE ==========

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void eo$useItemOn(LocalPlayer player,
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

        System.out.println("[EO-DBG-1.20] useItemOn: hand=" + hand
                + " stack=" + player.getItemInHand(hand).getItem()
                + " clicked=" + clickedState.getBlock()
                + " pos=" + clickedPos);

        // blokada craftingu
        if (EntityOptimizerMod.craftingDisabled && clickedState.is(Blocks.CRAFTING_TABLE)) {
            System.out.println("[EO-DBG-1.20] useItemOn: craftingDisabled=true, blokuję crafting");
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

        if (!isThroughBlock(clickedState)) {
            System.out.println("[EO-DBG-1.20] useItemOn: to NIE jest cobweb/banner – nie zmieniam nic");
            return;
        }

        System.out.println("[EO-DBG-1.20] useItemOn: kliknięto throughBlock=" + clickedState.getBlock());

        // kierunek, w który MA wisieć obraz (strona ściany)
        Direction wallFace = hit.getDirection();
        // kierunek DO ściany (od tego bloku w głąb)
        Direction toWall = wallFace.getOpposite();

        BlockPos cur = clickedPos;
        BlockPos wallPos = null;

        for (int i = 0; i < 8; i++) {
            cur = cur.relative(toWall);
            BlockState s = minecraft.level.getBlockState(cur);
            System.out.println("[EO-DBG-1.20] scan step " + i + " pos=" + cur + " block=" + s.getBlock());

            if (isThroughBlock(s)) {
                System.out.println("[EO-DBG-1.20] step " + i + ": kolejny throughBlock – idę dalej");
                continue;
            }

            wallPos = cur;
            System.out.println("[EO-DBG-1.20] znaleziono ścianę na pos=" + wallPos + " block=" + s.getBlock());
            break;
        }

        if (wallPos == null) {
            System.out.println("[EO-DBG-1.20] NIE znaleziono ściany w 8 krokach – nie stawiam obrazu");
            return;
        }

        BlockState wallState = minecraft.level.getBlockState(wallPos);

        if (!wallState.isFaceSturdy(minecraft.level, wallPos, wallFace)) {
            System.out.println("[EO-DBG-1.20] ściana na pos=" + wallPos + " nie jest sturdy na face=" + wallFace);
            return;
        }

        Vec3 hitVec = Vec3.atCenterOf(wallPos);
        BlockHitResult newHit = new BlockHitResult(hitVec, wallFace, wallPos, false);

        System.out.println("[EO-DBG-1.20] wywołuję vanilla useItemOn na wallPos=" + wallPos);

        entity_optimizer$placingPainting = true;
        InteractionResult result = ((MultiPlayerGameMode) (Object) this)
                .useItemOn(player, hand, newHit);
        entity_optimizer$placingPainting = false;

        System.out.println("[EO-DBG-1.20] vanilla useItemOn zwrócił " + result);

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
