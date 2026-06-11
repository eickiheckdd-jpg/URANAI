package com.gemini.baritonechat;

public class ChatParser {

    public static void handle(String msg) {
        if (msg == null) return;

        String lower = msg.toLowerCase().trim();

        if (!lower.startsWith("hey gemini")) return;

        String content = lower.replaceFirst("hey gemini", "").trim();

        if (content.startsWith("mine ")) {
            CommandExecutor.run("#mine " + content.replace("mine ", "").trim());
            return;
        }

        if (content.startsWith("follow ")) {
            CommandExecutor.run("#follow player " + content.replace("follow ", "").trim());
            return;
        }

        if (content.startsWith("go to ")) {
            CommandExecutor.run("#goto " + content.replace("go to ", "").trim());
            return;
        }

        if (content.startsWith("kill ")) {
            CommandExecutor.run("#follow player " + content.replace("kill ", "").trim());
            return;
        }

        if (content.startsWith("stop")) {
            CommandExecutor.run("#stop");
        }
    }
}