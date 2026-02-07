package am.ik.accessmonitor.blacklist;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AllowedHostMatcherTest {

	@Test
	void exactMatchOnly() {
		AllowedHostMatcher matcher = new AllowedHostMatcher(List.of("ik.am"));
		assertThat(matcher.isAllowed("ik.am")).isTrue();
		assertThat(matcher.isAllowed("www.ik.am")).isFalse();
		assertThat(matcher.isAllowed("api.ik.am")).isFalse();
	}

	@Test
	void suffixMatchOnly() {
		AllowedHostMatcher matcher = new AllowedHostMatcher(List.of(".ik.am"));
		assertThat(matcher.isAllowed("www.ik.am")).isTrue();
		assertThat(matcher.isAllowed("api.ik.am")).isTrue();
		assertThat(matcher.isAllowed("ik.am")).isFalse();
	}

	@Test
	void exactAndSuffixCombined() {
		AllowedHostMatcher matcher = new AllowedHostMatcher(List.of("ik.am", ".ik.am"));
		assertThat(matcher.isAllowed("ik.am")).isTrue();
		assertThat(matcher.isAllowed("www.ik.am")).isTrue();
		assertThat(matcher.isAllowed("api.ik.am")).isTrue();
	}

	@Test
	void emptyListMatchesNothing() {
		AllowedHostMatcher matcher = new AllowedHostMatcher(List.of());
		assertThat(matcher.isAllowed("ik.am")).isFalse();
		assertThat(matcher.isAllowed("evil.example.com")).isFalse();
	}

	@Test
	void disallowedHostDoesNotMatch() {
		AllowedHostMatcher matcher = new AllowedHostMatcher(List.of("ik.am", ".ik.am"));
		assertThat(matcher.isAllowed("evil.example.com")).isFalse();
		assertThat(matcher.isAllowed("notik.am")).isFalse();
	}

	@Test
	void deepSubdomainMatchesSuffix() {
		AllowedHostMatcher matcher = new AllowedHostMatcher(List.of(".ik.am"));
		assertThat(matcher.isAllowed("a.b.c.ik.am")).isTrue();
	}

	@Test
	void multipleAllowedDomains() {
		AllowedHostMatcher matcher = new AllowedHostMatcher(List.of("ik.am", ".ik.am", "example.com", ".example.com"));
		assertThat(matcher.isAllowed("ik.am")).isTrue();
		assertThat(matcher.isAllowed("www.ik.am")).isTrue();
		assertThat(matcher.isAllowed("example.com")).isTrue();
		assertThat(matcher.isAllowed("sub.example.com")).isTrue();
		assertThat(matcher.isAllowed("other.org")).isFalse();
	}

	@Test
	void suffixDoesNotMatchPartialLabel() {
		AllowedHostMatcher matcher = new AllowedHostMatcher(List.of(".am"));
		// ".am" should match "ik.am" (suffix at domain boundary)
		assertThat(matcher.isAllowed("ik.am")).isTrue();
		// but should not match "spam" (no dot boundary)
		assertThat(matcher.isAllowed("spam")).isFalse();
	}

}
