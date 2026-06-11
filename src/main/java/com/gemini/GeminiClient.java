package com.gemini;

import net.fabricmc.api.ClientModInitializer;

public class GeminiClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        System.out.println("Gemini loaded");
    }
}