package ru.sp0ge.kiwy.mixin;

import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.sp0ge.kiwy.Network;
import java.util.function.BooleanSupplier;

@Mixin(ServerWorld.class)
public abstract class SurvivalWorldActivity {
 @Shadow public abstract void resetIdleTimeout();

 @Inject(method="tick",at=@At("HEAD"))
 private void kiwy$keepSurvivalActive(BooleanSupplier shouldKeepTicking,CallbackInfo ci){
  // 1.20.1 stops ticking entities after 300 idle world ticks without players/forced chunks.
  // Keep loaded Survival worlds active; normal chunk loading rules still apply.
  if(Network.INSTANCE!=null&&Network.INSTANCE.role.equals("survival"))resetIdleTimeout();
 }
}
