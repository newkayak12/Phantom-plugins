---
name: writing-tests
description: Guidelines for writing tests in IntelliJ codebase. Use when creating new test classes or test methods.
---

# Writing Tests

> Phantom-plugins: plugin tests live in `<folder>/src/test/kotlin` and follow the plugin's existing framework (most use JUnit 4 fixtures, see `CLAUDE.md` → Tests). Use JUnit 5 below only where the plugin's `build.gradle.kts` already sets it up. The `community/...` paths refer to the IntelliJ sources in the Phantom repository.

Guidelines for writing tests in IntelliJ IDEA codebase.

For examples, see `community/platform/testFramework/junit5/test/showcase/`.

## Prefer JUnit 5 over JUnit 4

Use JUnit 5 with `@TestApplication` annotation instead of extending `LightJavaCodeInsightFixtureTestCase`.

**Why JUnit 5:**
- **Faster**: No class hierarchy overhead, shared fixtures via companion objects
- **Cleaner**: Annotations (`@TestDisposable`, `@RegistryKey`) instead of manual setup/teardown
- **Flexible**: Mix EDT and non-EDT tests in one class, parameterized tests, nested tests
- **Better isolation**: Each test gets fresh disposables automatically

## Shared Fixtures Pattern

Use companion object fixtures shared between all tests:

```kotlin
@TestApplication
internal class MyTest {
  companion object {
    private val projectFixture = projectFixture()
    private val moduleFixture = projectFixture.moduleFixture("src")
  }

  private val project get() = projectFixture.get()
  private val module get() = moduleFixture.get()
}
```

## Lifecycle Hooks

Use JUnit 5 lifecycle annotations for setup and teardown:

```kotlin
@TestApplication
internal class MyTest {
  companion object {
    @JvmStatic
    @BeforeAll
    fun setUpClass() {
      // Once before all tests in class
    }

    @JvmStatic
    @AfterAll
    fun tearDownClass() {
      // Once after all tests in class
    }
  }

  @BeforeEach
  fun setUp() {
    // Before each test method
  }

  @AfterEach
  fun tearDown() {
    // After each test method
  }
}
```

**Note:** Prefer `@TestDisposable` over manual `@AfterEach` cleanup for resources.

## Test Disposables

Use `@TestDisposable` annotation to inject test-scoped disposables (created before each test, disposed after):

```kotlin
@TestDisposable
lateinit var disposable: Disposable

// Or as parameter
@Test
fun myTest(@TestDisposable disposable: Disposable) { ... }
```

## Registry Values in Tests

Use `@RegistryKey` annotation instead of `Registry.get().setValue()`:

```kotlin
@Test
@RegistryKey(key = "my.registry.key", value = "true")
fun testWithRegistryEnabled() { ... }
```

## System Properties in Tests

Use `@SystemProperty` annotation instead of `System.setProperty()`:

```kotlin
@Test
@SystemProperty(propertyKey = "my.property", propertyValue = "value")
fun testWithSystemProperty() { ... }
```

## Running Tests

```bash
./gradlew :<folder>:test --tests 'package.MyTest'
```
