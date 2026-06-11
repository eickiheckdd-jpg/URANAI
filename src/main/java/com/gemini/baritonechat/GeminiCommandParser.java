package com.example.geminibaritone;

import org.jetbrains.annotations.Nullable;

/**
 * Pure logic parser – no Minecraft imports, no MinecraftClient.
 * Lives in src/main so it can be tested independently.
 *
 * Converts a raw chat message into a Baritone command string,
 * or returns null if the message does not match the "hey gemini" prefix.
 */
public final class GeminiCommandParser {

    /** The trigger prefix (case-insensitive). */
    private static final String PREFIX = "hey gemini ";

    private GeminiCommandParser() {}

    /**
     * Parse a chat message and return the Baritone command to run,
     * or {@code null} if the message is not a gemini command.
     *
     * <p>Examples:
     * <pre>
     *   "hey gemini mine stone"       → "#mine stone"
     *   "hey gemini go to 100 64 200" → "#goto 100 64 200"
     *   "hey gemini go to Steve"      → "#goto Steve"
     *   "hey gemini follow Steve"     → "#follow player Steve"
     *   "hey gemini kill Steve"       → "#follow player Steve"  ← alias!
     *   "hey gemini stop"             → "#stop"
     * </pre>
     */
    @Nullable
    public static String parse(String rawMessage) {
        if (rawMessage == null) return null;

        // Strip colour codes (§x) that servers sometimes inject
        String clean = rawMessage.replaceAll("§[0-9a-fk-or]", "").trim();

        // Case-insensitive prefix check
        if (!clean.toLowerCase().startsWith(PREFIX)) return null;

        // Everything after "hey gemini "
        String body = clean.substring(PREFIX.length()).trim();
        if (body.isEmpty()) return null;

        // ── stop ──────────────────────────────────────────────
        if (body.equalsIgnoreCase("stop")) {
            return "#stop";
        }

        // ── mine <target> ──────────────────────────────────────
        if (body.toLowerCase().startsWith("mine ")) {
            String target = body.substring(5).trim();
            if (target.isEmpty()) return null;
            return "#mine " + target;
        }

        // ── go to <x y z | player> ────────────────────────────
        if (body.toLowerCase().startsWith("go to ")) {
            String dest = body.substring(6).trim();
            if (dest.isEmpty()) return null;
            return "#goto " + dest;
        }

        // ── follow <player> ───────────────────────────────────
        if (body.toLowerCase().startsWith("follow ")) {
            String target = body.substring(7).trim();
            if (target.isEmpty()) return null;
            return "#follow player " + target;
        }

        // ── kill <player> — alias for follow ──────────────────
        // DO NOT use #attack; Baritone does not have that command.
        if (body.toLowerCase().startsWith("kill ")) {
            String target = body.substring(5).trim();
            if (target.isEmpty()) return null;
            return "#follow player " + target;
        }

        return null; // unrecognised sub-command
    }
}