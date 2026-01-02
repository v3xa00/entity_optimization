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
     * - wykonujemy własny raycast od oczu gracza, ignorując cobweby i bannery,
     * - pierwszy napotkany "normalny" blok traktujemy jako ścianę docelową,
     *   tak jakby cobweb/banner w ogóle nie istniał.
     *
     * Dodatkowo:
     * - blokuje PPM na crafting table (nie otwiera GUI).
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

        // Tylko jeśli faktycznie kliknęliśmy w cobweb lub banner
        if (!isThroughBlock(clickedState)) {
            return;
        }

        // Wykonujemy własny raycast od oczu gracza w kierunku wzroku,
        // ignorując po drodze cobweby i bannery, żeby znaleźć ścianę.
        BlockHitResult wallHit = findWallIgnoringThroughBlocks(player);
        if (wallHit == null || wallHit.getType() != HitResult.Type.BLOCK) {
            return;
        }

        BlockPos wallPos = wallHit.getBlockPos();
        BlockState wallState = minecraft.level.getBlockState(wallPos);
        Direction wallFace = wallHit.getDirection();

        // Upewniamy się, że ściana jest solidna na tej twarzy
        if (!wallState.isFaceSturdy(minecraft.level, wallPos, wallFace)) {
            return;
        }

        // Symulujemy kliknięcie dokładnie tej ściany, którą widzi gracz
        BlockHitResult newHit = new BlockHitResult(
                wallHit.getLocation(),
                wallFace,
                wallPos,
                false
        );

        // Wywołujemy vanilla useItemOn z nowym hitem, wyłączając nasz kod na ten czas
        entity_optimizer$placingPainting = true;
        InteractionResult result = ((MultiPlayerGameMode) (Object) this)
                .useItemOn(player, hand, newHit);
        entity_optimizer$placingPainting = false;

        cir.setReturnValue(result);
        cir.cancel();
    }

    /**
     * Raycast od oczu gracza w kierunku wzroku, ignorując cobweby i bannery.
     * Zwraca pierwszy napotkany "normalny" blok.
     */
    private BlockHitResult findWallIgnoringThroughBlocks(LocalPlayer player) {
        final int MAX_STEPS = 8;
        final double REACH = 5.0D; // zasięg "ręki" w survivalu

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
