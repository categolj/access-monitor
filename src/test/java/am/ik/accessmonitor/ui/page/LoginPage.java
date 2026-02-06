package am.ik.accessmonitor.ui.page;

import com.microsoft.playwright.Page;

/**
 * Page object for the login page.
 */
public class LoginPage {

	private final Page page;

	public LoginPage(Page page) {
		this.page = page;
	}

	/**
	 * Navigates to the login page.
	 */
	public LoginPage navigate(String baseUrl) {
		this.page.navigate(baseUrl);
		this.page.waitForSelector("[data-testid='login-form']");
		return this;
	}

	/**
	 * Returns whether the login form is displayed.
	 */
	public boolean isDisplayed() {
		return this.page.locator("[data-testid='login-form']").isVisible();
	}

	/**
	 * Logs in with the given credentials and returns the dashboard page.
	 */
	public DashboardPage login(String username, String password) {
		this.page.locator("[data-testid='username']").fill(username);
		this.page.locator("[data-testid='password']").fill(password);
		this.page.locator("[data-testid='login-button']").click();
		this.page.waitForSelector("[data-testid='dashboard']");
		return new DashboardPage(this.page);
	}

	/**
	 * Returns the error message displayed on the login page.
	 */
	public String getErrorMessage() {
		return this.page.locator("[data-testid='login-error']").textContent();
	}

}
