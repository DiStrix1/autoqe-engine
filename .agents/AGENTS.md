# Project Rules

## Java Test Import Rules
- ALWAYS include `import static org.junit.jupiter.api.Assertions.*;` at the top of every test file.
- ALWAYS include `import static org.mockito.Mockito.*;` at the top of every test file if Mockito or mocks are used.

## Arithmetic Rule for Test Assertions
- ALWAYS double-check all math when calculating expected values in assertions.
- Always compute expected values as: `initial state + mutation = expected result` (e.g., Initial $500 + Deposited $500 = Expected $1000).
- Never assume a post-operation balance without tracing every prior state change.

## Mockito & Java Syntax Rules
- VOID METHODS: NEVER use `when(mock.voidMethod(...)).thenReturn(...)`. Void methods on Mockito mocks are no-ops by default — do not stub them with `when()`. Use `doNothing().when(mock).voidMethod(...)` only if you need to explicitly verify the call, or just use `verify()` after the act.
- PRIMITIVES: NEVER assign `null` to primitive types (`double`, `int`, `boolean`, `long`). Primitives cannot be null in Java; this is a compile error.
- EXCEPTIONS: Only use `assertThrows(NullPointerException.class, ...)` if the target source code explicitly contains `Objects.requireNonNull(...)` or an explicit null check that throws NPE. Do not fabricate NPE tests when the source has no such guard.

## Generics & Object Equality Rules
- CONCRETE TYPES: When testing generic classes like `DataRepository<T>`, always instantiate concrete types with proper `.equals()` value implementations (e.g., `DataRepository<String>`, `String item = "testData"`). NEVER use raw `new Object()`.
- INSTANCE POPULATION: Always populate generic repositories using instance method calls (e.g., `repository.save("id1", "item1")`). Never declare separate local `Map` or `List` variables without calling the target repository's `.save()` method.
