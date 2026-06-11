package com.gemini;

import java.util.Locale;

public class GeminiParser {

    public static void parse(String msg) {

        String original = msg.trim();
        String lower = original.toLowerCase(Locale.ROOT);

        if (!lower.startsWith("hey gemini")) {
            return;
        }

        String args = original.substring(10).trim();

        if (args.toLowerCase().startsWith("mine ")) {
            String block = args.substring(5).trim();
            BaritoneExecutor.execute("mine " + block);
        }

        else if (args.toLowerCase().startsWith("follow ")) {
            String player = args.substring(7).trim();
            BaritoneExecutor.execute("follow player " + player);
        }

        else if (args.toLowerCase().startsWith("kill ")) {
            String player = args.substring(5).trim();
            BaritoneExecutor.execute("follow player " + player);
        }

        else if (args.toLowerCase().startsWith("go to ")) {
            String coords = args.substring(6).trim();
            BaritoneExecutor.execute("goto " + coords);
        }
    }
}