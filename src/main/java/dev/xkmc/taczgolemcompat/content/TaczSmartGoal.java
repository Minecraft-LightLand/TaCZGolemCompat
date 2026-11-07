package dev.xkmc.taczgolemcompat.content;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.gun.AbstractGunItem;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.pojo.data.gun.Bolt;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import com.tacz.guns.util.time.GunTime;
import dev.xkmc.mob_weapon_api.api.ai.IWeaponHolder;
import dev.xkmc.mob_weapon_api.api.goals.IMeleeGoal;
import dev.xkmc.mob_weapon_api.example.goal.SmartRangedAttackGoal;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.CrossbowAttackMob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import static dev.xkmc.taczgolemcompat.content.TaczSmartGoal.GunState.*;

/**
 * Adapted from Pillager's Gun
 */
public class TaczSmartGoal<T extends Mob> extends SmartRangedAttackGoal<T> {

	private GunState gunState;
	private int attackDelay;
	private double attackCount;
	private int ammoCount;

	public TaczSmartGoal(T mob, IWeaponHolder holder, IMeleeGoal melee, double speed, double radius) {
		super(mob, holder, melee, speed, radius);
		this.gunState = UNCHARGED;
	}

	public boolean canUse() {
		return isHoldingGun() && (isValidTarget() || !hasAmmo() && canReload() || gunState == CHARGING);
	}

	public boolean canContinueToUse() {
		return isHoldingGun() && (isValidTarget() || !hasAmmo() && canReload() || gunState == CHARGING);
	}

	public void start() {
		super.start();
		this.gunState = UNCHARGED;
		var operator = IGunOperator.fromLivingEntity(mob);
		operator.draw(mob::getMainHandItem);
		operator.getDataHolder().drawTimestamp = GunTime.nowMillis() - 10000L;
	}

	public void stop() {
		super.stop();
		attackDelay = 0;
		attackCount = 0;
		mob.getNavigation().stop();
		var gun = getGun();
		if (gun != null && isHoldingGun()) {
			IGunOperator operator = IGunOperator.fromLivingEntity(mob);
			operator.cancelReload();
			operator.aim(false);
			GunData gunData = TimelessAPI.getCommonGunIndex(gun.getGunId(mob.getMainHandItem())).map(CommonGunIndex::getGunData).orElse(null);
			if (gunData != null && gunData.getBolt() == Bolt.MANUAL_ACTION) {
				operator.bolt();
			}
		}

	}

	public void tick() {
		LivingEntity target = mob.getTarget();
		var gun = getGun();
		ItemStack stack = mob.getMainHandItem();
		IGunOperator operator = IGunOperator.fromLivingEntity(mob);
		boolean preloadOnly = false;
		if (gun == null || !isHoldingGun()) return;
		if (target != null) {
			double range = mob.getAttributeValue(Attributes.FOLLOW_RANGE);
			double dist = target.position().subtract(mob.position()).length();
			preloadOnly = isValidTarget() && (dist > range || seeTime < 5) && attackDelay == 0;
			if (isValidTarget() && canMeleeAttack(operator, target)) {
				operator.melee();
				return;
			}
			strafing();
			mob.getLookControl().setLookAt(target, 30, 90);
		}
		if (gun.isOverheatLocked(stack)) {
			gunState = UNCHARGED;
			return;
		}
		if (gunState == UNCHARGED && hasAmmo()) {
			gunState = CHARGED;
			ammoCount = getAmmoCount(stack);
		} else if (gunState == CHARGED && !hasAmmo()) {
			gunState = UNCHARGED;
		}
		if (gunState == UNCHARGED) {
			if (!preloadOnly && canReload()) {
				operator.reload();
				gunState = CHARGING;
				if (mob instanceof CrossbowAttackMob cs) {
					cs.setChargingCrossbow(true);
				}
			}
		} else if (gunState == CHARGING) {
			if (!isHoldingGun()) {
				gunState = UNCHARGED;
			}
			if (!operator.getDataHolder().reloadStateType.isReloading()) {
				if (hasAmmo()) {
					gunState = CHARGED;
					attackDelay = 10 + mob.getRandom().nextInt(20);
					ammoCount = getAmmoCount(stack);
				} else {
					gunState = UNCHARGED;
				}
				if (mob instanceof CrossbowAttackMob cs) {
					cs.setChargingCrossbow(false);
				}
			}
		} else if (gunState == CHARGED) {
			if (--attackDelay <= 0) {
				operator.aim(true);
				gunState = READY_TO_ATTACK;
				attackDelay = 0;
			}
		} else if (gunState == READY_TO_ATTACK && seeTime > 0 && isRightAngle()) {
			GunData data = TimelessAPI.getCommonGunIndex(gun.getGunId(stack)).map(CommonGunIndex::getGunData).orElse(null);
			if (data != null) {
				for (attackCount += gun.getFireMode(stack) == FireMode.AUTO ? gun.getRPM(stack) / 1200d : gun.getRPM(stack) / (Math.max(1200 * (target.distanceTo(mob) / 8), 2400)); attackCount >= 1; --attackCount) {
					if (isHoldingGun()) {
						operator.shoot(mob::getXRot, mob::getYHeadRot);
						if (data.getBolt() == Bolt.MANUAL_ACTION) {
							operator.bolt();
						}

						if (ammoCount > 0) {
							--ammoCount;
						}

						if (ammoCount == 0) {
							operator.aim(false);
							attackCount = 0;
							gunState = UNCHARGED;
							break;
						}
					}
				}
			}
		}

	}

	@Override
	public void performRangedAttack(LivingEntity livingEntity, float v, ItemStack itemStack, InteractionHand interactionHand) {
	}

	private boolean isHoldingGun() {
		return getGun() != null;
	}

	private boolean canReload() {
		if (holder.toUser().bypassAllConsumption()) return true;
		IGun item = this.getGun();
		if (item instanceof AbstractGunItem gun) {
			ItemStack stack = mob.getItemInHand(InteractionHand.MAIN_HAND);
			if (gun.useDummyAmmo(stack) && gun.getDummyAmmoAmount(stack) == 0) {
				stack.getOrCreateTag().remove("DummyAmmo");
			}
			return gun.canReload(mob, stack);
		} else {
			return false;
		}
	}

	@Nullable
	private IGun getGun() {
		return IGun.getIGunOrNull(mob.getItemInHand(InteractionHand.MAIN_HAND));
	}

	private boolean isValidTarget() {
		return mob.getTarget() != null && mob.getTarget().isAlive();
	}


	private boolean hasAmmo() {
		return getAmmoCount(mob.getItemInHand(InteractionHand.MAIN_HAND)) != 0;
	}

	private boolean isRightAngle() {
		LivingEntity target = mob.getTarget();
		if (target != null && isValidTarget()) {
			var diff = target.getEyePosition().subtract(mob.getEyePosition());
			var deg = vectorDegreeCalculate(mob.getViewVector(1), diff);
			return deg < 10 + Math.max(0, 64 - mob.distanceToSqr(target));
		} else {
			return false;
		}
	}

	private int getAmmoCount(ItemStack stack) {
		IGun gun = IGun.getIGunOrNull(stack);
		if (gun != null) {
			GunData gunData = TimelessAPI.getCommonGunIndex(gun.getGunId(stack)).map(CommonGunIndex::getGunData).orElse(null);
			if (gunData != null) {
				if (gun.useInventoryAmmo(stack)) return -1;
				return gun.getCurrentAmmoCount(stack) + (gun.hasBulletInBarrel(stack) && gunData.getBolt() != Bolt.OPEN_BOLT ? 1 : 0);
			}
		}

		return 0;
	}

	public double vectorDegreeCalculate(Vec3 dir, Vec3 diff) {
		double cos = dir.dot(diff) / dir.length() / diff.length();
		return Math.toDegrees(Math.acos(cos));
	}

	public boolean canMeleeAttack(IGunOperator gunOperator, LivingEntity target) {
		return GunTime.nowMillis() - gunOperator.getDataHolder().meleeTimestamp > 3000L && isValidTarget() && getAttackReachSqr(target) >= mob.distanceToSqr(target);
	}

	public double getAttackReachSqr(LivingEntity target) {
		return mob.getBbWidth() * 2 * mob.getBbWidth() * 2 + target.getBbWidth();
	}

	enum GunState {
		UNCHARGED,
		CHARGING,
		CHARGED,
		READY_TO_ATTACK;
	}

}
