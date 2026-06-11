package com.gemini.baritonechat;

import java.util.Locale;

public class ChatParser {

    public static String parse(String msg) {

        String original = msg.trim();
        String lower = original.toLowerCase(Locale.ROOT);

        if (!lower.startsWith("hey gemini")) return null;

        String args = original.substring(10).trim();
        String argsLower = args.toLowerCase(Locale.ROOT);

        if (argsLower.startsWith("mine ")) {
            return "#mine " + args.substring(5).trim();
        }

        if (argsLower.startsWith("follow ")) {
            return "#follow player " + args.substring(7).trim();
        }

        if (argsLower.startsWith("kill ")) {
            return "#follow player " + args.substring(5).trim();
        }

        if (argsLower.startsWith("go to ")) {
            return "#goto " + args.substring(6).trim();
        }

        return null;
    }
}