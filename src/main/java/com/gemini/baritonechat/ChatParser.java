package com.gemini.baritonechat;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

public class ChatParser {

    public static void init() {

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {

            String content = message.getString().toLowerCase();

            if (content.startsWith("mine ")) {
                CommandExecutor.run("#mine " + content.replace("mine ", "").trim());
            }

            if (content.startsWith("follow ")) {
                CommandExecutor.run("#follow " + content.replace("follow ", "").trim());
            }

            if (content.startsWith("go to ")) {
                CommandExecutor.run("#goto " + content.replace("go to ", "").trim());
            }

            if (content.startsWith("kill ")) {
                CommandExecutor.run("#attack " + content.replace("kill ", "").trim());
            }

            if (content.equals("stop")) {
                CommandExecutor.run("#stop");
            }
        });
    }
}