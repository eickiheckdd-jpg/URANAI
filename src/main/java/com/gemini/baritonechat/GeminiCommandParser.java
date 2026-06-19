package com.gemini.baritonechat;

import org.jetbrains.annotations.Nullable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Natural-language parser with:
 *  - alias mapping  (attack → kill, dig → mine, halt → stop, etc.)
 *  - typo tolerance (Levenshtein distance ≤ 2 on the verb)
 *  - block name normalisation (spaces → _, plural stripping)
 *  - tower: number + tower keyword can appear in ANY order/position
 */
public final class GeminiCommandParser {

    private static final String PREFIX = "hey gemini ";
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    private GeminiCommandParser() {}

    public enum CommandType { STOP, FOLLOW, KILL, MINE, GOTO, TOWER_UP, WALK }

    public static class GeminiCommand {
        public final CommandType type;
        public final String arg1;
        public final String arg2;
        public GeminiCommand(CommandType type, String arg1, String arg2) { this.type = type; this.arg1 = arg1; this.arg2 = arg2; }
        public GeminiCommand(CommandType type, String arg1) { this(type, arg1, null); }
        public GeminiCommand(CommandType type) { this(type, null, null); }
    }

    // ── Verb → canonical verb ─────────────────────────────────────────────────
    private static final String[][] VERB_ALIASES = {
        // STOP
        { "stop",    "stop"   }, { "halt",     "stop"   }, { "cancel",   "stop"   },
        { "quit",    "stop"   }, { "abort",    "stop"   }, { "pause",    "stop"   },
        { "freeze",  "stop"   }, { "end",      "stop"   },
        // FOLLOW
        { "follow",  "follow" }, { "fallow",   "follow" }, { "folow",    "follow" },
        { "track",   "follow" }, { "stalk",    "follow" }, { "chase",    "follow" },
        { "shadow",  "follow" },
        // KILL
        { "kill",    "kill"   }, { "attack",   "kill"   }, { "fight",    "kill"   },
        { "hit",     "kill"   }, { "murder",   "kill"   }, { "slay",     "kill"   },
        { "pvp",     "kill"   }, { "kil",      "kill"   }, { "kll",      "kill"   },
        { "atack",   "kill"   }, { "attck",    "kill"   },
        // MINE
        { "mine",    "mine"   }, { "dig",      "mine"   }, { "collect",  "mine"   },
        { "farm",    "mine"   }, { "gather",   "mine"   }, { "mne",      "mine"   },
        { "mien",    "mine"   }, { "grind",    "mine"   }, { "harvest",  "mine"   },
        // GOTO
        { "goto",    "goto"   }, { "go",       "goto"   }, { "travel",   "goto"   },
        { "move",    "goto"   }, { "head",     "goto"   }, { "navigate", "goto"   },
        { "teleport","goto"   }, { "tp",       "goto"   }, { "warp",     "goto"   },
        // TOWER — intentionally NOT including "up" or "walk" here to avoid
        // collisions; they are recognised only when a tower word is also present
        { "tower",   "tower"  }, { "build",    "tower"  }, { "climb",    "tower"  },
        { "pillar",  "tower"  }, { "towerup",  "tower"  },
        // WALK (short nudge — only triggered when NO tower word present)
        { "walk",    "walk"   }, { "nudge",    "walk"   }, { "step",     "walk"   },
        { "forward", "walk"   },
    };

    // Words that count as "tower intent" when scanning the whole sentence
    private static final String[] TOWER_WORDS = {
        "tower", "pillar", "climb", "build", "towerup", "towering"
    };

    // ── Parse ─────────────────────────────────────────────────────────────────
    @Nullable
    public static GeminiCommand parse(String rawMessage) {
        if (rawMessage == null) return null;

        // Strip colour codes
        String clean = rawMessage.replaceAll("§[0-9a-fk-or]", "").trim();

        // Strip "<PlayerName> " or "PlayerName: " chat prefix
        if (clean.startsWith("<")) {
            int close = clean.indexOf("> ");
            if (close != -1) clean = clean.substring(close + 2).trim();
        } else {
            int colon = clean.indexOf(": ");
            if (colon != -1 && colon < 32) clean = clean.substring(colon + 2).trim();
        }

        if (!clean.toLowerCase().startsWith(PREFIX)) return null;

        String body  = clean.substring(PREFIX.length()).trim();
        if (body.isEmpty()) return null;
        String lower = body.toLowerCase();

        // ── TOWER: scan every word for a tower keyword ────────────────────────
        // Handles any word order:
        //   "tower up 26 blocks"   "26 blocks tower up"
        //   "tower 26 blocks up"   "build me a pillar 30 high"
        //   "climb up 10"          "hey go up 15 blocks" etc.
        String[] words = lower.split("\\s+");

        // Does the sentence contain "up" AND a number AND at least one tower word?
        // OR just a tower word + number (without requiring "up")?
        boolean hasTowerKeyword = false;
        boolean hasUp = false;
        for (String w : words) {
            if (w.equals("up")) hasUp = true;
            for (String tw : TOWER_WORDS) {
                if (w.equals(tw) || levenshtein(w, tw) <= 1) {
                    hasTowerKeyword = true;
                }
            }
        }

        // Also catch: first verb resolves to "tower" via alias
        int spaceIdx = lower.indexOf(' ');
        String firstVerb = spaceIdx == -1 ? lower : lower.substring(0, spaceIdx);
        if ("tower".equals(resolveVerb(firstVerb))) hasTowerKeyword = true;

        // "go up 20" / "move up 10 blocks" — goto verb + "up" + number
        boolean isGoUp = false;
        if (hasUp && !hasTowerKeyword) {
            String fv = resolveVerb(firstVerb);
            if ("goto".equals(fv) || "walk".equals(fv)) isGoUp = true;
        }

        if (hasTowerKeyword || isGoUp) {
            Matcher m = NUMBER.matcher(lower);
            String amount = m.find() ? m.group() : "10";
            return new GeminiCommand(CommandType.TOWER_UP, amount);
        }

        // ── Normal verb-first parsing ─────────────────────────────────────────
        String rest    = spaceIdx == -1 ? ""    : body.substring(spaceIdx + 1).trim();
        String restLow = rest.toLowerCase();

        String canonical = resolveVerb(firstVerb);
        if (canonical == null) return null;

        // "go to <dest>"
        if (canonical.equals("goto") && restLow.startsWith("to ")) {
            rest    = rest.substring(3).trim();
            restLow = rest.toLowerCase();
        }

        return switch (canonical) {
            case "stop"   -> new GeminiCommand(CommandType.STOP);
            case "follow" -> {
                if (rest.isEmpty()) yield null;
                yield new GeminiCommand(CommandType.FOLLOW, rest);
            }
            case "kill" -> {
                if (rest.isEmpty()) yield null;
                yield new GeminiCommand(CommandType.KILL, rest);
            }
            case "mine" -> {
                if (rest.isEmpty()) yield null;
                yield new GeminiCommand(CommandType.MINE, normaliseBlock(rest));
            }
            case "goto" -> {
                if (rest.isEmpty()) yield null;
                yield new GeminiCommand(CommandType.GOTO, rest);
            }
            case "walk" -> new GeminiCommand(CommandType.WALK, rest.isEmpty() ? "1" : rest);
            default -> null;
        };
    }

    // ── Verb resolution ───────────────────────────────────────────────────────
    @Nullable
    private static String resolveVerb(String verb) {
        for (String[] pair : VERB_ALIASES) {
            if (pair[0].equals(verb)) return pair[1];
        }
        int bestDist = Integer.MAX_VALUE;
        String bestCanonical = null;
        for (String[] pair : VERB_ALIASES) {
            int d = levenshtein(verb, pair[0]);
            if (d < bestDist) { bestDist = d; bestCanonical = pair[1]; }
        }
        return (bestDist <= 2) ? bestCanonical : null;
    }

    // ── Block name normalisation ──────────────────────────────────────────────
    private static String normaliseBlock(String raw) {
        String s = raw.toLowerCase().replace(" ", "_").replace("-", "_");
        // Strip plural trailing-s: "logs"→"log", "diamonds"→"diamond"
        if (s.length() > 4 && s.endsWith("s") && !s.endsWith("ss")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    // ── Levenshtein distance ──────────────────────────────────────────────────
    private static int levenshtein(String a, String b) {
        int la = a.length(), lb = b.length();
        int[][] dp = new int[la + 1][lb + 1];
        for (int i = 0; i <= la; i++) dp[i][0] = i;
        for (int j = 0; j <= lb; j++) dp[0][j] = j;
        for (int i = 1; i <= la; i++) {
            for (int j = 1; j <= lb; j++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i-1][j]+1, dp[i][j-1]+1), dp[i-1][j-1]+cost);
            }
        }
        return dp[la][lb];
    }
}