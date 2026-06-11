package com.example.geminibaritone;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Environment(EnvType.CLIENT)
public class GeminiBaritoneClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("gemini-baritone");

    @Override
    public void onInitializeClient() {
        LOGGER.info("[GeminiBaritone] Client initialised — listening for 'hey gemini' commands");

        // ── Messages received FROM the server (other players talking) ──
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            handleIncoming(message.getString());
        });

        // ── System/game messages from the server ──
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                handleIncoming(message.getString());
            }
        });

        // ── Messages YOU type yourself ──
        ClientSendMessageEvents.CHAT.register((message) -> {
            String baritoneCmd = GeminiCommandParser.parse(message);
            if (baritoneCmd != null) {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client != null) {
                    executeBaritoneCommand(client, baritoneCmd);
                }
            }
            return true; // true = still send to server, false = cancel
        });
    }

    // ──────────────────────────────────────────────────────────────────────────

    private static void handleIncoming(String raw) {
        String baritoneCmd = GeminiCommandParser.parse(raw);
        if (baritoneCmd == null) return;

        LOGGER.info("[GeminiBaritone] Parsed command: {}", baritoneCmd);

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) {
            LOGGER.warn("[GeminiBaritone] MinecraftClient not ready; dropping: {}", baritoneCmd);
            return;
        }

        executeBaritoneCommand(client, baritoneCmd);
    }

    private static void executeBaritoneCommand(MinecraftClient client, String cmd) {
        client.execute(() -> {
            if (client.player != null) {
                client.player.networkHandler.sendChatMessage(cmd);
                client.player.sendMessage(
                    Text.literal("§7[Gemini→Baritone] §f" + cmd),
                    false
                );
            }
        });
    }
}