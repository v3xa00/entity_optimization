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
import net.minecraft.world.level.block.Blocks;
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
     * SHIFT + PPM z obrazem na cobwebie:
     * - spróbuj postawić obraz na ścianie ZA cobwebem (po stronie przeciwnej do klikniętej).
     *
     * Uwaga: w 1.20.1 MultiPlayerGameMode.useItemOn(LocalPlayer, ...) – stąd LocalPlayer w sygnaturze.
     */
    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void entity_optimizer$placePaintingBehindCobweb(LocalPlayer player,
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

        // tylko SHIFT + PPM z głównej ręki
        if (!player.isCrouching() || hand != InteractionHand.MAIN_HAND) {
            return;
        }

        ItemStack stack = player.getItemInHand(hand);
        if (!stack.is(Items.PAINTING)) {
            return;
        }

        BlockPos cobwebPos = hit.getBlockPos();
        BlockState cobwebState = minecraft.level.getBlockState(cobwebPos);
        if (!cobwebState.is(Blocks.COBWEB)) {
            return;
        }

        // kierunek do ściany: PRZECIWNY do strony cobweb'a, którą kliknęliśmy
        Direction towardWall = hit.getDirection().getOpposite();
        BlockPos wallPos = cobwebPos.relative(towardWall);
        BlockState wallState = minecraft.level.getBlockState(wallPos);

        // ściana musi być solidna z przeciwnej strony (tam przyczepi się obraz)
        if (!wallState.isFaceSturdy(minecraft.level, wallPos, towardWall.getOpposite())) {
            return;
        }

        // symulujemy kliknięcie na środku ściany
        Vec3 hitVec = Vec3.atCenterOf(wallPos);
        BlockHitResult newHit = new BlockHitResult(hitVec, towardWall, wallPos, false);

        // wywołujemy vanilla useItemOn z nowym hitem, wyłączając nasz kod na ten czas
        entity_optimizer$placingPainting = true;
        InteractionResult result = ((MultiPlayerGameMode) (Object) this)
                .useItemOn(player, hand, newHit);
        entity_optimizer$placingPainting = false;

        cir.setReturnValue(result);
        cir.cancel();
    }
}
