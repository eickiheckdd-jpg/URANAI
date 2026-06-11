package com.gemini.baritonechat;

import org.jetbrains.annotations.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Pure logic parser – no Minecraft imports, no MinecraftClient.
 * Converts "hey gemini ..." chat into a parsed GeminiCommand object.
 */
public final class GeminiCommandParser {

    private static final String PREFIX = "hey gemini ";

    private static final Map<String, String> BLOCK_ALIASES = new HashMap<>();

    static {
        BLOCK_ALIASES.put("diamond",    "diamond_ore deepslate_diamond_ore");
        BLOCK_ALIASES.put("iron",       "iron_ore deepslate_iron_ore");
        BLOCK_ALIASES.put("gold",       "gold_ore deepslate_gold_ore nether_gold_ore");
        BLOCK_ALIASES.put("coal",       "coal_ore deepslate_coal_ore");
        BLOCK_ALIASES.put("copper",     "copper_ore deepslate_copper_ore");
        BLOCK_ALIASES.put("emerald",    "emerald_ore deepslate_emerald_ore");
        BLOCK_ALIASES.put("lapis",      "lapis_ore deepslate_lapis_ore");
        BLOCK_ALIASES.put("redstone",   "redstone_ore deepslate_redstone_ore");
        BLOCK_ALIASES.put("ancient debris", "ancient_debris");
        BLOCK_ALIASES.put("netherite",  "ancient_debris");
        BLOCK_ALIASES.put("quartz",     "nether_quartz_ore");
        BLOCK_ALIASES.put("stone",      "stone");
        BLOCK_ALIASES.put("cobblestone","cobblestone");
        BLOCK_ALIASES.put("wood",       "oak_log birch_log spruce_log jungle_log acacia_log dark_oak_log mangrove_log cherry_log");
        BLOCK_ALIASES.put("oak",        "oak_log");
        BLOCK_ALIASES.put("birch",      "birch_log");
        BLOCK_ALIASES.put("spruce",     "spruce_log");
        BLOCK_ALIASES.put("mangrove",   "mangrove_log");
        BLOCK_ALIASES.put("cherry",     "cherry_log");
        BLOCK_ALIASES.put("bamboo",     "bamboo_block");
        BLOCK_ALIASES.put("crimson",    "crimson_stem");
        BLOCK_ALIASES.put("warped",     "warped_stem");
        BLOCK_ALIASES.put("planks",     "oak_planks birch_planks spruce_planks jungle_planks acacia_planks dark_oak_planks mangrove_planks cherry_planks crimson_planks warped_planks");
        BLOCK_ALIASES.put("gravel",     "gravel");
        BLOCK_ALIASES.put("sand",       "sand");
        BLOCK_ALIASES.put("dirt",       "dirt");
        BLOCK_ALIASES.put("obsidian",   "obsidian");
        BLOCK_ALIASES.put("glowstone",  "glowstone");
        BLOCK_ALIASES.put("netherrack", "netherrack");
        BLOCK_ALIASES.put("soul sand",  "soul_sand");
        BLOCK_ALIASES.put("magma",      "magma_block");
        BLOCK_ALIASES.put("basalt",     "basalt");
        BLOCK_ALIASES.put("clay",       "clay");
        BLOCK_ALIASES.put("endstone",   "end_stone");
        BLOCK_ALIASES.put("amethyst",   "amethyst_cluster budding_amethyst");
    }

    private GeminiCommandParser() {}

    /**
     * Represents a parsed gemini command.
     * type = "baritone", "drop", or null if unrecognised.
     */
    public static class GeminiCommand {
        public final String type;
        public final String baritoneCmd;   // for type="baritone"
        public final String dropMode;      // "all", "one", "stack"
        public final String dropItem;      // item name (for one/stack)
        public final String dropTarget;    // player name to drop to

        // Baritone command constructor
        public GeminiCommand(String baritoneCmd) {
            this.type = "baritone";
            this.baritoneCmd = baritoneCmd;
            this.dropMode = null;
            this.dropItem = null;
            this.dropTarget = null;
        }

        // Drop command constructor
        public GeminiCommand(String dropMode, String dropItem, String dropTarget) {
            this.type = "drop";
            this.baritoneCmd = null;
            this.dropMode = dropMode;
            this.dropItem = dropItem;
            this.dropTarget = dropTarget;
        }
    }

    @Nullable
    public static GeminiCommand parse(String rawMessage) {
        if (rawMessage == null) return null;

        String clean = rawMessage.replaceAll("§[0-9a-fk-or]", "").trim();
        if (!clean.toLowerCase().startsWith(PREFIX)) return null;

        String body = clean.substring(PREFIX.length()).trim();
        if (body.isEmpty()) return null;

        // ── stop ──────────────────────────────────────────────
        if (body.equalsIgnoreCase("stop")) {
            return new GeminiCommand("#stop");
        }

        // ── mine ──────────────────────────────────────────────
        if (body.toLowerCase().startsWith("mine ")) {
            String target = body.substring(5).trim().toLowerCase();
            if (target.isEmpty()) return null;
            String expanded = BLOCK_ALIASES.get(target);
            return new GeminiCommand("#mine " + (expanded != null ? expanded : target));
        }

        // ── go to ─────────────────────────────────────────────
        if (body.toLowerCase().startsWith("go to ")) {
            String dest = body.substring(6).trim();
            if (dest.isEmpty()) return null;
            return new GeminiCommand("#goto " + dest);
        }

        // ── follow ────────────────────────────────────────────
        if (body.toLowerCase().startsWith("follow ")) {
            String target = body.substring(7).trim();
            if (target.isEmpty()) return null;
            return new GeminiCommand("#follow player " + target);
        }

        // ── kill (alias for follow) ───────────────────────────
        if (body.toLowerCase().startsWith("kill ")) {
            String target = body.substring(5).trim();
            if (target.isEmpty()) return null;
            return new GeminiCommand("#follow player " + target);
        }

        // ── drop all to <player> ──────────────────────────────
        // "drop all to Steve"
        if (body.toLowerCase().startsWith("drop all to ")) {
            String target = body.substring(12).trim();
            if (target.isEmpty()) return null;
            return new GeminiCommand("all", null, target);
        }

        // ── drop 1 <item> to <player> ─────────────────────────
        // "drop 1 diamond_sword to Steve"
        if (body.toLowerCase().startsWith("drop 1 ")) {
            String rest = body.substring(7).trim(); // "diamond_sword to Steve"
            int toIdx = rest.toLowerCase().lastIndexOf(" to ");
            if (toIdx == -1) return null;
            String item = rest.substring(0, toIdx).trim();
            String target = rest.substring(toIdx + 4).trim();
            if (item.isEmpty() || target.isEmpty()) return null;
            return new GeminiCommand("one", item, target);
        }

        // ── drop stack <item> to <player> ─────────────────────
        // "drop stack diamond_sword to Steve"
        if (body.toLowerCase().startsWith("drop stack ")) {
            String rest = body.substring(11).trim(); // "diamond_sword to Steve"
            int toIdx = rest.toLowerCase().lastIndexOf(" to ");
            if (toIdx == -1) return null;
            String item = rest.substring(0, toIdx).trim();
            String target = rest.substring(toIdx + 4).trim();
            if (item.isEmpty() || target.isEmpty()) return null;
            return new GeminiCommand("stack", item, target);
        }

        return null;
    }
}