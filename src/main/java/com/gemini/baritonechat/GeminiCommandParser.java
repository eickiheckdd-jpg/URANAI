package com.gemini.baritonechat;

import org.jetbrains.annotations.Nullable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final String[][] VERB_ALIASES = {
        { "stop",      "stop"   }, { "halt",     "stop"   }, { "cancel",   "stop"   },
        { "quit",      "stop"   }, { "abort",    "stop"   }, { "pause",    "stop"   },
        { "freeze",    "stop"   }, { "end",      "stop"   },
        { "follow",    "follow" }, { "fallow",   "follow" }, { "folow",    "follow" },
        { "track",     "follow" }, { "stalk",    "follow" }, { "chase",    "follow" },
        { "shadow",    "follow" },
        { "kill",      "kill"   }, { "attack",   "kill"   }, { "fight",    "kill"   },
        { "hit",       "kill"   }, { "murder",   "kill"   }, { "slay",     "kill"   },
        { "pvp",       "kill"   }, { "kil",      "kill"   }, { "kll",      "kill"   },
        { "atack",     "kill"   }, { "attck",    "kill"   },
        { "mine",      "mine"   }, { "dig",      "mine"   }, { "collect",  "mine"   },
        { "farm",      "mine"   }, { "gather",   "mine"   }, { "mne",      "mine"   },
        { "mien",      "mine"   }, { "grind",    "mine"   }, { "harvest",  "mine"   },
        { "goto",      "goto"   }, { "go",       "goto"   }, { "travel",   "goto"   },
        { "move",      "goto"   }, { "head",     "goto"   }, { "navigate", "goto"   },
        { "teleport",  "goto"   }, { "tp",       "goto"   }, { "warp",     "goto"   },
        { "tower",     "tower"  }, { "build",    "tower"  }, { "climb",    "tower"  },
        { "pillar",    "tower"  }, { "towerup",  "tower"  },
        { "walk",      "walk"   }, { "nudge",    "walk"   }, { "step",     "walk"   },
        { "forward",   "walk"   },
    };

    private static final String[] TOWER_WORDS = {
        "tower", "pillar", "climb", "build", "towerup", "towering"
    };

    @Nullable
    public static GeminiCommand parse(String rawMessage) {
        if (rawMessage == null) return null;

        String clean = rawMessage.replaceAll("§[0-9a-fk-or]", "").trim();

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

        String[] words   = lower.split("\\s+");
        int spaceIdx     = lower.indexOf(' ');
        String firstVerb = spaceIdx == -1 ? lower : lower.substring(0, spaceIdx);

        boolean hasTowerKeyword = false;
        boolean hasUp = false;
        for (String w : words) {
            if (w.equals("up")) hasUp = true;
            for (String tw : TOWER_WORDS) {
                if (w.equals(tw) || levenshtein(w, tw) <= 1) hasTowerKeyword = true;
            }
        }
        if ("tower".equals(resolveVerb(firstVerb))) hasTowerKeyword = true;

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

        String rest    = spaceIdx == -1 ? "" : body.substring(spaceIdx + 1).trim();
        String restLow = rest.toLowerCase();

        String canonical = resolveVerb(firstVerb);
        if (canonical == null) return null;

        if (canonical.equals("goto") && restLow.startsWith("to ")) {
            rest = rest.substring(3).trim();
        }

        return switch (canonical) {
            case "stop"   -> new GeminiCommand(CommandType.STOP);
            case "follow" -> rest.isEmpty() ? null : new GeminiCommand(CommandType.FOLLOW, rest);
            case "kill"   -> rest.isEmpty() ? null : new GeminiCommand(CommandType.KILL, rest);
            case "goto"   -> rest.isEmpty() ? null : new GeminiCommand(CommandType.GOTO, rest);
            case "walk"   -> new GeminiCommand(CommandType.WALK, rest.isEmpty() ? "1" : rest);
            case "mine"   -> {
                if (rest.isEmpty()) yield null;
                String[] parts = rest.split("\\s+");
                if (parts.length >= 2) {
                    String last = parts[parts.length - 1];
                    try {
                        int count = Integer.parseInt(last);
                        String blockName = rest.substring(0, rest.lastIndexOf(last)).trim();
                        yield new GeminiCommand(CommandType.MINE, normaliseBlock(blockName), String.valueOf(count));
                    } catch (NumberFormatException e) {
                        yield new GeminiCommand(CommandType.MINE, normaliseBlock(rest));
                    }
                }
                yield new GeminiCommand(CommandType.MINE, normaliseBlock(rest));
            }
            default -> null;
        };
    }

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

    private static String normaliseBlock(String raw) {
        String s = raw.toLowerCase().replace(" ", "_").replace("-", "_");
        if (s.length() > 4 && s.endsWith("s") && !s.endsWith("ss")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

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