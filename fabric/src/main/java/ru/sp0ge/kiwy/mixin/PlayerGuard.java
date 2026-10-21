package ru.sp0ge.kiwy.mixin;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.util.Prediction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.sp0ge.kiwy.Network;
@Mixin(ServerPlayer.class)
public class PlayerGuard {
 @Inject(method="drop(Lnet/minecraft/world/item/ItemStack;ZLnet/minecraft/util/Prediction;)Lnet/minecraft/world/entity/item/ItemEntity;",at=@At("HEAD"),cancellable=true)
 private void kiwy$drop(ItemStack stack,boolean random,Prediction prediction,CallbackInfoReturnable<ItemEntity> ci){
  Network n=Network.INSTANCE;if(n!=null&&(n.arena||n.frozen((ServerPlayer)(Object)this)))ci.setReturnValue(null);
 }
}
