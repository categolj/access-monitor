package am.ik.accessmonitor.aggregation;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import am.ik.accessmonitor.TestcontainersConfiguration;
import am.ik.accessmonitor.event.AccessEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ValkeyAggregationServiceIntegrationTest {

	@Autowired
	ValkeyAggregationService aggregationService;

	@Autowired
	StringRedisTemplate redisTemplate;

	@BeforeEach
	void setUp() {
		Set<String> keys = this.redisTemplate.keys("access:*");
		if (keys != null && !keys.isEmpty()) {
			this.redisTemplate.delete(keys);
		}
	}

	@Test
	void aggregateWritesCountAndDurationKeys() {
		Instant timestamp = Instant.parse("2026-02-06T15:30:00Z");
		AccessEvent event = new AccessEvent(timestamp, "ik.am", "/entries/896", "GET", 200, 114720000L, "47.128.110.92",
				"https", "HTTP/2.0", "web-service", "web-router", 200, 100000000L, 14720000L, "abc123", "def456", 0);

		this.aggregationService.aggregate(event);

		// Verify 1m count key
		String countKey = "access:cnt:1m:202602061530:ik.am:/entries/896:200:GET";
		String countValue = this.redisTemplate.opsForValue().get(countKey);
		assertThat(countValue).isEqualTo("1");

		// Verify 1h count key
		String hourCountKey = "access:cnt:1h:2026020615:ik.am:/entries/896:200:GET";
		String hourCountValue = this.redisTemplate.opsForValue().get(hourCountKey);
		assertThat(hourCountValue).isEqualTo("1");

		// Verify duration hash
		String durKey = "access:dur:1m:202602061530:ik.am:/entries/896:200:GET";
		Map<Object, Object> durHash = this.redisTemplate.opsForHash().entries(durKey);
		assertThat(durHash.get("sum")).isEqualTo("114720000");
		assertThat(durHash.get("count")).isEqualTo("1");

		// Verify dimension indexes
		Set<String> hosts = this.redisTemplate.opsForSet().members("access:idx:1m:202602061530:hosts");
		assertThat(hosts).contains("ik.am");

		Set<String> paths = this.redisTemplate.opsForSet().members("access:idx:1m:202602061530:ik.am:paths");
		assertThat(paths).contains("/entries/896", "/entries/*");

		Set<String> statuses = this.redisTemplate.opsForSet().members("access:idx:1m:202602061530:ik.am:statuses");
		assertThat(statuses).contains("200");

		Set<String> methods = this.redisTemplate.opsForSet().members("access:idx:1m:202602061530:ik.am:methods");
		assertThat(methods).contains("GET");

		// Verify path pattern aggregation keys
		String patternCountKey = "access:cnt:1m:202602061530:ik.am:/entries/*:200:GET";
		assertThat(this.redisTemplate.opsForValue().get(patternCountKey)).isEqualTo("1");

		String patternDurKey = "access:dur:1m:202602061530:ik.am:/entries/*:200:GET";
		Map<Object, Object> patternDurHash = this.redisTemplate.opsForHash().entries(patternDurKey);
		assertThat(patternDurHash.get("sum")).isEqualTo("114720000");
		assertThat(patternDurHash.get("count")).isEqualTo("1");
	}

	@Test
	void aggregateIncrementsOnMultipleCalls() {
		Instant timestamp = Instant.parse("2026-02-06T15:30:00Z");
		AccessEvent event1 = new AccessEvent(timestamp, "ik.am", "/entries/1", "GET", 200, 100000000L, "47.128.110.92",
				"https", "HTTP/2.0", "web", "router", 200, 90000000L, 10000000L, "t1", "s1", 0);
		AccessEvent event2 = new AccessEvent(timestamp, "ik.am", "/entries/1", "GET", 200, 200000000L, "47.128.110.93",
				"https", "HTTP/2.0", "web", "router", 200, 180000000L, 20000000L, "t2", "s2", 0);

		this.aggregationService.aggregate(event1);
		this.aggregationService.aggregate(event2);

		String countKey = "access:cnt:1m:202602061530:ik.am:/entries/1:200:GET";
		assertThat(this.redisTemplate.opsForValue().get(countKey)).isEqualTo("2");

		String durKey = "access:dur:1m:202602061530:ik.am:/entries/1:200:GET";
		Map<Object, Object> durHash = this.redisTemplate.opsForHash().entries(durKey);
		assertThat(durHash.get("sum")).isEqualTo("300000000");
		assertThat(durHash.get("count")).isEqualTo("2");

		// Verify path pattern aggregation is also incremented
		String patternCountKey = "access:cnt:1m:202602061530:ik.am:/entries/*:200:GET";
		assertThat(this.redisTemplate.opsForValue().get(patternCountKey)).isEqualTo("2");

		String patternDurKey = "access:dur:1m:202602061530:ik.am:/entries/*:200:GET";
		Map<Object, Object> patternDurHash = this.redisTemplate.opsForHash().entries(patternDurKey);
		assertThat(patternDurHash.get("sum")).isEqualTo("300000000");
		assertThat(patternDurHash.get("count")).isEqualTo("2");
	}

}
