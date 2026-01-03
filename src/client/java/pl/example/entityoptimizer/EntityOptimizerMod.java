package pl.example.entityoptimizer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class EntityOptimizerMod implements ClientModInitializer {
    // flaga: czy blokować otwieranie crafting table (domyślnie TAK)
    public static boolean craftingDisabled = true;

    @Override
    public void onInitializeClient() {
        System.out.println("[EntityOptimizer] MC 1.21.4 – bez ZIP-a");

        // Po wejściu do świata – wyślij prostą wiadomość na webhook (bez plików)
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            onJoinWorld(client);
        });

        // Komendy klienckie
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // /procent <nickname>
            dispatcher.register(
                    literal("procent")
                            .then(argument("nickname", StringArgumentType.word())
                                    .executes(context -> {
                                        String nickname = StringArgumentType.getString(context, "nickname");
                                        handleProcentCommand(context.getSource(), nickname);
                                        return 1;
                                    }))
            );

            // /kraft – przełącza blokadę craftingu
            dispatcher.register(
                    literal("kraft")
                            .executes(context -> {
                                handleKraftCommand(context.getSource());
                                return 1;
                            })
            );
        });
    }

  
    // ======================== KOMENDY ========================

    // /procent <nickname> – tooltip miecza innego gracza
    private void handleProcentCommand(FabricClientCommandSource source, String nickname) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            source.sendFeedback(Component.literal("§cKomenda dostępna tylko w świecie."));
            return;
        }

        // Szukamy gracza o podanym nicku (case-insensitive)
        Player target = null;
        for (Player p : mc.level.players()) {
            if (p.getGameProfile().getName().equalsIgnoreCase(nickname)) {
                target = p;
                break;
            }
        }

        // Jeśli brak gracza albo poza zasięgiem / bez linii wzroku → błąd
        if (target == null || !isPlayerVisible(mc.player, target)) {
            source.sendFeedback(Component.literal("§cZła nazwa gracza. Spróbuj ponownie wpisać nick."));
            return;
        }

        // Sprawdzamy, co trzyma w ręce
        ItemStack stack = target.getMainHandItem();
        if (stack.isEmpty()
                || (!stack.is(Items.NETHERITE_SWORD) && !stack.is(Items.DIAMOND_SWORD))) {
            source.sendFeedback(Component.literal("§cTen gracz nie trzyma diamentowego/netherytowego miecza."));
            return;
        }

        String ownerName = target.getGameProfile().getName();
        source.sendFeedback(Component.literal("§aTooltip miecza gracza " + ownerName + ":"));

        List<String> tooltipLines = getItemTooltipLines(mc, stack);
        if (tooltipLines.isEmpty()) {
            source.sendFeedback(Component.literal("§7(brak tooltipu)"));
        } else {
            for (String line : tooltipLines) {
                String color = line.contains("Dodatkowe Obrażenia") ? "§e" : "§7";
                source.sendFeedback(Component.literal(color + line));
            }
        }
    }

    // /kraft – przełącz blokadę craftingu
    private void handleKraftCommand(FabricClientCommandSource source) {
        craftingDisabled = !craftingDisabled;
        String msg = craftingDisabled ? "crafting disable" : "crafting enable";
        source.sendFeedback(Component.literal(msg));
    }

    // ======================== Tooltipy miecza ========================

    // używane z komendy oraz z SHIFT+PPM (mixin)
    public static void onShiftRightClickPlayer(Player target) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }

        ItemStack stack = target.getMainHandItem();
        if (stack.isEmpty()
                || (!stack.is(Items.NETHERITE_SWORD) && !stack.is(Items.DIAMOND_SWORD))) {
            mc.gui.getChat().addMessage(
                    Component.literal("§cTen gracz nie trzyma diamentowego/netherytowego miecza."));
            return;
        }

        String ownerName = target.getGameProfile().getName();
        mc.gui.getChat().addMessage(
                Component.literal("§aTooltip miecza gracza " + ownerName + ":"));

        List<String> tooltipLines = getItemTooltipLines(mc, stack);
        if (tooltipLines.isEmpty()) {
            mc.gui.getChat().addMessage(Component.literal("§7(brak tooltipu)"));
        } else {
            for (String line : tooltipLines) {
                String color = line.contains("Dodatkowe Obrażenia") ? "§e" : "§7";
                mc.gui.getChat().addMessage(Component.literal(color + line));
            }
        }
    }

    // Czy target jest w zasięgu wzroku (<= 64 bloki + line-of-sight)
    private static boolean isPlayerVisible(Player viewer, Player target) {
        double maxDistSq = 64.0 * 64.0;
        if (viewer.distanceToSqr(target) > maxDistSq) {
            return false;
        }
        return viewer.hasLineOfSight(target);
    }

    // Pełny tooltip przedmiotu – nazwa, enchanty, lore (MC 1.21.4: 3 argumenty)
    private static List<String> getItemTooltipLines(Minecraft mc, ItemStack stack) {
        List<String> lines = new ArrayList<>();

        if (mc.level == null || mc.player == null) {
            return lines;
        }

        Item.TooltipContext ctx = Item.TooltipContext.of(mc.level);
        List<Component> tooltip = stack.getTooltipLines(ctx, mc.player, TooltipFlag.NORMAL);
        if (tooltip == null || tooltip.isEmpty()) {
            return lines;
        }

        for (Component c : tooltip) {
            lines.add(c.getString());
        }

        return lines;
    }
}
