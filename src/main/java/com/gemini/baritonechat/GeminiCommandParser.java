package com.gemini.baritonechat;

import org.jetbrains.annotations.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GeminiCommandParser {

    private static final String PREFIX = "hey gemini ";
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    private GeminiCommandParser() {}

    public enum CommandType {
        STOP, FOLLOW, KILL, MINE, GOTO, TOWER_UP, WALK,
        COORDS, HEALTH, DROP_ALL, DROP_AMOUNT
    }

    public static class GeminiCommand {
        public final CommandType type;
        public final String arg1;
        public final String arg2;
        public final String arg3;
        public GeminiCommand(CommandType t, String a1, String a2, String a3) {
            type = t; arg1 = a1; arg2 = a2; arg3 = a3;
        }
        public GeminiCommand(CommandType t, String a1, String a2) { this(t, a1, a2, null); }
        public GeminiCommand(CommandType t, String a1) { this(t, a1, null, null); }
        public GeminiCommand(CommandType t) { this(t, null, null, null); }
    }

    private static final String[][] VERB_ALIASES = {
        { "stop",      "stop"    }, { "halt",     "stop"    }, { "cancel",   "stop"    },
        { "quit",      "stop"    }, { "abort",    "stop"    }, { "pause",    "stop"    },
        { "freeze",    "stop"    }, { "end",      "stop"    },
        { "follow",    "follow"  }, { "fallow",   "follow"  }, { "folow",    "follow"  },
        { "track",     "follow"  }, { "stalk",    "follow"  }, { "chase",    "follow"  },
        { "shadow",    "follow"  },
        { "kill",      "kill"    }, { "attack",   "kill"    }, { "fight",    "kill"    },
        { "hit",       "kill"    }, { "murder",   "kill"    }, { "slay",     "kill"    },
        { "pvp",       "kill"    }, { "kil",      "kill"    }, { "kll",      "kill"    },
        { "atack",     "kill"    }, { "attck",    "kill"    },
        { "mine",      "mine"    }, { "dig",      "mine"    }, { "collect",  "mine"    },
        { "farm",      "mine"    }, { "gather",   "mine"    }, { "mne",      "mine"    },
        { "mien",      "mine"    }, { "grind",    "mine"    }, { "harvest",  "mine"    },
        { "goto",      "goto"    }, { "go",       "goto"    }, { "travel",   "goto"    },
        { "move",      "goto"    }, { "head",     "goto"    }, { "navigate", "goto"    },
        { "teleport",  "goto"    }, { "tp",       "goto"    }, { "warp",     "goto"    },
        { "tower",     "tower"   }, { "build",    "tower"   }, { "climb",    "tower"   },
        { "pillar",    "tower"   }, { "towerup",  "tower"   },
        { "walk",      "walk"    }, { "nudge",    "walk"    }, { "step",     "walk"    },
        { "forward",   "walk"    },
        { "drop",      "drop"    }, { "give",     "drop"    }, { "throw",    "drop"    },
        { "toss",      "drop"    }, { "share",    "drop"    },
    };

    private static final String[] TOWER_WORDS = {
        "tower", "pillar", "climb", "build", "towerup", "towering"
    };

    // Common name → registry ID (or space-separated IDs for multi-match)
    private static final Map<String, String> BLOCK_ALIASES = new HashMap<>();
    static {
        BLOCK_ALIASES.put("grass",          "grass_block");
        BLOCK_ALIASES.put("path",           "dirt_path");
        BLOCK_ALIASES.put("dirt path",      "dirt_path");
        BLOCK_ALIASES.put("log",            "oak_log birch_log spruce_log jungle_log acacia_log dark_oak_log mangrove_log cherry_log");
        BLOCK_ALIASES.put("wood",           "oak_log birch_log spruce_log jungle_log acacia_log dark_oak_log mangrove_log cherry_log");
        BLOCK_ALIASES.put("oak",            "oak_log");
        BLOCK_ALIASES.put("birch",          "birch_log");
        BLOCK_ALIASES.put("spruce",         "spruce_log");
        BLOCK_ALIASES.put("jungle",         "jungle_log");
        BLOCK_ALIASES.put("acacia",         "acacia_log");
        BLOCK_ALIASES.put("dark oak",       "dark_oak_log");
        BLOCK_ALIASES.put("mangrove",       "mangrove_log");
        BLOCK_ALIASES.put("cherry",         "cherry_log");
        BLOCK_ALIASES.put("crimson",        "crimson_stem");
        BLOCK_ALIASES.put("warped",         "warped_stem");
        BLOCK_ALIASES.put("bamboo",         "bamboo_block");
        BLOCK_ALIASES.put("planks",         "oak_planks birch_planks spruce_planks jungle_planks acacia_planks dark_oak_planks mangrove_planks cherry_planks crimson_planks warped_planks");
        BLOCK_ALIASES.put("diamond",        "diamond_ore deepslate_diamond_ore");
        BLOCK_ALIASES.put("iron",           "iron_ore deepslate_iron_ore");
        BLOCK_ALIASES.put("gold",           "gold_ore deepslate_gold_ore nether_gold_ore");
        BLOCK_ALIASES.put("coal",           "coal_ore deepslate_coal_ore");
        BLOCK_ALIASES.put("copper",         "copper_ore deepslate_copper_ore");
        BLOCK_ALIASES.put("emerald",        "emerald_ore deepslate_emerald_ore");
        BLOCK_ALIASES.put("lapis",          "lapis_ore deepslate_lapis_ore");
        BLOCK_ALIASES.put("redstone",       "redstone_ore deepslate_redstone_ore");
        BLOCK_ALIASES.put("netherite",      "ancient_debris");
        BLOCK_ALIASES.put("debris",         "ancient_debris");
        BLOCK_ALIASES.put("quartz",         "nether_quartz_ore");
        BLOCK_ALIASES.put("amethyst",       "amethyst_cluster budding_amethyst");
        BLOCK_ALIASES.put("stone",          "stone");
        BLOCK_ALIASES.put("cobble",         "cobblestone");
        BLOCK_ALIASES.put("cobblestone",    "cobblestone");
        BLOCK_ALIASES.put("deepslate",      "deepslate");
        BLOCK_ALIASES.put("gravel",         "gravel");
        BLOCK_ALIASES.put("sand",           "sand");
        BLOCK_ALIASES.put("dirt",           "dirt");
        BLOCK_ALIASES.put("clay",           "clay");
        BLOCK_ALIASES.put("obsidian",       "obsidian");
        BLOCK_ALIASES.put("glowstone",      "glowstone");
        BLOCK_ALIASES.put("netherrack",     "netherrack");
        BLOCK_ALIASES.put("soul sand",      "soul_sand");
        BLOCK_ALIASES.put("magma",          "magma_block");
        BLOCK_ALIASES.put("basalt",         "basalt");
        BLOCK_ALIASES.put("endstone",       "end_stone");
        BLOCK_ALIASES.put("end stone",      "end_stone");
        BLOCK_ALIASES.put("purpur",         "purpur_block");
        BLOCK_ALIASES.put("bookshelf",      "bookshelf");
        BLOCK_ALIASES.put("tnt",            "tnt");
        BLOCK_ALIASES.put("sponge",         "sponge");
        BLOCK_ALIASES.put("ice",            "ice packed_ice blue_ice");
        BLOCK_ALIASES.put("packed ice",     "packed_ice");
        BLOCK_ALIASES.put("blue ice",       "blue_ice");
        BLOCK_ALIASES.put("leaves",         "oak_leaves birch_leaves spruce_leaves jungle_leaves acacia_leaves dark_oak_leaves mangrove_leaves cherry_leaves azalea_leaves");
        BLOCK_ALIASES.put("ore",            "diamond_ore deepslate_diamond_ore iron_ore deepslate_iron_ore gold_ore deepslate_gold_ore coal_ore deepslate_coal_ore copper_ore deepslate_copper_ore emerald_ore deepslate_emerald_ore lapis_ore deepslate_lapis_ore redstone_ore deepslate_redstone_ore nether_gold_ore nether_quartz_ore ancient_debris");
    }

    @Nullable
    public static String resolveBlock(String name) {
        String lower = name.toLowerCase().replace("-", " ").trim();
        if (BLOCK_ALIASES.containsKey(lower)) return BLOCK_ALIASES.get(lower);
        String underscored = lower.replace(" ", "_");
        if (BLOCK_ALIASES.containsKey(underscored)) return BLOCK_ALIASES.get(underscored);
        return null;
    }

    @Nullable
    public static GeminiCommand parse(String rawMessage) {
        return parse(rawMessage, null);
    }

    @Nullable
    public static GeminiCommand parse(String rawMessage, @Nullable String senderName) {
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

        // Health — keyword scan anywhere in sentence
        if (containsAny(lower, "health", "hp", "hearts", "hunger", "hungry", "hurt", "dying",
                "how much health", "what's your health", "ur health", "your health")) {
            return new GeminiCommand(CommandType.HEALTH);
        }

        // Coords — keyword scan anywhere in sentence
        if (containsAny(lower, "coords", "coordinates", "location", "position", "where are you",
                "where r u", "where r you", "ur coords", "your coords", "pos", "where am i")) {
            return new GeminiCommand(CommandType.COORDS);
        }

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
            rest    = rest.substring(3).trim();
            restLow = rest.toLowerCase();
        }

        return switch (canonical) {
            case "stop"   -> new GeminiCommand(CommandType.STOP);
            case "follow" -> rest.isEmpty() ? null : new GeminiCommand(CommandType.FOLLOW, rest);
            case "kill"   -> rest.isEmpty() ? null : new GeminiCommand(CommandType.KILL, rest);
            case "goto"   -> rest.isEmpty() ? null : new GeminiCommand(CommandType.GOTO, rest);
            case "walk"   -> new GeminiCommand(CommandType.WALK, rest.isEmpty() ? "1" : rest);

            case "drop" -> {
                // "drop all [to <player>]"
                if (restLow.startsWith("all")) {
                    String target = extractTo(rest);
                    if (target == null) target = senderName; // auto-detect sender
                    yield new GeminiCommand(CommandType.DROP_ALL, target);
                }
                // "drop <number> <item> [to <player>]"
                Matcher m = NUMBER.matcher(restLow);
                if (m.find()) {
                    String count    = m.group();
                    String after    = rest.substring(m.end()).trim();
                    String target   = extractTo(after);
                    String itemPart = target != null
                        ? after.substring(0, after.toLowerCase().lastIndexOf(" to ")).trim()
                        : after.trim();
                    if (target == null) target = senderName;
                    yield new GeminiCommand(CommandType.DROP_AMOUNT, count, itemPart, target);
                }
                yield null;
            }

            case "mine" -> {
                if (rest.isEmpty()) yield null;
                String[] parts = rest.split("\\s+");
                if (parts.length >= 2) {
                    String last = parts[parts.length - 1];
                    try {
                        int count        = Integer.parseInt(last);
                        String blockRaw  = rest.substring(0, rest.lastIndexOf(last)).trim();
                        yield new GeminiCommand(CommandType.MINE, normaliseBlock(blockRaw), String.valueOf(count));
                    } catch (NumberFormatException ignored) {}
                }
                yield new GeminiCommand(CommandType.MINE, normaliseBlock(rest));
            }

            default -> null;
        };
    }

    @Nullable
    private static String extractTo(String text) {
        int idx = text.toLowerCase().lastIndexOf(" to ");
        if (idx == -1) return null;
        String target = text.substring(idx + 4).trim();
        return target.isEmpty() ? null : target;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
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

    public static String normaliseBlock(String raw) {
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