package am.ik.accessmonitor.ui.page;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * Page object for the dashboard page.
 */
public class DashboardPage {

	private final Page page;

	public DashboardPage(Page page) {
		this.page = page;
	}

	/**
	 * Returns whether the request rate chart is visible.
	 */
	public boolean isChartVisible() {
		return this.page.locator("[data-testid='request-rate-chart']").isVisible();
	}

	/**
	 * Returns whether the access log table is visible.
	 */
	public boolean isAccessLogTableVisible() {
		return this.page.locator("[data-testid='access-log']").isVisible();
	}

	/**
	 * Waits for an access log entry containing the specified text.
	 */
	public void waitForAccessLogEntry(String text, int timeoutSeconds) {
		this.page.locator("[data-testid='access-log-body']")
			.locator("tr")
			.filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText(text))
			.first()
			.waitFor(new com.microsoft.playwright.Locator.WaitForOptions().setTimeout(timeoutSeconds * 1000.0)
				.setState(WaitForSelectorState.VISIBLE));
	}

	/**
	 * Returns the number of rows in the access log table.
	 */
	public int getAccessLogRowCount() {
		return this.page.locator("[data-testid='access-log-body'] tr").count();
	}

	/**
	 * Returns the total requests value from the summary cards.
	 */
	public String getTotalRequests() {
		return this.page.locator("[data-testid='total-requests']").textContent();
	}

	/**
	 * Returns the error rate value from the summary cards.
	 */
	public String getErrorRate() {
		return this.page.locator("[data-testid='error-rate']").textContent();
	}

	/**
	 * Returns the average duration value from the summary cards.
	 */
	public String getAvgDuration() {
		return this.page.locator("[data-testid='avg-duration']").textContent();
	}

	/**
	 * Waits until the total requests value is at least the given count.
	 */
	public void waitForTotalRequests(int minCount, int timeoutSeconds) {
		this.page.waitForCondition(() -> {
			String text = this.page.locator("[data-testid='total-requests']").textContent();
			try {
				return text != null && Integer.parseInt(text.trim()) >= minCount;
			}
			catch (NumberFormatException ex) {
				return false;
			}
		}, new Page.WaitForConditionOptions().setTimeout(timeoutSeconds * 1000.0));
	}

	/**
	 * Sets the host filter input.
	 */
	public void setHostFilter(String host) {
		this.page.locator("[data-testid='filter-host']").fill(host);
	}

	/**
	 * Sets the path filter input.
	 */
	public void setPathFilter(String path) {
		this.page.locator("[data-testid='filter-path']").fill(path);
	}

	/**
	 * Sets the method filter select.
	 */
	public void setMethodFilter(String method) {
		this.page.locator("[data-testid='filter-method']").selectOption(method);
	}

	/**
	 * Clicks the clear filter button.
	 */
	public void clearFilter() {
		this.page.locator("[data-testid='filter-clear']").click();
	}

	/**
	 * Clicks the theme toggle button.
	 */
	public void clickThemeToggle() {
		this.page.locator("[data-testid='theme-toggle']").click();
	}

	/**
	 * Returns the data-theme attribute from the html element.
	 */
	public String getThemeAttribute() {
		return this.page.locator("html").getAttribute("data-theme");
	}

	/**
	 * Navigates to the query page.
	 */
	public QueryPage navigateToQuery() {
		this.page.locator("[data-testid='nav-query']").click();
		this.page.waitForSelector("[data-testid='query-page']");
		return new QueryPage(this.page);
	}

}
