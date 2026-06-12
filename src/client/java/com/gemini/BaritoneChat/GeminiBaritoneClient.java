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
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
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
    private static final int        WALK_TICKS      = 14;

    private static int attackCooldownTicks = 0;

    private static volatile boolean mineCountActive = false;
    private static String           mineCountItem   = null;
    private static int              mineCountTarget = 0;

    // Drop task state
    private static volatile boolean dropTaskActive  = false;
    private static volatile boolean dropAll         = false;
    private static volatile int     dropAmount      = 0;
    private static volatile String  dropItem        = null;
    private static volatile String  dropTarget      = null;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[GeminiBaritone] Client initialised");

        ClientReceiveMessageEvents.CHAT.register(
            (message, signedMessage, sender, params, receptionTimestamp) -> {
                String raw = null;
                String senderName = null;

                if (signedMessage != null) raw = signedMessage.getSignedContent();
                if (sender != null) senderName = sender.name();

                if (raw == null || raw.isEmpty()) {
                    raw = message.getString();
                    if (raw.startsWith("<")) {
                        int close = raw.indexOf("> ");
                        if (close != -1) {
                            senderName = raw.substring(1, close);
                            raw = raw.substring(close + 2).trim();
                        }
                    } else {
                        int colon = raw.indexOf(": ");
                        if (colon != -1 && colon < 32) {
                            senderName = raw.substring(0, colon);
                            raw = raw.substring(colon + 2).trim();
                        }
                    }
                }

                LOGGER.info("[GeminiBaritone] CHAT sender='{}' raw='{}'", senderName, raw);
                handleIncoming(raw, senderName);
            }
        );

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) handleIncoming(message.getString(), null);
        });

        ClientSendMessageEvents.CHAT.register(message -> {
            if (message.equalsIgnoreCase("gemini commands")) {
                MinecraftClient c = MinecraftClient.getInstance();
                if (c != null && c.player != null) sendHelp(c);
                return;
            }
            if (message.equalsIgnoreCase("gemini status")) {
                MinecraftClient c = MinecraftClient.getInstance();
                if (c != null && c.player != null) sendStatus(c);
                return;
            }
            handleIncoming(message, null);
        });

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
                if (walkTickCounter <= WALK_TICKS) {
                    KeyBinding.setKeyPressed(forwardKey, true);
                } else {
                    KeyBinding.setKeyPressed(forwardKey, false);
                    walkActive      = false;
                    walkTickCounter = 0;
                    if ("walk".equals(activeTask)) activeTask = null;
                }
            }

            if (mineCountActive && mineCountItem != null) {
                int current = countItem(client, mineCountItem);
                if (current >= mineCountTarget) {
                    mineCountActive = false;
                    mineCountItem   = null;
                    mineCountTarget = 0;
                    activeTask      = null;
                    sendBaritoneCommand(client, "#stop");
                    client.player.sendMessage(Text.literal("§a[Gemini] Mining goal reached!"), false);
                }
            }

            // Drop task — aim at target, drop when within 3 blocks
            if (dropTaskActive && dropTarget != null) {
                AbstractClientPlayerEntity target = findPlayerExact(client, dropTarget);
                if (target != null) {
                    aimAt(client, target);
                    double distSq = client.player.squaredDistanceTo(target);
                    if (distSq <= 9.0) {
                        executeDrop(client);
                        sendBaritoneCommand(client, "#stop");
                        dropTaskActive = false;
                        dropTarget     = null;
                        dropItem       = null;
                    }
                } else {
                    // Target left or not found — cancel
                    dropTaskActive = false;
                    dropTarget     = null;
                    client.player.sendMessage(Text.literal("Failed."), false);
                }
            }
        });
    }

    private static void handleIncoming(String raw, String senderName) {
        GeminiCommandParser.GeminiCommand cmd = GeminiCommandParser.parse(raw, senderName);
        if (cmd == null) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return;

        try {
            switch (cmd.type) {
                case STOP        -> handleStop(client);
                case FOLLOW      -> handleFollow(client, cmd.arg1);
                case KILL        -> handleKill(client, cmd.arg1);
                case MINE        -> handleMine(client, cmd.arg1, cmd.arg2);
                case GOTO        -> handleGoto(client, cmd.arg1);
                case TOWER_UP    -> handleTowerUp(client, cmd.arg1);
                case WALK        -> handleWalk(client, cmd.arg1);
                case COORDS      -> handleCoords(client);
                case HEALTH      -> handleHealth(client);
                case DROP_ALL    -> handleDropAll(client, cmd.arg1);
                case DROP_AMOUNT -> handleDropAmount(client, cmd.arg1, cmd.arg2, cmd.arg3);
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
            KeyBinding.setKeyPressed(client.options.forwardKey.getDefaultKey(), false);
        }

        mineCountActive = false;
        mineCountItem   = null;
        mineCountTarget = 0;
        dropTaskActive  = false;
        dropTarget      = null;
        dropItem        = null;

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

    private static void handleMine(MinecraftClient client, String blockName, String countStr) {
        if (client.world == null) { sendFailed(client); return; }

        String lower = blockName.toLowerCase().replace(" ", "_");

        // Check block alias map first
        String aliasResolved = GeminiCommandParser.resolveBlock(blockName);
        String resolved;

        if (aliasResolved != null) {
            resolved = aliasResolved;
        } else {
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
            resolved = String.join(" ", matches);
        }

        activeTask = "mine";

        if (countStr != null && !countStr.isEmpty()) {
            try {
                int amount  = Integer.parseInt(countStr.trim());
                int current = countItem(client, lower);
                mineCountTarget = current + amount;
                mineCountItem   = lower;
                mineCountActive = true;
                client.player.sendMessage(
                    Text.literal("§7[Gemini] Mining until " + mineCountTarget + "x " + lower + " (have " + current + ")"),
                    false
                );
            } catch (NumberFormatException e) {
                sendFailed(client);
                return;
            }
        }

        sendBaritoneCommand(client, "#mine " + resolved);
    }

    private static void handleGoto(MinecraftClient client, String dest) {
        activeTask = "goto";
        sendBaritoneCommand(client, "#goto " + dest);
    }

    private static void handleTowerUp(MinecraftClient client, String amountStr) {
        try {
            int amount   = Integer.parseInt(amountStr.trim());
            BlockPos pos = client.player.getBlockPos();
            activeTask   = "tower";
            sendBaritoneCommand(client, "#goto " + pos.getX() + " " + (pos.getY() + amount) + " " + pos.getZ());
        } catch (NumberFormatException e) {
            sendFailed(client);
        }
    }

    private static void handleWalk(MinecraftClient client, String arg) {
        walkActive      = true;
        walkTickCounter = 0;
        activeTask      = "walk";
    }

    private static void handleCoords(MinecraftClient client) {
        BlockPos pos = client.player.getBlockPos();
        client.player.networkHandler.sendChatMessage(
            "Coords: " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
        );
    }

    private static void handleHealth(MinecraftClient client) {
        int hp     = (int) client.player.getHealth();
        int maxHp  = (int) client.player.getMaxHealth();
        int hunger = client.player.getHungerManager().getFoodLevel();
        client.player.networkHandler.sendChatMessage(
            "Health: " + hp + "/" + maxHp + " | Hunger: " + hunger + "/20"
        );
    }

    private static void handleDropAll(MinecraftClient client, String targetName) {
        if (targetName == null || targetName.isEmpty()) {
            // No target — drop at own feet
            dropAllItems(client);
            return;
        }
        if (!playerOnline(client, targetName)) { sendFailed(client); return; }
        dropAll        = true;
        dropItem       = null;
        dropAmount     = 0;
        dropTarget     = targetName;
        dropTaskActive = true;
        activeTask     = "drop";
        sendBaritoneCommand(client, "#follow player " + targetName);
    }

    private static void handleDropAmount(MinecraftClient client, String countStr, String itemName, String targetName) {
        if (countStr == null || itemName == null) { sendFailed(client); return; }
        try {
            int count = Integer.parseInt(countStr.trim());
            if (targetName == null || targetName.isEmpty()) {
                // No target — drop at own feet
                dropAmountItems(client, itemName, count);
                return;
            }
            if (!playerOnline(client, targetName)) { sendFailed(client); return; }
            dropAll        = false;
            dropAmount     = count;
            dropItem       = itemName;
            dropTarget     = targetName;
            dropTaskActive = true;
            activeTask     = "drop";
            sendBaritoneCommand(client, "#follow player " + targetName);
        } catch (NumberFormatException e) {
            sendFailed(client);
        }
    }

    private static void executeDrop(MinecraftClient client) {
        if (dropAll) {
            dropAllItems(client);
        } else {
            dropAmountItems(client, dropItem, dropAmount);
        }
    }

    private static void dropAllItems(MinecraftClient client) {
        PlayerInventory inv = client.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty()) {
                client.player.dropItem(stack, true);
                inv.setStack(i, ItemStack.EMPTY);
            }
        }
    }

    private static void dropAmountItems(MinecraftClient client, String itemName, int amount) {
        if (itemName == null) return;
        PlayerInventory inv = client.player.getInventory();
        int remaining = amount;
        for (int i = 0; i < inv.size() && remaining > 0; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            String id = Registries.ITEM.getId(stack.getItem()).getPath();
            if (!id.contains(itemName.toLowerCase().replace(" ", "_"))) continue;
            int toDrop = Math.min(remaining, stack.getCount());
            client.player.dropItem(stack.split(toDrop), false);
            remaining -= toDrop;
        }
    }

    private static void sendHelp(MinecraftClient client) {
        String[] lines = {
            "=== Gemini Commands ===",
            "hey gemini follow <player>",
            "hey gemini kill <player>",
            "hey gemini mine <block> [amount]",
            "hey gemini go to <x y z>",
            "hey gemini tower up <n>",
            "hey gemini walk",
            "hey gemini drop all [to <player>]",
            "hey gemini drop <n> <item> [to <player>]",
            "hey gemini health",
            "hey gemini coords",
            "hey gemini stop",
            "gemini status"
        };
        for (String line : lines) {
            client.player.networkHandler.sendChatMessage(line);
        }
    }

    private static void sendStatus(MinecraftClient client) {
        String task  = activeTask != null ? activeTask : "none";
        String kill  = killModeActive ? "ON -> " + killTarget : "OFF";
        String mine  = mineCountActive ? mineCountItem + " -> " + mineCountTarget : "OFF";
        String drop  = dropTaskActive ? "dropping to " + dropTarget : "OFF";
        client.player.networkHandler.sendChatMessage(
            "[Gemini Status] Task:" + task + " | Kill:" + kill + " | Mine:" + mine + " | Drop:" + drop
        );
    }

    private static void sendBaritoneCommand(MinecraftClient client, String cmd) {
        client.execute(() -> {
            if (client.player != null) {
                client.player.networkHandler.sendChatMessage(cmd);
                client.player.sendMessage(Text.literal("§7[Gemini] §f" + cmd), false);
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
        Vec3d from  = client.player.getEyePos();
        Vec3d to    = target.getEyePos();
        double dx   = to.x - from.x;
        double dy   = to.y - from.y;
        double dz   = to.z - from.z;
        double h    = Math.sqrt(dx * dx + dz * dz);
        client.player.setYaw((float)(Math.toDegrees(Math.atan2(dz, dx))) - 90f);
        client.player.setPitch((float)(-Math.toDegrees(Math.atan2(dy, h))));
    }

    private static int countItem(MinecraftClient client, String itemPath) {
        PlayerInventory inv = client.player.getInventory();
        int count = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            String id = Registries.ITEM.getId(stack.getItem()).getPath();
            if (id.contains(itemPath)) count += stack.getCount();
        }
        return count;
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
            KeyBinding.onKeyPressed(InputUtil.Type.KEYSYM.createFromCode(glfwKey));
        });
    }
}
