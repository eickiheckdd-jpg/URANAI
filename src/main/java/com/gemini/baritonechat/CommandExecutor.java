package com.gemini.baritonechat;

import net.minecraft.client.MinecraftClient;

public class CommandExecutor {

    public static void run(String command) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client.player != null) {
            client.player.networkHandler.sendChatCommand(command);
        }
    }
}