package ru.sp0ge.kiwy.mixin;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.core.BlockPos;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.sp0ge.kiwy.Network;
@Mixin(ServerExplosion.class)
public class ExplosionGuard {
 @Inject(method="calculateExplodedPositions",at=@At("HEAD"),cancellable=true)
 private void kiwy$blocks(CallbackInfoReturnable<List<BlockPos>> ci){if(Network.INSTANCE!=null&&Network.INSTANCE.arena)ci.setReturnValue(List.of());}
}
