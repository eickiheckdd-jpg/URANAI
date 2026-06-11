package com.gemini.baritonechat;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

public class ChatListener {

    public static void init() {

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String text = message.getString();
            String cmd = ChatParser.parse(text);

            if (cmd != null) {
                BaritoneExecutor.run(cmd);
            }
        });
    }
}