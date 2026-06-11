package com.gemini;

import baritone.api.BaritoneAPI;

public class BaritoneExecutor {

    public static void execute(String command) {

        try {

            BaritoneAPI
                    .getProvider()
                    .getCommandSystem()
                    .execute(command);

            System.out.println("[Gemini] Executed: " + command);

        } catch (Throwable t) {

            t.printStackTrace();

        }
    }
}