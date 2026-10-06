package ru.sp0ge.kiwy.mixin;
import ru.sp0ge.kiwy.Network;
import net.minecraft.world.explosion.Explosion;
import net.minecraft.util.math.BlockPos;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(Explosion.class)
public class ExplosionGuard {
 @Shadow @Final private ObjectArrayList<BlockPos> affectedBlocks;
 @Inject(method="collectBlocksAndDamageEntities",at=@At("TAIL"))
 void blocks(CallbackInfo ci){if(Network.INSTANCE!=null&&Network.INSTANCE.arena)affectedBlocks.clear();}
}
