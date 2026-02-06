package am.ik.accessmonitor.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import am.ik.accessmonitor.TestcontainersConfiguration;
import am.ik.accessmonitor.aggregation.Granularity;
import am.ik.accessmonitor.aggregation.ValkeyKeyBuilder;
import am.ik.accessmonitor.ui.page.DashboardPage;
import am.ik.accessmonitor.ui.page.LoginPage;
import am.ik.accessmonitor.ui.page.QueryPage;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class UiEndToEndTest {

	static Playwright playwright;

	static Browser browser;

	@LocalServerPort
	int port;

	@Autowired
	StringRedisTemplate redisTemplate;

	BrowserContext context;

	Page page;

	String baseUrl;

	@BeforeAll
	static void setupBrowser() {
		playwright = Playwright.create();
		browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
	}

	@AfterAll
	static void teardownBrowser() {
		if (browser != null) {
			browser.close();
		}
		if (playwright != null) {
			playwright.close();
		}
	}

	@BeforeEach
	void setupPage() {
		this.baseUrl = "http://localhost:" + this.port;
		this.context = browser.newContext();
		this.page = this.context.newPage();
	}

	@AfterEach
	void teardownPage() {
		if (this.context != null) {
			this.context.close();
		}
	}

	@Test
	void loginPageIsShownAndLoginWorks() {
		LoginPage loginPage = new LoginPage(this.page).navigate(this.baseUrl);
		assertThat(loginPage.isDisplayed()).isTrue();

		DashboardPage dashboard = loginPage.login("user", "password");
		assertThat(dashboard.isChartVisible()).isTrue();
	}

	@Test
	void dashboardShowsRealtimeChart() {
		LoginPage loginPage = new LoginPage(this.page).navigate(this.baseUrl);
		DashboardPage dashboard = loginPage.login("user", "password");

		assertThat(dashboard.isChartVisible()).isTrue();
		assertThat(dashboard.isAccessLogTableVisible()).isTrue();
	}

	@Test
	void dashboardReceivesSseEvents() {
		LoginPage loginPage = new LoginPage(this.page).navigate(this.baseUrl);
		DashboardPage dashboard = loginPage.login("user", "password");

		// Send diverse test data via the ingest API
		RestClient restClient = RestClient.builder().baseUrl(this.baseUrl).build();
		String[][] testData = {
				// host, path, method, statusCode, durationNs, clientIp, traceId
				{ "ik.am", "/index.html", "GET", "200", "15000000", "10.0.0.1", "trace-01" },
				{ "ik.am", "/api/users", "GET", "200", "45000000", "10.0.0.2", "trace-02" },
				{ "ik.am", "/api/users", "POST", "201", "80000000", "10.0.0.3", "trace-03" },
				{ "ik.am", "/api/users/1", "PUT", "200", "60000000", "10.0.0.4", "trace-04" },
				{ "ik.am", "/assets/style.css", "GET", "304", "5000000", "10.0.0.1", "trace-05" },
				{ "ik.am", "/old-page", "GET", "301", "8000000", "10.0.0.5", "trace-06" },
				{ "ik.am", "/api/secret", "GET", "403", "12000000", "10.0.0.6", "trace-07" },
				{ "ik.am", "/api/missing", "GET", "404", "10000000", "10.0.0.7", "trace-08" },
				{ "ik.am", "/api/broken", "POST", "500", "200000000", "10.0.0.8", "trace-09" },
				{ "ik.am", "/api/timeout", "GET", "503", "500000000", "10.0.0.9", "trace-10" }, };

		Instant baseTimestamp = Instant.parse("2026-02-06T15:30:00Z");
		for (int i = 0; i < testData.length; i++) {
			String[] row = testData[i];
			String timestamp = baseTimestamp.plusSeconds(i).toString();
			String body = """
					{
					  "timestamp": "%s",
					  "host": "%s",
					  "path": "%s",
					  "method": "%s",
					  "statusCode": %s,
					  "durationNs": %s,
					  "clientIp": "%s",
					  "traceId": "%s"
					}
					""".formatted(timestamp, row[0], row[1], row[2], row[3], row[4], row[5], row[6]);
			restClient.post()
				.uri("/api/ingest")
				.contentType(MediaType.APPLICATION_JSON)
				.headers(headers -> headers.setBasicAuth("user", "password"))
				.body(body)
				.retrieve()
				.toBodilessEntity();
		}

		// Wait for all events to arrive in the dashboard
		dashboard.waitForTotalRequests(testData.length, 15);

		// Verify access log contains multiple rows
		assertThat(dashboard.getAccessLogRowCount()).isGreaterThanOrEqualTo(testData.length);

		// Verify summary card values reflect the ingested data
		assertThat(Integer.parseInt(dashboard.getTotalRequests().trim())).isGreaterThanOrEqualTo(testData.length);

		// Error rate should be > 0% (we sent 2 5xx responses out of 10)
		String errorRate = dashboard.getErrorRate();
		assertThat(errorRate).isNotEqualTo("0.0%");

		// Avg duration should be > 0 ms
		String avgDuration = dashboard.getAvgDuration();
		assertThat(avgDuration).isNotEqualTo("0.0 ms");

		// Verify specific log entries exist
		dashboard.waitForAccessLogEntry("/api/broken", 5);
		dashboard.waitForAccessLogEntry("/api/timeout", 5);
		dashboard.waitForAccessLogEntry("403", 5);
	}

	@Test
	void dashboardFiltersByHostPathAndMethod() {
		LoginPage loginPage = new LoginPage(this.page).navigate(this.baseUrl);
		DashboardPage dashboard = loginPage.login("user", "password");

		// Verify filter controls are present
		assertThat(this.page.locator("[data-testid='dashboard-filter']").isVisible()).isTrue();
		assertThat(this.page.locator("[data-testid='filter-host']").isVisible()).isTrue();
		assertThat(this.page.locator("[data-testid='filter-path']").isVisible()).isTrue();
		assertThat(this.page.locator("[data-testid='filter-method']").isVisible()).isTrue();

		// Send initial data and wait for it to arrive
		RestClient restClient = RestClient.builder().baseUrl(this.baseUrl).build();
		String[][] testData = { { "app1.example.com", "/api/users", "GET", "200", "20000000", "10.0.0.1", "trace-f1" },
				{ "app1.example.com", "/api/users", "POST", "201", "40000000", "10.0.0.2", "trace-f2" },
				{ "app2.example.com", "/api/orders", "GET", "200", "30000000", "10.0.0.3", "trace-f3" },
				{ "app2.example.com", "/api/orders", "DELETE", "204", "15000000", "10.0.0.4", "trace-f4" },
				{ "app1.example.com", "/api/health", "GET", "200", "5000000", "10.0.0.5", "trace-f5" },
				{ "app2.example.com", "/api/orders", "POST", "500", "100000000", "10.0.0.6", "trace-f6" }, };

		sendTestData(restClient, testData, Instant.parse("2026-02-06T16:00:00Z"), "");
		dashboard.waitForTotalRequests(testData.length, 15);

		// Apply host filter - totals reset to 0
		dashboard.setHostFilter("app1");
		assertThat(dashboard.getTotalRequests().trim()).isEqualTo("0");

		// Send more events: only app1 events (3 of 6) should match
		sendTestData(restClient, testData, Instant.now(), "-round2");
		dashboard.waitForTotalRequests(3, 15);
		assertThat(Integer.parseInt(dashboard.getTotalRequests().trim())).isEqualTo(3);

		// Switch to method filter (GET): 3 of 6 events match
		dashboard.clearFilter();
		dashboard.setMethodFilter("GET");
		sendTestData(restClient, testData, Instant.now(), "-round3");
		dashboard.waitForTotalRequests(3, 15);
		assertThat(Integer.parseInt(dashboard.getTotalRequests().trim())).isEqualTo(3);

		// Clear all filters and verify inputs are empty
		dashboard.clearFilter();
		assertThat(this.page.locator("[data-testid='filter-host']").inputValue()).isEmpty();
		assertThat(this.page.locator("[data-testid='filter-path']").inputValue()).isEmpty();
		assertThat(this.page.locator("[data-testid='filter-method']").inputValue()).isEmpty();
	}

	private void sendTestData(RestClient restClient, String[][] testData, Instant baseTimestamp, String traceSuffix) {
		for (int i = 0; i < testData.length; i++) {
			String[] row = testData[i];
			String timestamp = baseTimestamp.plusSeconds(i).toString();
			String body = """
					{
					  "timestamp": "%s",
					  "host": "%s",
					  "path": "%s",
					  "method": "%s",
					  "statusCode": %s,
					  "durationNs": %s,
					  "clientIp": "%s",
					  "traceId": "%s"
					}
					""".formatted(timestamp, row[0], row[1], row[2], row[3], row[4], row[5], row[6] + traceSuffix);
			restClient.post()
				.uri("/api/ingest")
				.contentType(MediaType.APPLICATION_JSON)
				.headers(headers -> headers.setBasicAuth("user", "password"))
				.body(body)
				.retrieve()
				.toBodilessEntity();
		}
	}

	@Test
	void queryPageLoadsAndShowsResults() {
		// Seed Valkey with test aggregation data
		Instant now = Instant.now();
		Granularity granularity = Granularity.ONE_MINUTE;
		String ts = granularity.format(now);
		String host = "test.example.com";
		String path = "/api/test";
		String method = "GET";
		int status = 200;

		this.redisTemplate.opsForValue()
			.set(ValkeyKeyBuilder.countKey(granularity, ts, host, path, status, method), "42");
		this.redisTemplate.opsForSet().add(ValkeyKeyBuilder.hostsIndexKey(granularity, ts), host);
		this.redisTemplate.opsForSet().add(ValkeyKeyBuilder.pathsIndexKey(granularity, ts, host), path);
		this.redisTemplate.opsForSet()
			.add(ValkeyKeyBuilder.statusesIndexKey(granularity, ts, host), String.valueOf(status));
		this.redisTemplate.opsForSet().add(ValkeyKeyBuilder.methodsIndexKey(granularity, ts, host), method);

		LoginPage loginPage = new LoginPage(this.page).navigate(this.baseUrl);
		DashboardPage dashboard = loginPage.login("user", "password");
		QueryPage queryPage = dashboard.navigateToQuery();

		// Use from/to in local timezone (browser interprets datetime-local as local time)
		DateTimeFormatter localFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
			.withZone(ZoneId.systemDefault());
		String fromLocal = localFormatter.format(now.minusSeconds(120));
		String toLocal = localFormatter.format(now.plusSeconds(120));

		queryPage.setGranularity("1m");
		queryPage.setFromTime(fromLocal);
		queryPage.setToTime(toLocal);
		queryPage.submitQuery();

		assertThat(queryPage.getResultRowCount()).isGreaterThan(0);
		assertThat(queryPage.isResultChartVisible()).isTrue();
	}

	@Test
	void darkModeToggleWorks() {
		LoginPage loginPage = new LoginPage(this.page).navigate(this.baseUrl);
		DashboardPage dashboard = loginPage.login("user", "password");

		String initialTheme = dashboard.getThemeAttribute();
		dashboard.clickThemeToggle();

		String newTheme = dashboard.getThemeAttribute();
		assertThat(newTheme).isNotEqualTo(initialTheme);
		if ("light".equals(initialTheme)) {
			assertThat(newTheme).isEqualTo("dark");
		}
		else {
			assertThat(newTheme).isEqualTo("light");
		}
	}

}
