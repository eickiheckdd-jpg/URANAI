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
import net.minecraft.class_2561;
import net.minecraft.class_2960;
import net.minecraft.class_304;
import net.minecraft.class_310;
import net.minecraft.class_3675;
import net.minecraft.class_640;
import net.minecraft.class_742;
import net.minecraft.class_7923;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWKeyCallbackI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

@Environment(EnvType.CLIENT)
public class GeminiBaritoneClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("gemini-baritone");

    private static volatile boolean killModeActive  = false;
    private static volatile String  killTarget      = null;
    private static volatile String  activeTask      = null;

    private static volatile boolean walkActive      = false;
    private static int              walkTickCounter = 0;
    private static int              walkTargetTicks = 0;
    private static final double     TICKS_PER_BLOCK = 4.634;

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

            if (killModeActive && killTarget != null) {
                class_742 target = findPlayerExact(client, killTarget);
                if (target != null) {
                    aimAt(client, target);
                    float cooldown = client.field_1724.method_7261(0f);
                    if (cooldown >= 0.95f) {
                        double distSq = client.field_1724.method_5858(target);
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

            if (walkActive) {
                walkTickCounter++;
                class_3675.class_306 forwardKey = client.field_1690.field_1894.method_1429();
                if (walkTickCounter <= walkTargetTicks) {
                    class_304.method_1416(forwardKey, true);
                } else {
                    class_304.method_1416(forwardKey, false);
                    walkActive      = false;
                    walkTickCounter = 0;
                    walkTargetTicks = 0;
                    if ("walk".equals(activeTask)) activeTask = null;
                }
            }
        });
    }

    private static void handleIncoming(String raw) {
        GeminiCommandParser.GeminiCommand cmd = GeminiCommandParser.parse(raw);
        if (cmd == null) return;

        class_310 client = class_310.method_1551();
        if (client == null || client.field_1724 == null) return;

        try {
            switch (cmd.type) {
                case STOP      -> handleStop(client);
                case FOLLOW    -> handleFollow(client, cmd.arg1);
                case KILL      -> handleKill(client, cmd.arg1);
                case MINE      -> handleMine(client, cmd.arg1);
                case GOTO      -> handleGoto(client, cmd.arg1);
                case TOWER_UP  -> handleTowerUp(client, cmd.arg1);
                case WALK      -> handleWalk(client, cmd.arg1);
                case TPA       -> handleTpa(client, cmd.arg1);
                case TPACCEPT  -> handleTpAccept(client);
            }
        } catch (Exception e) {
            LOGGER.error("[GeminiBaritone] Command failed", e);
            sendFailed(client);
        }
    }

    private static void handleStop(class_310 client) {
        if (walkActive) {
            walkActive      = false;
            walkTickCounter = 0;
            walkTargetTicks = 0;
            class_304.method_1416(client.field_1690.field_1894.method_1429(), false);
        }
        sendBaritoneCommand(client, "#stop");
        if (killModeActive) {
            simulateKeyPress(client, GLFW.GLFW_KEY_K);
            simulateKeyPress(client, GLFW.GLFW_KEY_R);
        }
        killModeActive      = false;
        killTarget          = null;
        attackCooldownTicks = 0;
        activeTask          = null;
    }

    private static void handleFollow(class_310 client, String playerName) {
        if (playerName == null || playerName.isEmpty()) { sendFailed(client); return; }
        if (!playerOnline(client, playerName))          { sendFailed(client); return; }
        activeTask = "follow";
        sendBaritoneCommand(client, "#follow player " + playerName);
    }

    private static void handleKill(class_310 client, String playerName) {
        if (playerName == null || playerName.isEmpty()) { sendFailed(client); return; }
        if (!playerOnline(client, playerName))          { sendFailed(client); return; }
        killModeActive      = true;
        killTarget          = playerName;
        attackCooldownTicks = 0;
        activeTask          = "kill";
        sendBaritoneCommand(client, "#follow player " + playerName);
        simulateKeyPress(client, GLFW.GLFW_KEY_R);
        simulateKeyPress(client, GLFW.GLFW_KEY_K);
    }

    private static void handleMine(class_310 client, String blockName) {
        if (client.field_1687 == null) { sendFailed(client); return; }

        String lower = blockName.toLowerCase().replace(" ", "_");
        List<String> matches = new ArrayList<>();

        for (class_2248 block : class_7923.field_41175) {
            class_2960 id = class_7923.field_41175.method_10221(block);
            String path   = id.method_12832();
            if (path.contains(lower)
                    && !path.equals("air") && !path.equals("barrier")
                    && !path.equals("void_air") && !path.equals("cave_air")) {
                matches.add(path);
            }
        }

        if (matches.isEmpty()) { sendFailed(client); return; }

        activeTask = "mine";
        sendBaritoneCommand(client, "#mine " + String.join(" ", matches));
    }

    private static void handleGoto(class_310 client, String dest) {
        activeTask = "goto";
        sendBaritoneCommand(client, "#goto " + dest);
    }

    private static void handleTowerUp(class_310 client, String amountStr) {
        try {
            int amount     = Integer.parseInt(amountStr.trim());
            class_2338 pos = client.field_1724.method_24515();
            int targetY    = pos.method_10264() + amount;
            activeTask     = "tower";
            sendBaritoneCommand(client, "#goto " + pos.method_10263() + " " + targetY + " " + pos.method_10260());
        } catch (NumberFormatException e) {
            sendFailed(client);
        }
    }

    private static void handleWalk(class_310 client, String arg) {
        int blocks = 1;
        if (arg != null && !arg.isEmpty()) {
            try {
                blocks = Integer.parseInt(arg.trim().split("\\s+")[0]);
            } catch (NumberFormatException ignored) {}
        }
        walkTargetTicks = (int) Math.round(blocks * TICKS_PER_BLOCK);
        walkActive      = true;
        walkTickCounter = 0;
        activeTask      = "walk";
    }

    private static void handleTpa(class_310 client, String playerName) {
        if (playerName == null || playerName.isEmpty()) { sendFailed(client); return; }
        if (!playerOnline(client, playerName))          { sendFailed(client); return; }
        client.execute(() -> {
            if (client.field_1724 != null) {
                client.field_1724.field_3944.method_45728("/tpa " + playerName);
                client.field_1724.method_7353(
                    class_2561.method_43470("§7[Gemini] §f/tpa " + playerName),
                    false
                );
            }
        });
    }

    private static void handleTpAccept(class_310 client) {
        client.execute(() -> {
            if (client.field_1724 != null) {
                client.field_1724.field_3944.method_45728("/tpaccept");
                client.field_1724.method_7353(
                    class_2561.method_43470("§7[Gemini] §f/tpaccept"),
                    false
                );
            }
        });
    }

    private static void sendBaritoneCommand(class_310 client, String cmd) {
        client.execute(() -> {
            if (client.field_1724 != null) {
                client.field_1724.field_3944.method_45729(cmd);
                client.field_1724.method_7353(class_2561.method_43470("§7[Gemini→Baritone] §f" + cmd), false);
            }
        });
    }

    private static void sendFailed(class_310 client) {
        client.execute(() -> {
            if (client.field_1724 != null) {
                client.field_1724.method_7353(class_2561.method_43470("Failed."), false);
            }
        });
    }

    private static boolean playerOnline(class_310 client, String name) {
        if (client.method_1562() == null) return false;
        for (class_640 entry : client.method_1562().method_2880()) {
            if (entry.method_2966().name().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static class_742 findPlayerExact(class_310 client, String name) {
        if (client.field_1687 == null || name == null) return null;
        for (class_742 p : client.field_1687.method_18456()) {
            if (p.method_5477().getString().equalsIgnoreCase(name)) return p;
        }
        return null;
    }

    private static void aimAt(class_310 client, class_742 target) {
        if (client.field_1724 == null) return;
        class_243 from = client.field_1724.method_33571();
        class_243 to   = target.method_33571();
        double dx  = to.field_1352 - from.field_1352;
        double dy  = to.field_1351 - from.field_1351;
        double dz  = to.field_1350 - from.field_1350;
        double h   = Math.sqrt(dx * dx + dz * dz);
        client.field_1724.method_36456((float)(Math.toDegrees(Math.atan2(dz, dx))) - 90f);
        client.field_1724.method_36457((float)(-Math.toDegrees(Math.atan2(dy, h))));
    }

    private static void simulateKeyPress(class_310 client, int glfwKey) {
        client.execute(() -> {
            long window  = client.method_22683().method_4490();
            int scancode = GLFW.glfwGetKeyScancode(glfwKey);
            GLFWKeyCallbackI callback = GLFW.glfwSetKeyCallback(window, null);
            if (callback != null) {
                GLFW.glfwSetKeyCallback(window, callback);
                callback.invoke(window, glfwKey, scancode, GLFW.GLFW_PRESS,   0);
                callback.invoke(window, glfwKey, scancode, GLFW.GLFW_RELEASE,  0);
            }
            class_3675.class_306 key = class_3675.class_307.field_1668.method_1447(glfwKey);
            class_304.method_1420(key);
        });
    }
}