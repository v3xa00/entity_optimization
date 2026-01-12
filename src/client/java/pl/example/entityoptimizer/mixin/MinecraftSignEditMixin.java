package pl.example.entityoptimizer.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.SignEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.example.entityoptimizer.EntityOptimizerMod;

@Mixin(Minecraft.class)
public class MinecraftSignEditMixin {

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void eo$blockSignEdit(Screen screen, CallbackInfo ci) {
        if (screen instanceof SignEditScreen && EntityOptimizerMod.signEditDisabled) {
            // nie otwieraj GUI do edycji tabliczki
            ci.cancel();
        }
    }
}
