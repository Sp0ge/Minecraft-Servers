package ru.sp0ge.kiwy.mixin;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.resources.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.item.*;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.protocol.game.*;
import net.minecraft.nbt.*;

import ru.sp0ge.kiwy.Network;


import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(Commands.class)
public class CommandGuard {
 @Inject(method="performCommand",at=@At("HEAD"),cancellable=true)
 void command(com.mojang.brigadier.ParseResults<CommandSourceStack> parse,String command,CallbackInfo ci){CommandSourceStack source=parse.getContext().getSource();if(source.getEntity() instanceof ServerPlayer p&&Network.INSTANCE!=null&&!Network.INSTANCE.admin(source)){String root=command.replaceFirst("^/","").split("\\s+",2)[0].toLowerCase(java.util.Locale.ROOT);if((!Network.INSTANCE.commands().contains(root)||!Network.INSTANCE.permitted(p,root))){Network.say(p,"Команда недоступна. /help — справка.");ci.cancel();}}}
}
