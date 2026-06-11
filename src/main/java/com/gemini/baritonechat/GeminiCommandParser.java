package com.gemini.baritonechat;

import org.jetbrains.annotations.Nullable;

/**
 * Pure logic parser – no Minecraft imports, no MinecraftClient.
 * Converts "hey gemini ..." chat into a parsed GeminiCommand object.
 *
 * Drop feature removed. Block alias map removed (dynamic lookup now in client).
 */
public final class GeminiCommandParser {

    private static final String PREFIX = "hey gemini ";

    private GeminiCommandParser() {}

    public enum CommandType {
        STOP, FOLLOW, KILL, MINE, GOTO, TOWER_UP, WALK
    }

    public static class GeminiCommand {
        public final CommandType type;
        public final String arg1; // player name, block name, destination, amount
        public final String arg2; // extra arg (e.g. block count for tower)

        public GeminiCommand(CommandType type, String arg1, String arg2) {
            this.type = type;
            this.arg1 = arg1;
            this.arg2 = arg2;
        }

        public GeminiCommand(CommandType type, String arg1) {
            this(type, arg1, null);
        }

        public GeminiCommand(CommandType type) {
            this(type, null, null);
        }
    }

    @Nullable
    public static GeminiCommand parse(String rawMessage) {
        if (rawMessage == null) return null;

        String clean = rawMessage.replaceAll("§[0-9a-fk-or]", "").trim();

        // Strip "PlayerName: " prefix if present (messages from other players)
        // e.g. "Steve: hey gemini mine dirt" → "hey gemini mine dirt"
        int colonIndex = clean.indexOf(": ");
        if (colonIndex != -1) {
            clean = clean.substring(colonIndex + 2).trim();
        }

        if (!clean.toLowerCase().startsWith(PREFIX)) return null;

        String body = clean.toLowerCase().substring(PREFIX.length()).trim();
        if (body.isEmpty()) return null;

        String lower = body;

        // ── stop ──────────────────────────────────────────────
        if (lower.equals("stop")) {
            return new GeminiCommand(CommandType.STOP);
        }

        // ── follow <player> ───────────────────────────────────
        if (lower.startsWith("follow ")) {
            String target = body.substring(7).trim();
            if (target.isEmpty()) return null;
            return new GeminiCommand(CommandType.FOLLOW, target);
        }

        // ── kill <player> ─────────────────────────────────────
        if (lower.startsWith("kill ")) {
            String target = body.substring(5).trim();
            if (target.isEmpty()) return null;
            return new GeminiCommand(CommandType.KILL, target);
        }

        // ── mine <block> ──────────────────────────────────────
        if (lower.startsWith("mine ")) {
            String target = body.substring(5).trim();
            if (target.isEmpty()) return null;
            return new GeminiCommand(CommandType.MINE, target);
        }

        // ── go to <dest> ──────────────────────────────────────
        if (lower.startsWith("go to ")) {
            String dest = body.substring(6).trim();
            if (dest.isEmpty()) return null;
            return new GeminiCommand(CommandType.GOTO, dest);
        }

        // ── tower up <n> blocks ───────────────────────────────
        if (lower.startsWith("tower up ")) {
            // "tower up 10 blocks" or "tower up 10"
            String rest = body.substring(9).trim().toLowerCase().replace(" blocks", "").trim();
            if (rest.isEmpty()) return null;
            return new GeminiCommand(CommandType.TOWER_UP, rest);
        }

        // ── walk 1 block / walk off the tower ─────────────────
        if (lower.startsWith("walk ")) {
            String rest = body.substring(5).trim();
            if (rest.isEmpty()) return null;
            return new GeminiCommand(CommandType.WALK, rest);
        }

        return null;
    }
}
