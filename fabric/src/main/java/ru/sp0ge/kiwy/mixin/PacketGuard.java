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


import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(ServerGamePacketListenerImpl.class)
public class PacketGuard {
 @Shadow public ServerPlayer player;
 @Unique private long kiwy$lastFreezeCorrection;
 @Inject(method="handleMovePlayer",at=@At("HEAD"),cancellable=true)
 void move(ServerboundMovePlayerPacket packet,CallbackInfo ci){
  if(Network.INSTANCE==null||Network.INSTANCE.server==null||!Network.INSTANCE.server.isSameThread())return;
  if(!Network.INSTANCE.frozen(player)){kiwy$lastFreezeCorrection=0;return;}
  ci.cancel();
  // A teleport causes the client to confirm and report its position again.
  // Do not answer that unchanged position with another teleport.
  if(!packet.hasPosition())return;
  double dx=packet.getX(player.getX())-player.getX(),dy=packet.getY(player.getY())-player.getY(),dz=packet.getZ(player.getZ())-player.getZ();
  if(dx*dx+dy*dy+dz*dz<=0.000001)return;
  long now=System.nanoTime();
  if(kiwy$lastFreezeCorrection!=0&&now-kiwy$lastFreezeCorrection<250000000L)return;
  kiwy$lastFreezeCorrection=now;
  player.connection.teleport(player.getX(),player.getY(),player.getZ(),player.getYRot(),player.getXRot());
 }
 @Inject(method="handleContainerClick",at=@At("HEAD"),cancellable=true)
 void click(ServerboundContainerClickPacket packet,CallbackInfo ci){if(Network.INSTANCE==null||Network.INSTANCE.server==null||!Network.INSTANCE.server.isSameThread())return;if(Network.INSTANCE!=null&&(Network.INSTANCE.frozen(player)||Network.INSTANCE.arena&&(packet.containerInput()==net.minecraft.world.inventory.ContainerInput.THROW||packet.slotNum()==-999))){player.containerMenu.sendAllDataToRemote();ci.cancel();}}
 @Inject(method="handlePlayerAction",at=@At("HEAD"),cancellable=true)
 void action(ServerboundPlayerActionPacket packet,CallbackInfo ci){if(Network.INSTANCE==null||Network.INSTANCE.server==null||!Network.INSTANCE.server.isSameThread())return;if(Network.INSTANCE!=null&&(Network.INSTANCE.frozen(player)||Network.INSTANCE.arena&&(packet.getAction()==ServerboundPlayerActionPacket.Action.DROP_ITEM||packet.getAction()==ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS))){player.containerMenu.sendAllDataToRemote();ci.cancel();}}
}
