# Repository Guidelines

## Project Structure & Module Organization

This is a standalone Spring Boot 2.7 / Java 11 job that rebuilds anonymous-profile records from UIN history data.

- `src/main/java/io/mosip/idrepository/anonymousprofile/` contains application code.
  - `config/` holds datasource configuration.
  - `repository/` contains JDBC access to history data.
  - `helper/` contains rebuild and profile-processing logic.
  - `client/` contains external-service clients, such as Key Manager integration.
- `src/main/resources/application-default.properties` defines the default runtime configuration.
- `src/test/java/...` contains integration tests using Spring Boot Test and H2.
- `target/` is generated Maven output; do not commit or edit it.

## Build, Test, and Development Commands

Use Maven from the repository root:

```bash
mvn clean verify
mvn test
mvn spring-boot:run -Dspring-boot.run.profiles=default
```

`mvn clean verify` compiles and runs the full verification lifecycle. `mvn test` runs tests only. The run command starts the job using the `default` profile; keep `rebuild.dry-run=true` until the full decrypt and profile-building flow is implemented.

## Coding Style & Naming Conventions

Use Java 11 and standard Spring conventions. Indent Java with four spaces and XML/properties files with four spaces where applicable. Keep package names lowercase under `io.mosip.idrepository.anonymousprofile`.

Name classes by role, such as `DataSourceConfig`, `UinHistoryRepository`, and `AnonymousProfileHelper`. Use `camelCase` for methods and fields, `UPPER_SNAKE_CASE` for constants, and descriptive property keys such as `rebuild.dry-run`.

## Testing Guidelines

Write tests with JUnit 5 through `spring-boot-starter-test`. Place tests under the matching package in `src/test/java` and name them `*Test` or `*IntegrationTest`. Cover database ordering, first-record selection, dry-run behavior, and failure-closed decryption handling. Run `mvn test` before opening a pull request.

## Commit & Pull Request Guidelines

Git history is not available in this checkout, so use concise, imperative commit subjects, for example: `Add UIN history query validation`. Keep commits focused.

Pull requests should explain the behavioral change, list configuration or schema implications, link the related issue when available, and include test evidence. Never include database credentials or production data in configuration, logs, or test fixtures.
