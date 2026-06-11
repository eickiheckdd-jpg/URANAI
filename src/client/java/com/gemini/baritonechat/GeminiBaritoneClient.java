package com.gemini.baritonechat;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

@Environment(EnvType.CLIENT)
public class GeminiBaritoneClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("gemini-baritone");

    // ── State flags ───────────────────────────────────────────────────────────
    private static volatile boolean killModeActive = false;
    private static volatile String  followTarget   = null;
    private static volatile String  mineTarget     = null;
    private static volatile String  activeTask     = null;

    // ── Walk task state ───────────────────────────────────────────────────────
    private static volatile boolean walkActive   = false;
    private static int walkTickCounter           = 0;
    private static final int WALK_TICKS          = 14; // ~0.7s at 20 ticks/sec

    @Override
    public void onInitializeClient() {
        LOGGER.info("[GeminiBaritone] Client initialised");

        // ── Received chat from other players ──────────────────────────────────
        ClientReceiveMessageEvents.CHAT.register(
            (message, signedMessage, sender, params, receptionTimestamp) ->
                handleIncoming(message.getString())
        );

        // ── System/game messages ──────────────────────────────────────────────
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) handleIncoming(message.getString());
        });

        // ── Your own outgoing chat ────────────────────────────────────────────
        ClientSendMessageEvents.CHAT.register(this::handleIncomingInstance);

        // ── Tick handler — walk only ──────────────────────────────────────────
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            if (walkActive) {
                walkTickCounter++;
                KeyBinding forwardKey = client.options.forwardKey;
                if (walkTickCounter <= WALK_TICKS) {
                    KeyBinding.setKeyPressed(forwardKey.getDefaultKey(), true);
                } else {
                    KeyBinding.setKeyPressed(forwardKey.getDefaultKey(), false);
                    walkActive      = false;
                    walkTickCounter = 0;
                    if ("walk".equals(activeTask)) activeTask = null;
                }
            }
        });
    }

    // Needed to use method reference for ClientSendMessageEvents (void return)
    private void handleIncomingInstance(String raw) {
        handleIncoming(raw);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Core dispatch
    // ──────────────────────────────────────────────────────────────────────────

    private static void handleIncoming(String raw) {
        GeminiCommandParser.GeminiCommand cmd = GeminiCommandParser.parse(raw);
        if (cmd == null) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return;

        try {
            switch (cmd.type) {
                case STOP     -> handleStop(client);
                case FOLLOW   -> handleFollow(client, cmd.arg1);
                case KILL     -> handleKill(client, cmd.arg1);
                case MINE     -> handleMine(client, cmd.arg1);
                case GOTO     -> handleGoto(client, cmd.arg1);
                case TOWER_UP -> handleTowerUp(client, cmd.arg1);
                case WALK     -> handleWalk(client, cmd.arg1);
            }
        } catch (Exception e) {
            LOGGER.error("[GeminiBaritone] Command failed", e);
            sendFailed(client);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // STOP — clears ALL state, cancels walk, conditionally presses K+R
    // ──────────────────────────────────────────────────────────────────────────

    private static void handleStop(MinecraftClient client) {
        // Cancel walk immediately
        if (walkActive) {
            walkActive      = false;
            walkTickCounter = 0;
            KeyBinding.setKeyPressed(client.options.forwardKey.getDefaultKey(), false);
        }

        // Send #stop to Baritone first
        sendBaritoneCommand(client, "#stop");

        // Only press K and R if kill mode was active
        if (killModeActive) {
            simulateKeyPress(client, GLFW.GLFW_KEY_K);
            simulateKeyPress(client, GLFW.GLFW_KEY_R);
            killModeActive = false;
        }

        // Always clear all state
        followTarget = null;
        mineTarget   = null;
        activeTask   = null;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // FOLLOW
    // ──────────────────────────────────────────────────────────────────────────

    private static void handleFollow(MinecraftClient client, String playerName) {
        if (!playerOnline(client, playerName)) {
            sendFailed(client);
            return;
        }
        followTarget = playerName;
        activeTask   = "follow";
        sendBaritoneCommand(client, "#follow player " + playerName);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // KILL — follow + press R then K (ONLY command that may do this)
    // ──────────────────────────────────────────────────────────────────────────

    private static void handleKill(MinecraftClient client, String playerName) {
        if (!playerOnline(client, playerName)) {
            sendFailed(client);
            return;
        }
        killModeActive = true;
        followTarget   = playerName;
        activeTask     = "kill";
        sendBaritoneCommand(client, "#follow player " + playerName);
        simulateKeyPress(client, GLFW.GLFW_KEY_R);
        simulateKeyPress(client, GLFW.GLFW_KEY_K);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // MINE — dynamic registry lookup, no hardcoded aliases
    // ──────────────────────────────────────────────────────────────────────────

    private static void handleMine(MinecraftClient client, String blockName) {
        if (client.world == null) {
            sendFailed(client);
            return;
        }

        String lower = blockName.toLowerCase().replace(" ", "_");
        List<String> matches = new ArrayList<>();

        for (Block block : Registries.BLOCK) {
            Identifier id   = Registries.BLOCK.getId(block);
            String path     = id.getPath();
            if (path.contains(lower)
                    && !path.equals("air")
                    && !path.equals("barrier")
                    && !path.equals("void_air")
                    && !path.equals("cave_air")) {
                matches.add(path);
            }
        }

        if (matches.isEmpty()) {
            sendFailed(client);
            return;
        }

        String resolved = String.join(" ", matches);
        mineTarget = resolved;
        activeTask = "mine";
        sendBaritoneCommand(client, "#mine " + resolved);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // GOTO
    // ──────────────────────────────────────────────────────────────────────────

    private static void handleGoto(MinecraftClient client, String dest) {
        activeTask = "goto";
        sendBaritoneCommand(client, "#goto " + dest);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TOWER UP
    // ──────────────────────────────────────────────────────────────────────────

    private static void handleTowerUp(MinecraftClient client, String amountStr) {
        try {
            int amount   = Integer.parseInt(amountStr.trim());
            BlockPos pos = client.player.getBlockPos();
            int targetY  = pos.getY() + amount;
            activeTask   = "tower";
            sendBaritoneCommand(client, "#goto " + pos.getX() + " " + targetY + " " + pos.getZ());
        } catch (NumberFormatException e) {
            sendFailed(client);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // WALK — hold W for ~0.7 seconds
    // ──────────────────────────────────────────────────────────────────────────

    private static void handleWalk(MinecraftClient client, String arg) {
        walkActive      = true;
        walkTickCounter = 0;
        activeTask      = "walk";
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private static void sendBaritoneCommand(MinecraftClient client, String cmd) {
        client.execute(() -> {
            if (client.player != null) {
                client.player.networkHandler.sendChatMessage(cmd);
                client.player.sendMessage(Text.literal("§7[Gemini→Baritone] §f" + cmd), false);
            }
        });
    }

    private static void sendFailed(MinecraftClient client) {
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendMessage(Text.literal("Failed."), false);
            }
        });
    }

    /**
     * Check tab-list for player — works even outside render distance.
     */
    private static boolean playerOnline(MinecraftClient client, String name) {
        if (client.getNetworkHandler() == null) return false;
        for (PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
            if (entry.getProfile().getName().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /**
     * Simulate a single key press using GLFW key code.
     * ONLY called by handleKill and handleStop (when killModeActive).
     */
    private static void simulateKeyPress(MinecraftClient client, int glfwKey) {
        for (KeyBinding kb : client.options.allKeys) {
            if (kb.getDefaultKey().getCode() == glfwKey) {
                KeyBinding.onKeyPressed(kb.getDefaultKey());
                return;
            }
        }
        LOGGER.warn("[GeminiBaritone] No keybinding found for GLFW key: {}", glfwKey);
    }
}