package com.example.geminibaritone;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side mod initializer.
 *
 * ⚠️  This class MUST stay in src/client/java — it uses MinecraftClient.
 *     Putting it in src/main will cause "MinecraftClient does not exist" errors.
 *
 * Entrypoint registered as "client" in fabric.mod.json.
 */
@Environment(EnvType.CLIENT)
public class GeminiBaritoneClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("gemini-baritone");

    @Override
    public void onInitializeClient() {
        LOGGER.info("[GeminiBaritone] Client initialised — listening for 'hey gemini' commands");

        // ── Chat listener ──────────────────────────────────────────────────────
        // ClientReceiveMessageEvents.CHAT fires when a player chat message arrives
        // (messages sent by other players, or echoed back from the server).
        //
        // ClientReceiveMessageEvents.GAME fires for system/game messages.
        // We register BOTH so that servers using the system channel still work.
        //
        // These events exist in fabric-message-api-v1, bundled in fabric-api.
        // Confirmed present in fabric-api 0.100.1+1.21 and later.
        // ──────────────────────────────────────────────────────────────────────

        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            handleIncoming(message.getString());
        });

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            // Skip action-bar messages (overlay == true) to reduce noise
            if (!overlay) {
                handleIncoming(message.getString());
            }
        });
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Core dispatch
    // ──────────────────────────────────────────────────────────────────────────

    private static void handleIncoming(String raw) {
        String baritoneCmd = GeminiCommandParser.parse(raw);
        if (baritoneCmd == null) return; // not a gemini command

        LOGGER.info("[GeminiBaritone] Parsed command: {}", baritoneCmd);

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) {
            LOGGER.warn("[GeminiBaritone] MinecraftClient not ready; dropping command: {}", baritoneCmd);
            return;
        }

        executeBaritoneCommand(client, baritoneCmd);
    }

    /**
     * Executes a Baritone command via the in-game command system.
     *
     * Baritone registers '#' as its command prefix and hooks into the chat
     * send pipeline.  We call {@code client.player.networkHandler.sendChatCommand}
     * or send the '#...' string via the chat — whichever matches your Baritone build.
     *
     * Strategy used here:
     *   1. Try the Baritone API directly (compile-time optional dependency).
     *   2. Fall back to sending the command as a chat message.
     *      Baritone intercepts '#' messages on the client before they leave the game.
     */
    private static void executeBaritoneCommand(MinecraftClient client, String cmd) {
        // ── Strategy 1: Baritone API (preferred, avoids network round-trip) ──
        // Uncomment this block if you have baritone-api on the compile classpath:
        //
        // try {
        //     baritone.api.BaritoneAPI api = baritone.api.BaritoneAPI.getProvider()
        //         .getPrimaryBaritone();
        //     if (api != null) {
        //         // Strip leading '#' — Baritone's ICommandManager expects bare text
        //         String bare = cmd.startsWith("#") ? cmd.substring(1) : cmd;
        //         api.getCommandManager().execute(bare);
        //         return;
        //     }
        // } catch (NoClassDefFoundError ignored) {
        //     // Baritone not present at runtime; fall through to chat fallback
        // }

        // ── Strategy 2: Chat message fallback ─────────────────────────────────
        // Baritone intercepts outgoing '#' messages on the CLIENT side before they
        // are sent to the server, so this does NOT expose the command to other players.
        //
        // sendChatMessage queues to the main thread safely:
        client.execute(() -> {
            if (client.player != null) {
                // Baritone expects the '#' prefix in the outgoing chat string
                client.player.networkHandler.sendChatMessage(cmd);
                // Show a small feedback message in local chat (optional)
                client.player.sendMessage(
                    Text.literal("§7[Gemini→Baritone] §f" + cmd),
                    false
                );
            }
        });
    }
}