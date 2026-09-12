package nu.metacraft.rivals.mixin;

import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import nu.metacraft.rivals.gun.PaintWeapon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Left click is the special, and this is where a left click at thin air arrives.
 *
 * <p>26.3 splits what used to be the swing packet in two: an attack on the block or the entity under
 * the crosshair (the packets Fabric's {@code AttackBlockCallback} and {@code AttackEntityCallback}
 * already cover), and {@link ServerboundPunchPacket}, which the client sends on <em>every</em> left
 * click — at a block, at an entity and at nothing at all. Nothing in the Fabric API covers that last
 * case, so a click at the sky would otherwise never reach the server as anything, which is most of
 * how a paint weapon is actually fired. The client does not repeat it while the button is held (only
 * block breaking continues), and it is sent even while an item is being used, which is what lets the
 * charger be scoped with the right button and fired with the left.
 *
 * <p>Injected after {@code ensureRunningOnSameThread}, the same place the other Metacraft mods hook
 * their packet handlers: before it, this code would be running on the netty thread.
 * {@link PaintWeapon#leftClick} de-duplicates the tick, so the attack packet and the punch that
 * follows it are one special rather than two.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class ServerGamePacketListenerImplMixin {
	@Shadow
	public ServerPlayer player;

	@Inject(
			method = "handlePunch",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
					shift = At.Shift.AFTER
			)
	)
	public void rivalsLeftClick(ServerboundPunchPacket packet, CallbackInfo info) {
		PaintWeapon.leftClick(player);
	}
}
