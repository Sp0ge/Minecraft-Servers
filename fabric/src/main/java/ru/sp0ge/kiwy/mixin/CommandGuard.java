package ru.sp0ge.kiwy.mixin;
import ru.sp0ge.kiwy.Network;
import net.minecraft.server.command.*;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(CommandManager.class)
public class CommandGuard {
 @Inject(method="execute",at=@At("HEAD"),cancellable=true)
 void command(com.mojang.brigadier.ParseResults<ServerCommandSource> parse,String command,CallbackInfoReturnable<Integer> ci){ServerCommandSource source=parse.getContext().getSource();if(source.getEntity() instanceof ServerPlayerEntity p&&Network.INSTANCE!=null&&!source.hasPermissionLevel(2)){String root=command.replaceFirst("^/","").split("\\s+",2)[0].toLowerCase(java.util.Locale.ROOT);if(!Network.INSTANCE.commands().contains(root)){Network.say(p,"Команда недоступна. /help — справка.");ci.setReturnValue(0);}}}
}
