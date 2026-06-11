package com.example.geminibaritone;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@Environment(EnvType.CLIENT)
public class GeminiBaritoneClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("gemini-baritone");

    // ── Drop task state ────────────────────────────────────────────────────────
    private static boolean dropTaskActive = false;
    private static int dropTickCounter = 0;
    private static final int DROP_DELAY_TICKS = 100; // 5 seconds at 20 ticks/sec

    private static String pendingDropMode   = null; // "all", "one", "stack"
    private static String pendingDropItem   = null;
    private static String pendingDropTarget = null;

    // ── Aim assist state ───────────────────────────────────────────────────────
    private static boolean aimAssistActive = false;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[GeminiBaritone] Client initialised");

        // ── Received chat (other players) ─────────────────────────────────────
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            handleIncoming(message.getString());
        });

        // ── System messages ────────────────────────────────────────────────────
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) handleIncoming(message.getString());
        });

        // ── Your own outgoing chat ─────────────────────────────────────────────
        ClientSendMessageEvents.CHAT.register((message) -> {
            handleIncoming(message);
        });

        // ── Tick handler — aim assist + drop after delay ───────────────────────
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;

            // Aim assist: continuously face the target player
            if (aimAssistActive && pendingDropTarget != null) {
                AbstractClientPlayerEntity target = findPlayer(client, pendingDropTarget);
                if (target != null) {
                    aimAt(client, target);
                }
            }

            // Drop task countdown
            if (dropTaskActive) {
                dropTickCounter++;

                if (dropTickCounter >= DROP_DELAY_TICKS) {
                    // Stop following
                    sendBaritoneCommand(client, "#stop");

                    // Execute the drop
                    executeDrop(client);

                    // Disable aim assist
                    aimAssistActive = false;

                    // Reset state
                    dropTaskActive = false;
                    dropTickCounter = 0;
                    pendingDropMode = null;
                    pendingDropItem = null;
                    pendingDropTarget = null;
                }
            }
        });
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Incoming message handler
    // ──────────────────────────────────────────────────────────────────────────

    private static void handleIncoming(String raw) {
        GeminiCommandParser.GeminiCommand cmd = GeminiCommandParser.parse(raw);
        if (cmd == null) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return;

        if (cmd.type.equals("baritone")) {
            LOGGER.info("[GeminiBaritone] Baritone command: {}", cmd.baritoneCmd);
            sendBaritoneCommand(client, cmd.baritoneCmd);

        } else if (cmd.type.equals("drop")) {
            handleDropCommand(client, cmd);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Drop command handler
    // ──────────────────────────────────────────────────────────────────────────

    private static void handleDropCommand(MinecraftClient client, GeminiCommandParser.GeminiCommand cmd) {
        // Check if target player exists in the world
        AbstractClientPlayerEntity target = findPlayer(client, cmd.dropTarget);
        if (target == null) {
            client.player.sendMessage(
                Text.literal("§c[GeminiBaritone] Error: Player '" + cmd.dropTarget + "' not found."),
                false
            );
            return;
        }

        client.player.sendMessage(
            Text.literal("§7[GeminiBaritone] §fDropping to " + cmd.dropTarget + " in 5 seconds..."),
            false
        );

        // Start following the target
        sendBaritoneCommand(client, "#follow player " + cmd.dropTarget);

        // Enable aim assist immediately
        aimAssistActive = true;

        // Store drop task
        pendingDropMode   = cmd.dropMode;
        pendingDropItem   = cmd.dropItem;
        pendingDropTarget = cmd.dropTarget;
        dropTickCounter   = 0;
        dropTaskActive    = true;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Execute the actual drop after delay
    // ──────────────────────────────────────────────────────────────────────────

    private static void executeDrop(MinecraftClient client) {
        if (client.player == null) return;

        PlayerInventory inv = client.player.getInventory();

        switch (pendingDropMode) {
            case "all" -> {
                // Drop every non-empty slot
                for (int i = 0; i < inv.size(); i++) {
                    ItemStack stack = inv.getStack(i);
                    if (!stack.isEmpty()) {
                        client.player.dropItem(stack, true);
                        inv.setStack(i, ItemStack.EMPTY);
                    }
                }
                client.player.sendMessage(Text.literal("§7[GeminiBaritone] §fDropped all items."), false);
            }

            case "one" -> {
                // Find the item and drop exactly 1
                for (int i = 0; i < inv.size(); i++) {
                    ItemStack stack = inv.getStack(i);
                    if (!stack.isEmpty() && itemMatchesName(stack, pendingDropItem)) {
                        ItemStack toDrop = stack.split(1);
                        client.player.dropItem(toDrop, false);
                        client.player.sendMessage(
                            Text.literal("§7[GeminiBaritone] §fDropped 1x " + pendingDropItem),
                            false
                        );
                        return;
                    }
                }
                client.player.sendMessage(
                    Text.literal("§c[GeminiBaritone] Error: " + pendingDropItem + " not found in inventory."),
                    false
                );
            }

            case "stack" -> {
                // Find the item and drop the whole stack
                for (int i = 0; i < inv.size(); i++) {
                    ItemStack stack = inv.getStack(i);
                    if (!stack.isEmpty() && itemMatchesName(stack, pendingDropItem)) {
                        client.player.dropItem(stack, true);
                        inv.setStack(i, ItemStack.EMPTY);
                        client.player.sendMessage(
                            Text.literal("§7[GeminiBaritone] §fDropped stack of " + pendingDropItem),
                            false
                        );
                        return;
                    }
                }
                client.player.sendMessage(
                    Text.literal("§c[GeminiBaritone] Error: " + pendingDropItem + " not found in inventory."),
                    false
                );
            }
        }
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

    private static AbstractClientPlayerEntity findPlayer(MinecraftClient client, String name) {
        if (client.world == null) return null;
        List<AbstractClientPlayerEntity> players = client.world.getPlayers();
        for (AbstractClientPlayerEntity p : players) {
            if (p.getName().getString().equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    private static void aimAt(MinecraftClient client, AbstractClientPlayerEntity target) {
        if (client.player == null) return;

        Vec3d from = client.player.getEyePos();
        Vec3d to   = target.getEyePos();

        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;

        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        float yaw   = (float)(Math.toDegrees(Math.atan2(dz, dx))) - 90f;
        float pitch = (float)(-Math.toDegrees(Math.atan2(dy, horizontalDist)));

        client.player.setYaw(yaw);
        client.player.setPitch(pitch);
    }

    private static boolean itemMatchesName(ItemStack stack, String name) {
        // Match against the item's registry ID (e.g. "diamond_sword")
        String itemId = net.minecraft.registry.Registries.ITEM
            .getId(stack.getItem())
            .getPath(); // gets the part after "minecraft:"
        return itemId.equalsIgnoreCase(name.toLowerCase());
    }
}
        // ── Messages YOU type yourself ──
        ClientSendMessageEvents.CHAT.register((message) -> {
            String baritoneCmd = GeminiCommandParser.parse(message);
            if (baritoneCmd != null) {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client != null) {
                    executeBaritoneCommand(client, baritoneCmd);
                }
            }
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