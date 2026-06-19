package com.gemini.baritonechat;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.class_1268;
import net.minecraft.class_2248;
import net.minecraft.class_2338;
import net.minecraft.class_243;
import net.minecraft.class_2960;
import net.minecraft.class_304;
import net.minecraft.class_310;
import net.minecraft.class_3675;
import net.minecraft.class_640;
import net.minecraft.class_742;
import net.minecraft.class_7923;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

@Environment(EnvType.CLIENT)
public class GeminiBaritoneClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("gemini-baritone");

    // ── State flags ───────────────────────────────────────────────────────────
    private static volatile boolean killModeActive  = false;
    private static volatile String  killTarget      = null;
    private static volatile String  activeTask      = null;

    // ── Walk task state ───────────────────────────────────────────────────────
    private static volatile boolean walkActive      = false;
    private static int              walkTickCounter = 0;
    private static final int        WALK_TICKS      = 14;

    // ── Attack cooldown ───────────────────────────────────────────────────────
    private static int attackCooldownTicks = 0;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[GeminiBaritone] Client initialised");

        ClientReceiveMessageEvents.CHAT.register(
            (message, signedMessage, sender, params, receptionTimestamp) -> {
                String raw = null;
                if (signedMessage != null) {
                    raw = signedMessage.method_44862();
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
            if (client.field_1724 == null) return;

            // ── Aim assist + auto attack (kill mode only) ─────────────────────
            // ONLY runs when killModeActive=true AND killTarget is set.
            // NEVER runs for follow, mine, goto, walk, or any other command.
            if (killModeActive && killTarget != null) {
                class_742 target = findPlayerExact(client, killTarget);
                if (target != null) {
                    // Aim assist — lock onto target's eye position every tick
                    aimAt(client, target);

                    // 1.9 combat — attack only at 95% cooldown charge
                    // getAttackCooldownProgress returns 0.0 to 1.0
                    // At 95% we get near-full damage without spam
                    float cooldown = client.field_1724.method_7261(0f);
                    if (cooldown >= 0.95f) {
                        double distSq = client.field_1724.method_5858(target);
                        // Only attack within melee range (4 blocks = 16 squared)
                        if (distSq <= 16.0) {
                            client.field_1761.method_2918(client.field_1724, target);
                            client.field_1724.method_6104(class_1268.field_5808);
                            attackCooldownTicks = 0;
                        }
                    } else {
                        attackCooldownTicks++;
                    }
                }
            }

            // ── Walk hold ─────────────────────────────────────────────────────
            if (walkActive) {
                walkTickCounter++;
                class_3675.class_306 forwardKey = client.field_1690.field_1894.method_1429();
                if (walkTickCounter <= WALK_TICKS) {
                    class_304.method_1416(forwardKey, true);
                } else {
                    class_304.method_1416(forwardKey, false);
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
        if (!GeminiConfig.isEnabled()) return;

        GeminiCommandParser.GeminiCommand cmd = GeminiCommandParser.parse(raw);
        if (cmd == null) return;

        class_310 client = class_310.method_1551();
        if (client == null || client.field_1724 == null) return;

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
    // STOP — always clears kill mode, aim assist, and attack
    // ──────────────────────────────────────────────────────────────────────────

    private static void handleStop(class_310 client) {
        if (walkActive) {
            walkActive      = false;
            walkTickCounter = 0;
            class_304.method_1416(client.field_1690.field_1894.method_1429(), false);
        }

        sendBaritoneCommand(client, "#stop");

        killModeActive     = false;
        killTarget         = null;
        attackCooldownTicks = 0;
        activeTask         = null;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // FOLLOW — NO aim assist, NO auto attack, never touches killMode
    // ──────────────────────────────────────────────────────────────────────────

    private static void handleFollow(class_310 client, String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            sendFailed(client);
            return;
        }
        if (!playerOnline(client, playerName)) {
            sendFailed(client);
            return;
        }
        activeTask = "follow";
        sendBaritoneCommand(client, "#follow player " + playerName);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // KILL — enables aim assist + auto attack for EXACT named target only
    // ──────────────────────────────────────────────────────────────────────────

    private static void handleKill(class_310 client, String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            sendFailed(client);
            return;
        }
        if (!playerOnline(client, playerName)) {
            sendFailed(client);
            return;
        }

        killModeActive      = true;
        killTarget          = playerName;
        attackCooldownTicks = 0;
        activeTask          = "kill";

        sendBaritoneCommand(client, "#follow player " + playerName);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // MINE
    // ──────────────────────────────────────────────────────────────────────────

    private static void handleMine(class_310 client, String blockName) {
        if (client.field_1687 == null) {
            sendFailed(client);
            return;
        }

        String lower = blockName.toLowerCase().replace(" ", "_");
        List<String> matches = new ArrayList<>();

        for (class_2248 block : class_7923.field_41175) {
            class_2960 id = class_7923.field_41175.method_10221(block);
            String path   = id.method_12832();
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

    private static void handleGoto(class_310 client, String dest) {
        activeTask = "goto";
        sendBaritoneCommand(client, "#goto " + dest);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TOWER UP
    // ──────────────────────────────────────────────────────────────────────────

    private static void handleTowerUp(class_310 client, String amountStr) {
        try {
            int amount   = Integer.parseInt(amountStr.trim());
            class_2338 pos = client.field_1724.method_24515();
            int targetY  = pos.method_10264() + amount;
            activeTask   = "tower";
            sendBaritoneCommand(client, "#goto " + pos.method_10263() + " " + targetY + " " + pos.method_10260());
        } catch (NumberFormatException e) {
            sendFailed(client);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // WALK
    // ──────────────────────────────────────────────────────────────────────────

    private static void handleWalk(class_310 client, String arg) {
        walkActive      = true;
        walkTickCounter = 0;
        activeTask      = "walk";
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private static void sendBaritoneCommand(class_310 client, String cmd) {
        client.execute(() -> {
            if (client.field_1724 != null) {
                client.field_1724.field_3944.method_45729(cmd);
            }
        });
    }

    private static void sendFailed(class_310 client) {
    }

    private static boolean playerOnline(class_310 client, String name) {
        if (client.method_1562() == null) return false;
        for (class_640 entry : client.method_1562().method_2880()) {
            if (entry.method_2966().name().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /**
     * Finds a player by EXACT name match only.
     * Returns null if not found — will never return the wrong player.
     */
    private static class_742 findPlayerExact(class_310 client, String name) {
        if (client.field_1687 == null || name == null) return null;
        for (class_742 p : client.field_1687.method_18456()) {
            if (p.method_5477().getString().equalsIgnoreCase(name)) return p;
        }
        return null;
    }

    private static void aimAt(class_310 client, class_742 target) {
        if (client.field_1724 == null) return;
        class_243 from  = client.field_1724.method_33571();
        class_243 to    = target.method_33571();
        double dx   = to.field_1352 - from.field_1352;
        double dy   = to.field_1351 - from.field_1351;
        double dz   = to.field_1350 - from.field_1350;
        double h    = Math.sqrt(dx * dx + dz * dz);
        float yaw   = (float)(Math.toDegrees(Math.atan2(dz, dx))) - 90f;
        float pitch = (float)(-Math.toDegrees(Math.atan2(dy, h)));
        client.field_1724.method_36456(yaw);
        client.field_1724.method_36457(pitch);
    }
}