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
@Mixin(ServerPlayerGameMode.class)
public class InteractionGuard {
 @Shadow @Final protected ServerPlayer player;
 @Inject(method="destroyBlock",at=@At("HEAD"),cancellable=true)
 void block(BlockPos pos,CallbackInfoReturnable<Boolean> ci){if(Network.INSTANCE!=null&&(Network.INSTANCE.arena||Network.INSTANCE.frozen(player)))ci.setReturnValue(false);}
 @Inject(method="useItemOn",at=@At("HEAD"),cancellable=true)
 void useBlock(ServerPlayer p,Level world,ItemStack stack,InteractionHand hand,BlockHitResult hit,CallbackInfoReturnable<InteractionResult> ci){if(Network.INSTANCE!=null&&(Network.INSTANCE.arena||Network.INSTANCE.frozen(p)))ci.setReturnValue(InteractionResult.FAIL);}
 @Inject(method="useItem",at=@At("HEAD"),cancellable=true)
 void useItem(ServerPlayer p,Level world,ItemStack stack,InteractionHand hand,CallbackInfoReturnable<InteractionResult> ci){if(Network.INSTANCE!=null&&(Network.INSTANCE.frozen(p)||Network.INSTANCE.arena&&(stack.getItem() instanceof BucketItem||stack.getItem() instanceof BlockItem||stack.getItem() instanceof SpawnEggItem||stack.is(Items.FLINT_AND_STEEL)||stack.is(Items.FIRE_CHARGE))))ci.setReturnValue(InteractionResult.FAIL);}
}
