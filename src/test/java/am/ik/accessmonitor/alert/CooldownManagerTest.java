package am.ik.accessmonitor.alert;

import java.time.Duration;
import java.time.InstantSource;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CooldownManagerTest {

	@Test
	void canFireWhenNeverFired() {
		CooldownManager manager = new CooldownManager(InstantSource.system());
		assertThat(manager.canFire("test-alert", Duration.ofMinutes(5))).isTrue();
	}

	@Test
	void cannotFireDuringCooldown() {
		CooldownManager manager = new CooldownManager(InstantSource.system());
		manager.recordFiring("test-alert");
		assertThat(manager.canFire("test-alert", Duration.ofMinutes(5))).isFalse();
	}

	@Test
	void canFireAfterCooldown() {
		CooldownManager manager = new CooldownManager(InstantSource.system());
		manager.recordFiring("test-alert");
		// Zero duration cooldown should allow immediate re-firing
		assertThat(manager.canFire("test-alert", Duration.ZERO)).isTrue();
	}

	@Test
	void differentKeysAreIndependent() {
		CooldownManager manager = new CooldownManager(InstantSource.system());
		manager.recordFiring("alert-1");
		assertThat(manager.canFire("alert-1", Duration.ofMinutes(5))).isFalse();
		assertThat(manager.canFire("alert-2", Duration.ofMinutes(5))).isTrue();
	}

}
