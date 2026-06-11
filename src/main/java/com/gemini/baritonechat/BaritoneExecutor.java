package com.gemini.baritonechat;

import baritone.api.BaritoneAPI;

public class BaritoneExecutor {

    public static void run(String command) {
        try {
            BaritoneAPI.getProvider()
                    .getPrimaryBaritone()
                    .getCommandManager()
                    .execute(command);
        } catch (Exception e) {
            System.out.println("[BaritoneChat] Failed: " + command);
        }
    }
}