package com.gemini.baritonechat;

import net.minecraft.client.MinecraftClient;

public class BaritoneExecutor {

    public static void run(String command) {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc.player != null) {
            mc.player.networkHandler.sendChatCommand(command);
        }
    }
}