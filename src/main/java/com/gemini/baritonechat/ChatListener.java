package com.gemini.baritonechat;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

public class ChatListener {

    public static void register() {

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {

            String raw = message.getString().trim();
            String msg = raw.toLowerCase();

            if (!msg.startsWith("hey gemini")) return;

            if (msg.startsWith("hey gemini mine ")) {
                String block = raw.substring(16);
                BaritoneExecutor.run("#mine " + block);
            }

            else if (msg.startsWith("hey gemini follow ")) {
                String player = raw.substring(18);
                BaritoneExecutor.run("#follow player " + player);
            }

            else if (msg.startsWith("hey gemini kill ")) {
                String player = raw.substring(16);
                BaritoneExecutor.run("#follow player " + player);
            }

            else if (msg.startsWith("hey gemini go to ")) {
                String coords = raw.substring(17);
                BaritoneExecutor.run("#goto " + coords);
            }

            else if (msg.startsWith("hey gemini stop")) {
                BaritoneExecutor.run("#stop");
            }
        });
    }
}