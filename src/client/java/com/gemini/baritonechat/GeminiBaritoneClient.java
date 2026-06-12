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
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
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
import java.util.Set;

@Environment(EnvType.CLIENT)
public class GeminiBaritoneClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("gemini-baritone");

    // ── State flags ───────────────────────────────────────────────────────────
    private static volatile boolean killModeActive  = false;
    private static volatile String  killTarget      = null;
    private static volatile String  activeTask      = null;

    // ── Walk ─────────────────────────────────────────────────────────────────
    private static volatile boolean walkActive      = false;
    private static int              walkTickCounter = 0;
    private static final int        WALK_TICKS      = 14;

    // ── Attack cooldown ───────────────────────────────────────────────────────
    private static int attackCooldownTicks = 0;

    // ── Auto eat ─────────────────────────────────────────────────────────────
    private static volatile boolean autoEatEnabled  = false;
    private static int              eatCooldown     = 0;
    private static final int        EAT_TICKS       = 32; // ticks to hold use item

    // Foods excluded from auto eat (valuable / fight foods / side-effect foods)
    private static final Set<String> EXCLUDED_FOODS = Set.of(
        "golden_apple", "enchanted_golden_apple",
        "golden_carrot", "chorus_fruit", "suspicious_stew"
    );

    // ── Patrol ───────────────────────────────────────────────────────────────
    private static volatile boolean patrolActive     = false;
    private static int[][]          patrolWaypoints  = null; // [point][x, z]
    private static int              patrolIndex      = 0;
    private static final int        PATROL_THRESHOLD = 4; // blocks squared (2 block radius)
    private static final int        PATROL_TIMEOUT   = 1200; // 60s at 20tps before #stop
    private static int              patrolTicksSinceMove = 0;

    // ── Come back ────────────────────────────────────────────────────────────
    private static BlockPos savedPosition = null;

    // ── Mining with target amount ─────────────────────────────────────────────
    private static volatile boolean mineCountActive  = false;
    private static String           mineCountItem    = null; // registry path to check
    private static int              mineCountTarget  = 0;   // target total in inventory

    @Override
    public void onInitializeClient() {
        LOGGER.info("[GeminiBaritone] Client initialised");

        ClientReceiveMessageEvents.CHAT.register(
            (message, signedMessage, sender, params, receptionTimestamp) -> {
                String raw = null;
                if (signedMessage != null) raw = signedMessage.getSignedContent();
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

        ClientSendMessageEvents.CHAT.register(message -> {
            // Handle special no-prefix commands
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
            handleIncoming(message);
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            // ── Kill mode: aim assist + auto attack ───────────────────────────
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

            // ── Auto eat ──────────────────────────────────────────────────────
            if (autoEatEnabled) {
                if (eatCooldown > 0) {
                    eatCooldown--;
                    // Hold use-item key while eating
                    KeyBinding.setKeyPressed(client.options.useKey.getDefaultKey(), eatCooldown > 0);
                } else {
                    int hunger = client.player.getHungerManager().getFoodLevel();
                    if (hunger <= 10) { // half bar = 10/20
                        int slot = findBestFoodSlot(client);
                        if (slot != -1) {
                            client.player.getInventory().selectedSlot = slot < 9 ? slot : client.player.getInventory().selectedSlot;
                            if (slot < 9) client.player.getInventory().selectedSlot = slot;
                            KeyBinding.setKeyPressed(client.options.useKey.getDefaultKey(), true);
                            eatCooldown = EAT_TICKS;
                        }
                    }
                }
            }

            // ── Walk hold ─────────────────────────────────────────────────────
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

            // ── Patrol ────────────────────────────────────────────────────────
            if (patrolActive && patrolWaypoints != null) {
                int[] wp    = patrolWaypoints[patrolIndex];
                int   wpX   = wp[0];
                int   wpZ   = wp[1];
                BlockPos pos = client.player.getBlockPos();
                double distSq = Math.pow(pos.getX() - wpX, 2) + Math.pow(pos.getZ() - wpZ, 2);

                if (distSq <= PATROL_THRESHOLD) {
                    // Reached waypoint — advance to next
                    patrolIndex = (patrolIndex + 1) % patrolWaypoints.length;
                    patrolTicksSinceMove = 0;
                    int[] next = patrolWaypoints[patrolIndex];
                    int   nextY = client.player.getBlockPos().getY();
                    sendBaritoneCommand(client, "#goto " + next[0] + " " + nextY + " " + next[1]);
                } else {
                    patrolTicksSinceMove++;
                    if (patrolTicksSinceMove >= PATROL_TIMEOUT) {
                        // Baritone failed to reach point — stop patrol
                        patrolActive         = false;
                        patrolTicksSinceMove = 0;
                        sendBaritoneCommand(client, "#stop");
                        client.player.sendMessage(Text.literal("§c[Gemini] Patrol failed to reach waypoint. Stopped."), false);
                    }
                }
            }

            // ── Mine count check ──────────────────────────────────────────────
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

        // Save position before any command
        savedPosition = client.player.getBlockPos();

        try {
            switch (cmd.type) {
                case STOP      -> handleStop(client);
                case FOLLOW    -> handleFollow(client, cmd.arg1);
                case KILL      -> handleKill(client, cmd.arg1);
                case MINE      -> handleMine(client, cmd.arg1, cmd.arg2);
                case GOTO      -> handleGoto(client, cmd.arg1);
                case TOWER_UP  -> handleTowerUp(client, cmd.arg1);
                case WALK      -> handleWalk(client, cmd.arg1);
                case EAT       -> handleEat(client);
                case AUTO_EAT  -> handleAutoEat(client);
                case PATROL    -> handlePatrol(client, cmd.arg1);
                case COME_BACK -> handleComeBack(client);
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

        autoEatEnabled       = false;
        eatCooldown          = 0;
        patrolActive         = false;
        patrolTicksSinceMove = 0;
        mineCountActive      = false;
        mineCountItem        = null;
        mineCountTarget      = 0;
        KeyBinding.setKeyPressed(client.options.useKey.getDefaultKey(), false);

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

    // ──────────────────────────────────────────────────────────────────────────
    // FOLLOW
    // ──────────────────────────────────────────────────────────────────────────

    private static void handleFollow(MinecraftClient client, String playerName) {
        if (playerName == null || playerName.isEmpty()) { sendFailed(client); return; }
        if (!playerOnline(client, playerName))          { sendFailed(client); return; }
        activeTask = "follow";
        sendBaritoneCommand(client, "#follow player " + playerName);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // KILL
    // ──────────────────────────────────────────────────────────────────────────

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

    // ──────────────────────────────────────────────────────────────────────────
    // MINE (with optional count)
    // arg1 = block name, arg2 = optional count string
    // ──────────────────────────────────────────────────────────────────────────

    private static void handleMine(MinecraftClient client, String blockName, String countStr) {
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

        String resolved = String.join(" ", matches);
        activeTask = "mine";

        // Handle optional count — "mine diamond 32"
        if (countStr != null && !countStr.isEmpty()) {
            try {
                int amount = Integer.parseInt(countStr.trim());
                int current = countItem(client, lower);
                mineCountTarget = current + amount; // mine X MORE on top of what you have
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
    // EAT — manual one-time eat
    // ──────────────────────────────────────────────────────────────────────────

    private static void handleEat(MinecraftClient client) {
        int hunger = client.player.getHungerManager().getFoodLevel();
        if (hunger >= 20) {
            client.player.sendMessage(Text.literal("§7[Gemini] Not hungry."), false);
            return;
        }
        int slot = findBestFoodSlot(client);
        if (slot == -1) {
            client.player.sendMessage(Text.literal("§c[Gemini] No food in inventory."), false);
            return;
        }
        if (slot < 9) client.player.getInventory().selectedSlot = slot;
        KeyBinding.setKeyPressed(client.options.useKey.getDefaultKey(), true);
        eatCooldown = EAT_TICKS;
        // Re-use autoEat tick logic by temporarily enabling eat cooldown
        // useKey will be released after EAT_TICKS in the auto eat tick block
    }

    // ──────────────────────────────────────────────────────────────────────────
    // AUTO EAT — toggle
    // ──────────────────────────────────────────────────────────────────────────

    private static void handleAutoEat(MinecraftClient client) {
        autoEatEnabled = !autoEatEnabled;
        client.player.sendMessage(
            Text.literal("§7[Gemini] Auto eat " + (autoEatEnabled ? "§aON" : "§cOFF")),
            false
        );
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PATROL — 3 waypoints, X and Z only, Y = current at time of each goto
    // arg1 = "x1 z1 x2 z2 x3 z3" or "x1 z1 y1 x2 z2 y2 x3 z3 y3"
    // ──────────────────────────────────────────────────────────────────────────

    private static void handlePatrol(MinecraftClient client, String arg) {
        if (arg == null || arg.isEmpty()) { sendFailed(client); return; }

        String[] parts = arg.trim().split("\\s+");
        int[][] waypoints;

        try {
            if (parts.length == 6) {
                // X Z X Z X Z
                waypoints = new int[][] {
                    { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) },
                    { Integer.parseInt(parts[2]), Integer.parseInt(parts[3]) },
                    { Integer.parseInt(parts[4]), Integer.parseInt(parts[5]) }
                };
            } else if (parts.length == 9) {
                // X Z Y X Z Y X Z Y — Y ignored, we use current Y at each step
                waypoints = new int[][] {
                    { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) },
