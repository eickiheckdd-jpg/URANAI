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
import net.minecraft.client.util.InputUtil;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWKeyCallbackI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

@Environment(EnvType.CLIENT)
public class GeminiBaritoneClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("gemini-baritone");

    // ── State flags ───────────────────────────────────────────────────────────
    private static volatile boolean killModeActive = false;
    private static volatile String  activeTask     = null;

    // ── Walk task state ───────────────────────────────────────────────────────
    private static volatile boolean walkActive   = false;
    private static int walkTickCounter           = 0;
    private static final int WALK_TICKS          = 14;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[GeminiBaritone] Client initialised");

        ClientReceiveMessageEvents.CHAT.register(
            (message, signedMessage, sender, params, receptionTimestamp) -> {
                String raw = null;
                if (signedMessage != null) {
                    raw = signedMessage.getSignedContent();
                }
                if (raw == null || raw.isEmpty()) {
                    raw = message.getString();
                    if (raw.startsWith("<")) {
                        int close = raw.indexOf("> ");
                        if (close != -1) raw = raw.substring(close + 2).trim();
                    } else {
                        int colon = raw.indexOf(": ");
                        if (colon != -1 && colon < 32) raw = raw.substring(colon + 2).trim();
                    }
                }
                LOGGER.info("[GeminiBaritone] CHAT raw='{}'", raw);
                handleIncoming(raw);
            }
        );

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) handleIncoming(message.getString());
        });

        ClientSendMessageEvents.CHAT.register(message -> handleIncoming(message));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            if (walkActive) {
                walkTickCounter++;
                InputUtil.Key forwardKey = client.options.forwardKey.getDefaultKey();
                if (walkTickCounter <= WALK_TICKS) {
                    KeyBinding.setKeyPressed(forwardKey, true);
                } else {
                    KeyBinding.setKeyPressed(forwardKey, false);
                    walkActive      = false;
                    walkTickCounter = 0;
                    if ("walk".equals(activeTask)) activeTask = null;
                }
            }
        });
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
    // STOP
    // ──────────────────────────────────────────────────────────────────────────

    private static void handleStop(MinecraftClient client) {
        if (walkActive) {
            walkActive      = false;
            walkTickCounter = 0;
            KeyBinding.setKeyPressed(client.options.forwardKey.getDefaultKey(), false);
        }

        sendBaritoneCommand(client, "#stop");

        if (killModeActive) {
            simulateKeyPress(client, GLFW.GLFW_KEY_K);
            simulateKeyPress(client, GLFW.GLFW_KEY_R);
            killModeActive = false;
        }

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
        activeTask   = "follow";
        sendBaritoneCommand(client, "#follow player " + playerName);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // KILL
    // ──────────────────────────────────────────────────────────────────────────

    private static void handleKill(MinecraftClient client, String playerName) {
        if (!playerOnline(client, playerName)) {
            sendFailed(client);
            return;
        }
        killModeActive = true;
        activeTask     = "kill";
        sendBaritoneCommand(client, "#follow player " + playerName);
        simulateKeyPress(client, GLFW.GLFW_KEY_R);
        simulateKeyPress(client, GLFW.GLFW_KEY_K);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // MINE
    // ──────────────────────────────────────────────────────────────────────────

    private static void handleMine(MinecraftClient client, String blockName) {
        if (client.world == null) {
            sendFailed(client);
            return;
        }

        String lower = blockName.toLowerCase().replace(" ", "_");
        List<String> matches = new ArrayList<>();

        for (Block block : Registries.BLOCK) {
            Identifier id = Registries.BLOCK.getId(block);
            String path   = id.getPath();
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
    // WALK
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

    private static boolean playerOnline(MinecraftClient client, String name) {
        if (client.getNetworkHandler() == null) return false;
        for (PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
            if (entry.getProfile().name().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /**
     * Fires a real GLFW key press + release event on Minecraft's window.
     *
     * Hack clients register their toggles via glfwSetKeyCallback or by hooking
     * Minecraft's KeyboardHandler. Both of those listen for actual GLFW events,
     * NOT Fabric's KeyBinding.onKeyPressed() — which is why the old method did
     * nothing visible.
     *
     * By invoking the existing GLFW key callback directly with GLFW_PRESS then
     * GLFW_RELEASE we replicate exactly what happens when the physical key is hit.
     */
    private static void simulateKeyPress(MinecraftClient client, int glfwKey) {
        client.execute(() -> {
            long window = client.getWindow().getHandle();
            int scancode = GLFW.glfwGetKeyScancode(glfwKey);

            // Get the currently installed key callback and invoke it directly.
            // This hits every listener that registered via glfwSetKeyCallback,
            // including hack client keybind systems.
            GLFWKeyCallbackI callback = GLFW.glfwSetKeyCallback(window, null);
            if (callback != null) {
                // Re-register it immediately so we don't break input
                GLFW.glfwSetKeyCallback(window, callback);
                // Fire PRESS then RELEASE — same as a real keypress
                callback.invoke(window, glfwKey, scancode, GLFW.GLFW_PRESS,   0);
                callback.invoke(window, glfwKey, scancode, GLFW.GLFW_RELEASE,  0);
            }

            // Also fire Fabric/vanilla path as a fallback
            InputUtil.Key key = InputUtil.Type.KEYSYM.createFromCode(glfwKey);
            KeyBinding.onKeyPressed(key);
        });
    }
}
 com.gemini.baritonechat;

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
import net.minecraft.client.util.InputUtil;
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

        // ── Received chat from other players ─────────────────────────────────
        // signedMessage.getSignedContent() gives ONLY the raw text the player
        // typed, with no "<PlayerName>" wrapper — exactly what the parser needs.
        ClientReceiveMessageEvents.CHAT.register(
            (message, signedMessage, sender, params, receptionTimestamp) -> {
                String raw = null;

                // Best case: signed content = exactly what they typed
                if (signedMessage != null) {
                    raw = signedMessage.getSignedContent();
                }

                // Fallback: strip player name from the decorated Text
                if (raw == null || raw.isEmpty()) {
                    raw = message.getString();
                    // Decorated format is "<PlayerName> text" or "PlayerName: text"
                    // Strip "<Name> " prefix
                    if (raw.startsWith("<")) {
                        int close = raw.indexOf("> ");
                        if (close != -1) raw = raw.substring(close + 2).trim();
                    }
                    // Strip "Name: " prefix
                    int colon = raw.indexOf(": ");
                    if (colon != -1 && colon < 32) raw = raw.substring(colon + 2).trim();
                }

                LOGGER.info("[GeminiBaritone] CHAT raw='{}'", raw);
                handleIncoming(raw);
            }
        );

        // ── System/game messages ──────────────────────────────────────────────
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) handleIncoming(message.getString());
        });

        // ── Your own outgoing chat ────────────────────────────────────────────
        ClientSendMessageEvents.CHAT.register(message -> handleIncoming(message));

        // ── Tick handler — walk only ──────────────────────────────────────────
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            if (walkActive) {
                walkTickCounter++;
                InputUtil.Key forwardKey = client.options.forwardKey.getDefaultKey();
                if (walkTickCounter <= WALK_TICKS) {
                    KeyBinding.setKeyPressed(forwardKey, true);
                } else {
                    KeyBinding.setKeyPressed(forwardKey, false);
                    walkActive      = false;
                    walkTickCounter = 0;
                    if ("walk".equals(activeTask)) activeTask = null;
                }
            }
        });
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
    // STOP
    // ──────────────────────────────────────────────────────────────────────────

    private static void handleStop(MinecraftClient client) {
        if (walkActive) {
            walkActive      = false;
            walkTickCounter = 0;
            KeyBinding.setKeyPressed(client.options.forwardKey.getDefaultKey(), false);
        }

        sendBaritoneCommand(client, "#stop");

        if (killModeActive) {
            simulateKeyPress(GLFW.GLFW_KEY_K);
            simulateKeyPress(GLFW.GLFW_KEY_R);
            killModeActive = false;
        }

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
    // KILL
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
        simulateKeyPress(GLFW.GLFW_KEY_R);
        simulateKeyPress(GLFW.GLFW_KEY_K);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // MINE
    // ──────────────────────────────────────────────────────────────────────────

    private static void handleMine(MinecraftClient client, String blockName) {
        if (client.world == null) {
            sendFailed(client);
            return;
        }

        String lower = blockName.toLowerCase().replace(" ", "_");
        List<String> matches = new ArrayList<>();

        for (Block block : Registries.BLOCK) {
            Identifier id = Registries.BLOCK.getId(block);
            String path   = id.getPath();
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
    // WALK
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

    private static boolean playerOnline(MinecraftClient client, String name) {
        if (client.getNetworkHandler() == null) return false;
        for (PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
            if (entry.getProfile().name().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static void simulateKeyPress(int glfwKey) {
        InputUtil.Key key = InputUtil.Type.KEYSYM.createFromCode(glfwKey);
        KeyBinding.onKeyPressed(key);
    }
}
