package am.ik.accessmonitor.blacklist;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AllowedIpMatcherTest {

	@Test
	void exactMatchReturnsTrue() {
		AllowedIpMatcher matcher = new AllowedIpMatcher(List.of("192.168.1.100", "10.0.0.1"));
		assertThat(matcher.isAllowed("192.168.1.100")).isTrue();
		assertThat(matcher.isAllowed("10.0.0.1")).isTrue();
	}

	@Test
	void nonMatchedIpReturnsFalse() {
		AllowedIpMatcher matcher = new AllowedIpMatcher(List.of("192.168.1.100", "10.0.0.1"));
		assertThat(matcher.isAllowed("192.168.1.101")).isFalse();
		assertThat(matcher.isAllowed("172.16.0.1")).isFalse();
	}

	@Test
	void emptyListMatchesNothing() {
		AllowedIpMatcher matcher = new AllowedIpMatcher(List.of());
		assertThat(matcher.isAllowed("192.168.1.100")).isFalse();
		assertThat(matcher.isAllowed("10.0.0.1")).isFalse();
	}

	@Test
	void ipv6AddressMatch() {
		AllowedIpMatcher matcher = new AllowedIpMatcher(List.of("::1", "2001:db8::1"));
		assertThat(matcher.isAllowed("::1")).isTrue();
		assertThat(matcher.isAllowed("2001:db8::1")).isTrue();
		assertThat(matcher.isAllowed("2001:db8::2")).isFalse();
	}

}
