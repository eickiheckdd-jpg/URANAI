package com.gemini.baritonechat;

import net.minecraft.client.MinecraftClient;

public class BaritoneExecutor {

    public static void run(String command) {

        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc.player == null) return;

        // Send directly to chat so Baritone-Meteor can read it
        mc.player.networkHandler.sendChatMessage(command);
    }
}