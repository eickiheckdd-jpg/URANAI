package com.gemini.baritonechat;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public final class GeminiConfig {

    private static volatile boolean enabled = true;

    private GeminiConfig() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static Screen createScreen(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Text.literal("Gemini Baritone"))
            .setSavingRunnable(() -> {});

        ConfigCategory general = builder.getOrCreateCategory(Text.literal("General"));
        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        general.addEntry(entryBuilder.startBooleanToggle(Text.literal("Enabled"), enabled)
            .setDefaultValue(true)
            .setTooltip(Text.literal("Enable or disable Gemini chat command listening"))
            .setSaveConsumer(newValue -> enabled = newValue)
            .build());

        return builder.build();
    }
}
