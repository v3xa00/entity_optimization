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

    // flaga, żeby uniknąć rekurencji przy naszym ponownym wywołaniu useItemOn
    private static boolean entity_optimizer$placingPainting = false;

    /**
     * Blokuje interakcje z entity:
     * - wszystkie wagoniki (AbstractMinecart),
     * - villagerzy bez profesji (NONE, NITWIT),
     * + SHIFT + PPM na graczu pokazuje tooltip miecza.
     */
    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void entity_optimizer$interact(Player player,
                                           Entity entity,
                                           InteractionHand hand,
                                           CallbackInfoReturnable<InteractionResult> cir) {

        // SHIFT + PPM na innym graczu -> pokaż tooltip miecza na czacie
        if (entity instanceof Player target
                && target != player
                && player.isCrouching()
                && hand == InteractionHand.MAIN_HAND) {

            EntityOptimizerMod.onShiftRightClickPlayer(target);
            // nie anulujemy – vanilla i tak zwykle nic nie robi
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

    /**
     * PPM z obrazem (painting) na cobwebie lub bannerze:
     * - zamiast próbować „postawić obraz na cobwebie/bannerze”,
     *   stawiamy go NA ŚCIANIE ZA TYM BLOKIEM, po naszej stronie (tak jakby cobweb/banner nie istniał).
     *
     * Dodatkowo:
     * - blokujemy PPM na crafting table (żeby nie otwierał craftingu).
     *
     * Uwaga: w 1.20.1 MultiPlayerGameMode.useItemOn(LocalPlayer, ...) – stąd LocalPlayer w sygnaturze.
     */
    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void entity_optimizer$placePaintingThroughBlocks(LocalPlayer player,
                                                             InteractionHand hand,
                                                             BlockHitResult hit,
                                                             CallbackInfoReturnable<InteractionResult> cir) {
        if (entity_optimizer$placingPainting) {
            // to jest nasze własne, wtórne wywołanie – nie rób nic
            return;
        }

        if (minecraft == null || minecraft.level == null) {
            return;
        }

        BlockPos clickedPos = hit.getBlockPos();
        BlockState clickedState = minecraft.level.getBlockState(clickedPos);

        // 1) Blokowanie otwierania craftingów
        if (clickedState.is(Blocks.CRAFTING_TABLE)) {
            cir.setReturnValue(InteractionResult.FAIL);
            cir.cancel();
            return;
        }

        // 2) Stawianie obrazów przez cobweb / bannery (zawsze, bez Shifta)
        if (hand != InteractionHand.MAIN_HAND) {
            return;
        }

        ItemStack stack = player.getItemInHand(hand);
        if (!stack.is(Items.PAINTING)) {
            return;
        }

        // Czy kliknięty blok to cobweb lub jakiś banner
        if (!isThroughBlock(clickedState)) {
            return;
        }

        // Strona ściany, na której ma wisieć obraz (ta, którą widzisz)
        Direction wallFace = hit.getDirection();
        // Kierunek od klikniętego bloku DO ściany (od cobweb/banner do ściany)
        Direction toWall = wallFace.getOpposite();

        BlockPos wallPos = clickedPos.relative(toWall);
        BlockState wallState = minecraft.level.getBlockState(wallPos);

        // Ściana musi być solidna na tej twarzy, na której chcemy powiesić obraz
        if (!wallState.isFaceSturdy(minecraft.level, wallPos, wallFace)) {
            return;
        }

        // Symulujemy kliknięcie na środku tej ściany (jak bez cobweb/banner)
        Vec3 hitVec = Vec3.atCenterOf(wallPos);
        BlockHitResult newHit = new BlockHitResult(hitVec, wallFace, wallPos, false);

        // Wywołujemy vanilla useItemOn z nowym hitem, wyłączając nasz kod na ten czas
        entity_optimizer$placingPainting = true;
        InteractionResult result = ((MultiPlayerGameMode) (Object) this)
                .useItemOn(player, hand, newHit);
        entity_optimizer$placingPainting = false;

        cir.setReturnValue(result);
        cir.cancel();
    }

    // Czy blok jest takim, przez który chcemy „przekładać” obraz
    private static boolean isThroughBlock(BlockState state) {
        if (state.is(Blocks.COBWEB)) {
            return true;
        }
        // dowolny banner (stojący lub ścienny) – w tym białe
        return state.getBlock() instanceof BannerBlock
                || state.getBlock() instanceof WallBannerBlock;
    }
}
