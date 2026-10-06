package ru.sp0ge.kiwy.mixin;
import ru.sp0ge.kiwy.Network;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(PlayerEntity.class)
public class PlayerGuard {
 @Inject(method="damage",at=@At("HEAD"),cancellable=true)
 void damage(DamageSource source,float amount,CallbackInfoReturnable<Boolean> ci){if((Object)this instanceof ServerPlayerEntity p&&Network.INSTANCE!=null){Network n=Network.INSTANCE;if(n.frozen(p)||n.arena&&source.getAttacker() instanceof ServerPlayerEntity other&&!n.duels.sameMatch(p,other))ci.setReturnValue(false);}}
 @Inject(method="dropItem(Lnet/minecraft/item/ItemStack;ZZ)Lnet/minecraft/entity/ItemEntity;",at=@At("HEAD"),cancellable=true)
 void drop(ItemStack stack,boolean throwRandomly,boolean retainOwnership,CallbackInfoReturnable<ItemEntity> ci){if((Object)this instanceof ServerPlayerEntity p&&Network.INSTANCE!=null&&(Network.INSTANCE.arena||Network.INSTANCE.frozen(p)))ci.setReturnValue(null);}
}
