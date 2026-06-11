package com.gemini.baritonechat.mixin;

import com.gemini.baritonechat.ChatParser;
import com.gemini.baritonechat.BaritoneExecutor;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatHud.class)
public class ChatMixin {

    @Inject(method = "addMessage", at = @At("HEAD"))
    private void onChat(Text message, CallbackInfo ci) {

        String cmd = ChatParser.parse(message.getString());

        if (cmd != null) {
            BaritoneExecutor.run(cmd);
        }
    }
}