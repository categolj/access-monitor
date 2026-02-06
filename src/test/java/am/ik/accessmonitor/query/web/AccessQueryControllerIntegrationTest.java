package am.ik.accessmonitor.query.web;

import java.util.Set;

import am.ik.accessmonitor.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class AccessQueryControllerIntegrationTest {

	RestTestClient client;

	RestTestClient noAuthClient;

	@Autowired
	StringRedisTemplate redisTemplate;

	@BeforeEach
	void setUp(@LocalServerPort int port) {
		this.client = RestTestClient.bindToServer()
			.baseUrl("http://localhost:" + port)
			.defaultHeaders(headers -> headers.setBasicAuth("user", "password"))
			.build();
		this.noAuthClient = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
		Set<String> keys = this.redisTemplate.keys("access:*");
		if (keys != null && !keys.isEmpty()) {
			this.redisTemplate.delete(keys);
		}
		// Set up test data
		this.redisTemplate.opsForValue().set("access:cnt:1m:202602061530:ik.am:/entries/896:200:GET", "15");
		this.redisTemplate.opsForHash()
			.putAll("access:dur:1m:202602061530:ik.am:/entries/896:200:GET",
					java.util.Map.of("sum", "1720800000", "count", "15"));
		this.redisTemplate.opsForSet().add("access:idx:1m:202602061530:hosts", "ik.am");
		this.redisTemplate.opsForSet().add("access:idx:1m:202602061530:ik.am:paths", "/entries/896");
		this.redisTemplate.opsForSet().add("access:idx:1m:202602061530:ik.am:statuses", "200");
		this.redisTemplate.opsForSet().add("access:idx:1m:202602061530:ik.am:methods", "GET");
	}

	@Test
	void queryAccess() {
		this.client.get()
			.uri("/api/query/access?granularity=1m&from=2026-02-06T15:30:00Z&to=2026-02-06T15:30:00Z&host=ik.am")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.json("""
					{
					  "granularity": "1m",
					  "from": "2026-02-06T15:30:00Z",
					  "to": "2026-02-06T15:30:00Z",
					  "series": [
					    {
					      "timestamp": "2026-02-06T15:30:00Z",
					      "host": "ik.am",
					      "path": "/entries/896",
					      "method": "GET",
					      "statuses": {
					        "200": {
					          "count": 15,
					          "durationMsAvg": 114.72
					        }
					      }
					    }
					  ]
					}
					""");
	}

	@Test
	void queryDimensions() {
		this.client.get()
			.uri("/api/query/dimensions?granularity=1m&timestamp=2026-02-06T15:30:00Z")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.json("""
					{
					  "granularity": "1m",
					  "timestamp": "2026-02-06T15:30:00Z",
					  "hosts": ["ik.am"]
					}
					""");
	}

	@Test
	void queryDimensionsWithHost() {
		this.client.get()
			.uri("/api/query/dimensions?granularity=1m&timestamp=2026-02-06T15:30:00Z&host=ik.am")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.json("""
					{
					  "granularity": "1m",
					  "timestamp": "2026-02-06T15:30:00Z",
					  "host": "ik.am",
					  "paths": ["/entries/896"],
					  "statuses": [200],
					  "methods": ["GET"]
					}
					""");
	}

	@Test
	void queryAccessRequiresAuth() {
		this.noAuthClient.get()
			.uri("/api/query/access?granularity=1m&from=2026-02-06T15:30:00Z&to=2026-02-06T15:30:00Z")
			.exchange()
			.expectStatus()
			.isUnauthorized();
	}

}
