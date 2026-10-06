package ru.sp0ge.kiwy.mixin;
import ru.sp0ge.kiwy.Network;
import net.minecraft.server.network.*;
import net.minecraft.network.packet.c2s.play.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(ServerPlayNetworkHandler.class)
public class PacketGuard {
 @Shadow public ServerPlayerEntity player;
 @Unique private long kiwy$lastFreezeCorrection;
 @Inject(method="onPlayerMove",at=@At("HEAD"),cancellable=true)
 void move(PlayerMoveC2SPacket packet,CallbackInfo ci){
  if(Network.INSTANCE==null||Network.INSTANCE.server==null||!Network.INSTANCE.server.isOnThread())return;
  if(!Network.INSTANCE.frozen(player)){kiwy$lastFreezeCorrection=0;return;}
  ci.cancel();
  // A teleport causes the client to confirm and report its position again.
  // Do not answer that unchanged position with another teleport.
  if(!packet.changesPosition())return;
  double dx=packet.getX(player.getX())-player.getX(),dy=packet.getY(player.getY())-player.getY(),dz=packet.getZ(player.getZ())-player.getZ();
  if(dx*dx+dy*dy+dz*dz<=0.000001)return;
  long now=System.nanoTime();
  if(kiwy$lastFreezeCorrection!=0&&now-kiwy$lastFreezeCorrection<250000000L)return;
  kiwy$lastFreezeCorrection=now;
  player.networkHandler.requestTeleport(player.getX(),player.getY(),player.getZ(),player.getYaw(),player.getPitch());
 }
 @Inject(method="onClickSlot",at=@At("HEAD"),cancellable=true)
 void click(ClickSlotC2SPacket packet,CallbackInfo ci){if(Network.INSTANCE==null||Network.INSTANCE.server==null||!Network.INSTANCE.server.isOnThread())return;if(Network.INSTANCE!=null&&(Network.INSTANCE.frozen(player)||Network.INSTANCE.arena&&(packet.getActionType()==net.minecraft.screen.slot.SlotActionType.THROW||packet.getSlot()==-999))){player.currentScreenHandler.syncState();ci.cancel();}}
 @Inject(method="onPlayerAction",at=@At("HEAD"),cancellable=true)
 void action(PlayerActionC2SPacket packet,CallbackInfo ci){if(Network.INSTANCE==null||Network.INSTANCE.server==null||!Network.INSTANCE.server.isOnThread())return;if(Network.INSTANCE!=null&&(Network.INSTANCE.frozen(player)||Network.INSTANCE.arena&&(packet.getAction()==PlayerActionC2SPacket.Action.DROP_ITEM||packet.getAction()==PlayerActionC2SPacket.Action.DROP_ALL_ITEMS))){player.currentScreenHandler.syncState();ci.cancel();}}
}
