package com.gemini.baritonechat;

import net.fabricmc.api.ClientModInitializer;

public class BaritoneChatClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ChatListener.register();
    }
}