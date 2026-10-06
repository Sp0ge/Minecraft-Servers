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
 @Inject(method="onPlayerMove",at=@At("HEAD"),cancellable=true)
 void move(PlayerMoveC2SPacket packet,CallbackInfo ci){if(Network.INSTANCE==null||Network.INSTANCE.server==null||!Network.INSTANCE.server.isOnThread())return;if(Network.INSTANCE!=null&&Network.INSTANCE.frozen(player)){player.networkHandler.requestTeleport(player.getX(),player.getY(),player.getZ(),player.getYaw(),player.getPitch());ci.cancel();}}
 @Inject(method="onClickSlot",at=@At("HEAD"),cancellable=true)
 void click(ClickSlotC2SPacket packet,CallbackInfo ci){if(Network.INSTANCE==null||Network.INSTANCE.server==null||!Network.INSTANCE.server.isOnThread())return;if(Network.INSTANCE!=null&&(Network.INSTANCE.frozen(player)||Network.INSTANCE.arena&&(packet.getActionType()==net.minecraft.screen.slot.SlotActionType.THROW||packet.getSlot()==-999))){player.currentScreenHandler.syncState();ci.cancel();}}
 @Inject(method="onPlayerAction",at=@At("HEAD"),cancellable=true)
 void action(PlayerActionC2SPacket packet,CallbackInfo ci){if(Network.INSTANCE==null||Network.INSTANCE.server==null||!Network.INSTANCE.server.isOnThread())return;if(Network.INSTANCE!=null&&(Network.INSTANCE.frozen(player)||Network.INSTANCE.arena&&(packet.getAction()==PlayerActionC2SPacket.Action.DROP_ITEM||packet.getAction()==PlayerActionC2SPacket.Action.DROP_ALL_ITEMS))){player.currentScreenHandler.syncState();ci.cancel();}}
}
