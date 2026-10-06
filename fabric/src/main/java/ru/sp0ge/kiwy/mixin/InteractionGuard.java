package ru.sp0ge.kiwy.mixin;
import ru.sp0ge.kiwy.Network;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.*;
import net.minecraft.util.math.*;
import net.minecraft.world.World;
import net.minecraft.item.*;
import net.minecraft.util.hit.BlockHitResult;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(ServerPlayerInteractionManager.class)
public class InteractionGuard {
 @Shadow @Final protected ServerPlayerEntity player;
 @Inject(method="tryBreakBlock",at=@At("HEAD"),cancellable=true)
 void block(BlockPos pos,CallbackInfoReturnable<Boolean> ci){if(Network.INSTANCE!=null&&(Network.INSTANCE.arena||Network.INSTANCE.frozen(player)))ci.setReturnValue(false);}
 @Inject(method="interactBlock",at=@At("HEAD"),cancellable=true)
 void useBlock(ServerPlayerEntity p,World world,ItemStack stack,Hand hand,BlockHitResult hit,CallbackInfoReturnable<ActionResult> ci){if(Network.INSTANCE!=null&&(Network.INSTANCE.arena||Network.INSTANCE.frozen(p)))ci.setReturnValue(ActionResult.FAIL);}
 @Inject(method="interactItem",at=@At("HEAD"),cancellable=true)
 void useItem(ServerPlayerEntity p,World world,ItemStack stack,Hand hand,CallbackInfoReturnable<ActionResult> ci){if(Network.INSTANCE!=null&&(Network.INSTANCE.frozen(p)||Network.INSTANCE.arena&&(stack.getItem() instanceof BucketItem||stack.getItem() instanceof BlockItem||stack.getItem() instanceof SpawnEggItem||stack.isOf(Items.FLINT_AND_STEEL)||stack.isOf(Items.FIRE_CHARGE))))ci.setReturnValue(ActionResult.FAIL);}
}
