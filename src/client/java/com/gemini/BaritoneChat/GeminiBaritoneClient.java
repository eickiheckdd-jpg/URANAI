package com.gemini.baritonechat;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
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
                // Use the rendered text directly; strip player-name prefix if present
                String raw = message.getString();
                if (raw.startsWith("<")) {
                    int close = raw.indexOf("> ");
                    if (close != -1) raw = raw.substring(close + 2).trim();
                } else {
                    int colon = raw.indexOf(": ");
                    if (colon != -1 && colon < 32) raw = raw.substring(colon + 2).trim();
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

            if (killModeActive && killTarget != null) {
                AbstractClientPlayerEntity target = findPlayerExact(client, killTarget);
                if (target != null) {
                    aimAt(client, target);
                    float cooldown = client.player.getAttackCooldownProgress(0f);
                    if (cooldown >= 0.95f) {
                        double distSq = client.player.squaredDistanceTo(target);
                        if (distSq <= 16.0) {
                            client.interactionManager.attackEntity(client.player, target);
                            client.player.swingHand(Hand.MAIN_HAND);
                            attackCooldownTicks = 0;
                        }
                    } else {
                        attackCooldownTicks++;
                    }
                }
            }

            if (walkActive) {
                walkTickCounter++;
                InputUtil.Key forwardKey = client.options.forwardKey.getDefaultKey();
                if (walkTickCounter <= walkTargetTicks) {
                    KeyBinding.setKeyPressed(forwardKey, true);
                } else {
                    KeyBinding.setKeyPressed(forwardKey, false);
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

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return;

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

    private static void handleStop(MinecraftClient client) {
        if (walkActive) {
            walkActive      = false;
            walkTickCounter = 0;
            walkTargetTicks = 0;
            KeyBinding.setKeyPressed(client.options.forwardKey.getDefaultKey(), false);
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

    private static void handleFollow(MinecraftClient client, String playerName) {
        if (playerName == null || playerName.isEmpty()) { sendFailed(client); return; }
        if (!playerOnline(client, playerName))          { sendFailed(client); return; }
        activeTask = "follow";
        sendBaritoneCommand(client, "#follow player " + playerName);
    }

    private static void handleKill(MinecraftClient client, String playerName) {
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

    private static void handleMine(MinecraftClient client, String blockName) {
        if (client.world == null) { sendFailed(client); return; }

        String lower = blockName.toLowerCase().replace(" ", "_");
        List<String> matches = new ArrayList<>();

        for (Block block : Registries.BLOCK) {
            Identifier id = Registries.BLOCK.getId(block);
            String path   = id.getPath();
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

    private static void handleGoto(MinecraftClient client, String dest) {
        activeTask = "goto";
        sendBaritoneCommand(client, "#goto " + dest);
    }

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

    private static void handleWalk(MinecraftClient client, String arg) {
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

    private static void handleTpa(MinecraftClient client, String playerName) {
        if (playerName == null || playerName.isEmpty()) { sendFailed(client); return; }
        if (!playerOnline(client, playerName))          { sendFailed(client); return; }
        client.execute(() -> {
            if (client.player != null) {
                client.player.networkHandler.sendChatMessage("/tpa " + playerName);
                client.player.sendMessage(
                    Text.literal("§7[Gemini] §f/tpa " + playerName),
                    false
                );
            }
        });
    }

    private static void handleTpAccept(MinecraftClient client) {
        client.execute(() -> {
            if (client.player != null) {
                client.player.networkHandler.sendChatMessage("/tpaccept");
                client.player.sendMessage(
                    Text.literal("§7[Gemini] §f/tpaccept"),
                    false
                );
            }
        });
    }

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

    private static AbstractClientPlayerEntity findPlayerExact(MinecraftClient client, String name) {
        if (client.world == null || name == null) return null;
        for (AbstractClientPlayerEntity p : client.world.getPlayers()) {
            if (p.getName().getString().equalsIgnoreCase(name)) return p;
        }
        return null;
    }

    private static void aimAt(MinecraftClient client, AbstractClientPlayerEntity target) {
        if (client.player == null) return;
        Vec3d from = client.player.getEyePos();
        Vec3d to   = target.getEyePos();
        double dx  = to.x - from.x;
        double dy  = to.y - from.y;
        double dz  = to.z - from.z;
        double h   = Math.sqrt(dx * dx + dz * dz);
        client.player.setYaw((float)(Math.toDegrees(Math.atan2(dz, dx))) - 90f);
        client.player.setPitch((float)(-Math.toDegrees(Math.atan2(dy, h))));
    }

    private static void simulateKeyPress(MinecraftClient client, int glfwKey) {
        client.execute(() -> {
            long window  = client.getWindow().getHandle();
            int scancode = GLFW.glfwGetKeyScancode(glfwKey);
            GLFWKeyCallbackI callback = GLFW.glfwSetKeyCallback(window, null);
            if (callback != null) {
                GLFW.glfwSetKeyCallback(window, callback);
                callback.invoke(window, glfwKey, scancode, GLFW.GLFW_PRESS,   0);
                callback.invoke(window, glfwKey, scancode, GLFW.GLFW_RELEASE,  0);
            }
            InputUtil.Key key = InputUtil.Type.KEYSYM.createFromCode(glfwKey);
            KeyBinding.onKeyPressed(key);
        });
    }
}