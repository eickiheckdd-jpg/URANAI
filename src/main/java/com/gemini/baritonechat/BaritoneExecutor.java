package com.gemini.baritonechat;

import baritone.api.BaritoneAPI;

public class BaritoneExecutor {

    public static void run(String command) {
        try {
            BaritoneAPI.getProvider()
                    .getCommandSystem()
                    .execute(command.replaceFirst("^#", ""));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}