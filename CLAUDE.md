# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this
repository.

## Build and Development Commands

This is a Spring Boot 4.x application using Maven with Java 21.
Code formatting is enforced using the Spring Java Format plugin.

**Build Commands:**

```bash
./mvnw clean spring-javaformat:apply package                    # Build application
./mvnw spring-boot:run                                          # Run application locally
./mvnw spring-javaformat:apply test                             # Run all tests
```

## Development Requirements

### Prerequisites

- Java 21 runtime

### Code Standards

- Use builder pattern if the number of arguments is more than two
- Write javadoc and comments in English
- Spring Java Format enforced via Maven plugin
- All code must pass formatting validation before commit
- Use Java 21 compatible features
- Use modern Java technics as much as possible like Java Records, Pattern Matching, Text Block
  etc ... but don't use "var".
- Be sure to avoid circular references between classes and packages.
- Don't use Lombok.
- Don't use Google Guava.

### Spring Specific Rules

- Always use constructor injection for Spring beans. No `@Autowired` required except for test code.
- Use `RestClient` for external API calls. Don't use `RestTemplate`.
- `RestClient` should be used with injected/autoconfigured `RestClient.Builder`.
- Use `JdbcClient` for database operations. Don't use `JdbcTemplate` except for batch update.
- Use `@Configuration(proxyBeanMethods = false)` for configuration classes to avoid proxying issues.
- Use `@ConfigurationProperties` + Java Records for configuration properties classes. Don't use
  `@Value` for configuration properties.
- Use `@DefaultValue` for non-null default values in configuration properties classes.

### Package Structure

Package structure should follow the "package by feature" principle, grouping related classes
together. Not by technical layers.
Exceptionally, web related classes including Controllers should be located in `web` package under the
feature package. Other layers should not have dedicated packages like "service", "repository", "dto"
etc...

`web` package should not be shared across different features. Each feature should have its own `web`
domain objects should be clean and not contain external layers like web or database.

For DTOs, use inner record classes in the appropriate classes. For example, if you have a
`UserController`, define the request/response class inside that controller class.

### Testing Strategy

:

- **Unit Tests**: JUnit 5 with AssertJ for service layer testing
- **Integration Tests**: `@SpringBootTest` + Testcontainers for full application context
- **Test Data Management**: Use `@TempDir` for filesystem testing, maintain test independence
- All tests must pass before completing tasks
- Test coverage includes artifact operations, repository browsing, and API endpoints

### After Task completion

- Ensure all code is formatted using `./mvnw spring-javaformat:apply`
- For each task, notify that the task is complete and ready for review by the following command:

```
osascript -e 'display notification "<Message Body>" with title "<Message Title>"’
```