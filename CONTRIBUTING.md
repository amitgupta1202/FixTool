# Contributing to FixTool

Thank you for your interest in contributing to FixTool! This document provides guidelines and best practices for contributing to this project.

## Table of Contents

- [Getting Started](#getting-started)
- [Development Workflow](#development-workflow)
- [Coding Standards](#coding-standards)
- [Testing Guidelines](#testing-guidelines)
- [Documentation](#documentation)
- [Pull Request Process](#pull-request-process)
- [AI-Assisted Development](#ai-assisted-development)

---

## Getting Started

### Prerequisites

Before contributing, ensure you have:
- JDK 17 or higher
- Git
- A GitHub account
- (Optional) IntelliJ IDEA or another Kotlin IDE

### Setting Up Your Development Environment

1. **Fork the repository** on GitHub
2. **Clone your fork** locally:
   ```bash
   git clone https://github.com/YOUR-USERNAME/FixTool.git
   cd FixTool
   ```
3. **Add upstream remote**:
   ```bash
   git remote add upstream https://github.com/ORIGINAL-OWNER/FixTool.git
   ```
4. **Verify the build works**:
   ```bash
   ./gradlew build
   ```

---

## Development Workflow

### 1. Create a Feature Branch

Always create a new branch for your work. Never commit directly to `main`.

**Branch naming conventions:**
- `feature/description` - New features
- `fix/description` - Bug fixes
- `refactor/description` - Code refactoring
- `docs/description` - Documentation updates
- `test/description` - Adding or updating tests

Example:
```bash
git checkout -b feature/add-session-timeout
```

### 2. Make Your Changes

- Write clean, readable code
- Follow the project's coding standards (see below)
- Keep commits focused and atomic
- Write descriptive commit messages

### 3. Write Tests

**All code changes must include tests.** See [Testing Guidelines](#testing-guidelines) below.

### 4. Run Quality Checks

Before committing, ensure:

```bash
# Format code
./gradlew ktlintFormat

# Run static analysis
./gradlew detekt

# Run tests
./gradlew jvmTest

# Full build (includes all checks)
./gradlew build
```

### 5. Commit Your Changes

Write clear, descriptive commit messages:

```
feat: Add automatic reconnection with exponential backoff

Implements retry logic when FIX connection is lost. Uses exponential
backoff starting at 1s, doubling up to 30s maximum.

- Add RetryStrategy class
- Update FixConnectionManager with retry logic
- Add retry configuration to UI
```

**What NOT to include in commit messages:**
- ❌ "All tests passing"
- ❌ "Generated with AI"
- ❌ Test counts or tool mentions

### 6. Push and Create Pull Request

```bash
git push origin feature/your-feature-name
```

Then create a Pull Request on GitHub.

---

## Coding Standards

### Code Style

- Follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use ktlint for automatic formatting: `./gradlew ktlintFormat`
- Configure your IDE to use the project's EditorConfig

### Code Organization

```
composeApp/src/
├── jvmMain/kotlin/com/knapsack/fixtool/
│   ├── model/           # Data models, business entities
│   ├── service/         # Business logic, external integrations
│   ├── ui/              # Compose UI components
│   ├── viewmodel/       # UI state management
│   └── util/            # Utility functions
└── jvmTest/kotlin/com/knapsack/fixtool/
    ├── model/
    ├── service/
    ├── ui/
    └── integration/     # Integration and E2E tests
```

### Best Practices

✅ **Do:**
- Use meaningful variable and function names
- Keep functions small and focused (< 60 lines)
- Prefer immutability (use `val` over `var`)
- Use data classes for models
- Document public APIs with KDoc
- Handle errors gracefully

❌ **Don't:**
- Leave commented-out code
- Use magic numbers (use named constants)
- Create god classes or functions
- Ignore compiler warnings
- Commit debug logging statements

---

## Testing Guidelines

### Test Philosophy

**Prefer integration tests over unit tests** when practical. Integration tests verify actual behavior users experience, while unit tests can become brittle when testing implementation details.

### Test Priority

1. **Integration Tests** (Highest Priority)
   - Test multiple components working together
   - Verify realistic user scenarios
   - Example: Profile creation → Connection → Message sending

2. **E2E Tests** (High Priority for critical paths)
   - Test complete user workflows
   - Cover happy path and error scenarios
   - Example: Full connection lifecycle

3. **Unit Tests** (When Appropriate)
   - Complex business logic
   - Edge cases and boundary conditions
   - Parser/validation functions

### Writing Good Tests

✅ **Good Test Characteristics:**
```kotlin
@Test
fun testSessionRoutesMessagesToCorrectConnection() {
    // Arrange: Clear setup with minimal dependencies
    val session1 = createSession("DEV1")
    val session2 = createSession("LOCAL")

    // Act: Single clear action
    session1.sendMessage(testMessage)

    // Assert: Verify behavior, not implementation
    assertMessageSentFrom(session1, testMessage)
    assertMessageNotSentFrom(session2, testMessage)
}
```

❌ **Avoid:**
- Excessive mocking (5+ mocks = consider integration test instead)
- Testing implementation details (internal state, private methods)
- Test interdependencies
- Complex async/threading logic

### Test Coverage

Aim to cover:
- ✅ Happy path (normal operation)
- ✅ Edge cases (empty inputs, nulls, boundaries)
- ✅ Error handling (network failures, invalid data)
- ✅ Integration points (component interactions)
- ✅ Backward compatibility (legacy data/configs)

### Running Tests

```bash
# All tests
./gradlew jvmTest --no-daemon

# Specific test class
./gradlew jvmTest --tests "ConnectionPanelTest" --no-daemon

# Specific test method
./gradlew jvmTest --tests "ConnectionPanelTest.testSaveProfile" --no-daemon

# With detailed output
./gradlew jvmTest --no-daemon --info
```

---

## Documentation

### Code Documentation

- Add KDoc comments to public APIs
- Explain WHY, not just WHAT (code shows what)
- Keep comments up to date with code changes
- Remove outdated or misleading comments

Example:
```kotlin
/**
 * Connects to a FIX server with automatic retry on failure.
 *
 * Uses exponential backoff to avoid overwhelming the server during
 * connection issues. Retries indefinitely until explicitly stopped.
 *
 * @param config Connection configuration including credentials
 * @throws ConnectionException if initial connection fails validation
 */
fun connect(config: FixConnectionConfig)
```

### README Updates

Update README.md when changes affect:
- Feature list
- Setup/installation instructions
- Configuration options
- Dependencies
- Breaking changes (add migration guide)

---

## Pull Request Process

### Before Submitting

- [ ] All tests pass locally
- [ ] Code is formatted (`./gradlew ktlintFormat`)
- [ ] Static analysis passes (`./gradlew detekt`)
- [ ] Documentation updated
- [ ] Unused code removed
- [ ] Commit messages follow guidelines

### PR Description Template

```markdown
## Description
Brief description of what this PR does.

## Type of Change
- [ ] Bug fix
- [ ] New feature
- [ ] Breaking change
- [ ] Documentation update

## Testing
Describe how you tested these changes.

## Checklist
- [ ] Tests added/updated
- [ ] Documentation updated
- [ ] No breaking changes (or migration guide provided)
- [ ] Code follows project style guidelines
```

### Review Process

1. Automated checks must pass (tests, linting, build)
2. At least one maintainer approval required
3. Address review feedback by pushing new commits
4. Maintainer will merge when approved

---

## AI-Assisted Development

If you're using AI tools (Claude Code, GitHub Copilot, ChatGPT, etc.) to assist with development, please follow the comprehensive guidelines in [`.ai-guidelines.md`](.ai-guidelines.md).

### Key Points for AI-Assisted Contributions

✅ **Do:**
- Use AI to understand code and suggest improvements
- Have AI help write tests
- Use AI for code review and quality checks
- Use AI to draft documentation

❌ **Don't:**
- Let AI commit directly to main branch
- Include AI tool mentions in commit messages
- Commit AI-generated code without review
- Skip testing because "AI wrote the code"
- Include organization secrets in prompts

### AI Guidelines Summary

1. **Branch Management**: Always use feature branches
2. **Testing**: Write integration/E2E tests for all changes
3. **Test Data**: Use only generic test data (no secrets)
4. **Commit Messages**: Clear, professional messages (no AI mentions)
5. **Documentation**: Keep README and docs up to date
6. **Code Cleanup**: Remove unused code, debug statements

See [`.ai-guidelines.md`](.ai-guidelines.md) for complete details.

---

## Security

### What NOT to Commit

Never commit:
- ❌ API keys, passwords, tokens, certificates
- ❌ Connection credentials
- ❌ Organization-specific data
- ❌ Email addresses (use generic in tests)
- ❌ Production hostnames or IPs

### Test Data Standards

Use only generic test data:
- **Emails**: `test@example.com`, `user@test.com`
- **Hosts**: `localhost`, `test.example.com`
- **SenderCompID**: `SENDER_CLIENT`, `BUYER_FIRM`, `SELLER_FIRM`
- **TargetCompID**: `TARGET_SERVER`, `EXCHANGE_TARGET`
- **SessionQualifier**: `DEV1`, `LOCAL`, `QA1`, `STAGING`

### User Configuration

User-specific configuration stored in `~/.fixtool/` is:
- ✅ Not tracked by git (.gitignore'd)
- ✅ Safe to contain real credentials
- ✅ Automatically created by application

---

## Getting Help

- **Documentation**: Check [README.md](README.md) and [TEST_SUGGESTIONS.md](TEST_SUGGESTIONS.md)
- **Issues**: Search existing issues before creating new ones
- **Discussions**: Use GitHub Discussions for questions
- **Code Review**: Request review when unsure about an approach

---

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0, the same license as the project.

---

Thank you for contributing to FixTool! 🎉
