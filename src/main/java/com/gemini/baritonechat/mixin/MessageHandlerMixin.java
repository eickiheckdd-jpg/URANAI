package com.gemini.baritonechat.mixin;

import com.gemini.baritonechat.ChatParser;
import net.minecraft.client.network.message.MessageHandler;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.network.message.MessageType;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MessageHandler.class)
public class MessageHandlerMixin {

    @Inject(method = "onChatMessage", at = @At("HEAD"))
    private void onChat(SignedMessage message, Object sender, MessageType.Parameters params, CallbackInfo ci) {
        ChatParser.handle(message.getSignedContent());
    }

    @Inject(method = "onGameMessage", at = @At("HEAD"))
    private void onGame(Text message, boolean overlay, CallbackInfo ci) {
        ChatParser.handle(message.getString());
    }
}