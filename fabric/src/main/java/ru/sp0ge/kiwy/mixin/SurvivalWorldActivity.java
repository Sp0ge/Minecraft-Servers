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



import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.sp0ge.kiwy.Network;
import java.util.function.BooleanSupplier;

@Mixin(ServerLevel.class)
public abstract class SurvivalWorldActivity {
 @Shadow public abstract void resetEmptyTime();

 @Inject(method="tick",at=@At("HEAD"))
 private void kiwy$keepSurvivalActive(BooleanSupplier shouldKeepTicking,CallbackInfo ci){
  // Keep the per-level empty timer reset while the Survival process is running.
  // Keep loaded Survival worlds active; normal chunk loading rules still apply.
  if(Network.INSTANCE!=null&&Network.INSTANCE.role.equals("survival"))resetEmptyTime();
 }
}
