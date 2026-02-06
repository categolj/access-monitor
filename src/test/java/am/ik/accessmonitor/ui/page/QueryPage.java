package am.ik.accessmonitor.ui.page;

import com.microsoft.playwright.Page;

/**
 * Page object for the query page.
 */
public class QueryPage {

	private final Page page;

	public QueryPage(Page page) {
		this.page = page;
	}

	/**
	 * Sets the granularity select value.
	 */
	public void setGranularity(String value) {
		this.page.locator("[data-testid='granularity-select']").selectOption(value);
	}

	/**
	 * Sets the from time input.
	 */
	public void setFromTime(String datetimeLocalValue) {
		this.page.locator("[data-testid='from-input']").fill(datetimeLocalValue);
	}

	/**
	 * Sets the to time input.
	 */
	public void setToTime(String datetimeLocalValue) {
		this.page.locator("[data-testid='to-input']").fill(datetimeLocalValue);
	}

	/**
	 * Clicks the query button and waits for the query to complete.
	 */
	public void submitQuery() {
		this.page.locator("[data-testid='query-button']").click();
		// Wait for loading state to appear and then resolve
		this.page.waitForCondition(() -> {
			String text = this.page.locator("[data-testid='query-button']").textContent();
			return text != null && text.trim().equals("Query");
		}, new Page.WaitForConditionOptions().setTimeout(30000));
	}

	/**
	 * Returns the number of result rows in the query results table. Returns 0 if no
	 * results table is visible.
	 */
	public int getResultRowCount() {
		if (this.page.locator("[data-testid='query-results-body']").count() == 0) {
			return 0;
		}
		return this.page.locator("[data-testid='query-results-body'] tr").count();
	}

	/**
	 * Returns whether the result chart is visible.
	 */
	public boolean isResultChartVisible() {
		return this.page.locator("[data-testid='query-chart']").isVisible();
	}

}
