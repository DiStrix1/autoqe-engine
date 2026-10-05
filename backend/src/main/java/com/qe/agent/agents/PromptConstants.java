package com.qe.agent.agents;

/**
 * PromptConstants - Centralized, reusable prompt rules for AI test generation
 * and self-healing repair agents.
 *
 * <p>Ensures that mandatory QE rules (Arithmetic Rule, Void Methods, Primitives,
 * NPE Assertions, Generics & Equality, Semantic Consistency, Return Branch Coverage,
 * and Anti-Assertion-Flipping) remain strictly synchronized across initial generation
 * and self-healing repair without copy-paste drift.
 */
public final class PromptConstants {

    private PromptConstants() {
        // Constant utility class
    }

    public static final String SHARED_RULES = """
            - ARITHMETIC RULE: Before writing any numeric assertion, explicitly verify the expected value by tracing the full state: initial value + every mutation applied = final expected value. Example: account starts at $500, $500 is deposited -> assertEquals(1000.0, ...). NEVER guess.
            - VOID METHODS: NEVER use when(mock.voidMethod(...)).thenReturn(...). Void methods on Mockito mocks are no-ops by default. Use verify(mock).voidMethod(...) after the act to assert they were called, or doNothing().when(mock).voidMethod(...) if explicit setup is needed.
            - PRIMITIVES: NEVER assign null to a primitive type (double, int, boolean, long). This is a Java compile error. Use 0.0, 0, false etc. for zero-value edge cases instead.
            - NPE ASSERTIONS: Only use assertThrows(NullPointerException.class, ...) if the target source code explicitly contains Objects.requireNonNull(...) or an explicit null check that throws NullPointerException. If no such guard exists, do NOT fabricate an NPE test.
            - GENERICS & EQUALITY: When testing generic classes like DataRepository<T>, always instantiate concrete types with proper .equals() implementations (e.g. DataRepository<String>, String item = "testData"). NEVER use raw new Object(). Always populate repositories using instance method calls (e.g. repository.save("id1", "item1")).
            - RETURN BRANCH COVERAGE: Inspect all return statements and conditional exits in the target class methods. Write tests for EVERY return path (e.g. both positive/success outputs and negative/guard outputs). Do NOT generate a test suite that only covers early guard exits or negative paths while completely ignoring the positive core algorithm branch.
            - SEMANTIC CONSISTENCY: The test method name, comments, and assertions MUST be strictly consistent. If a method is named '..._Returns1()', it MUST assert assertEquals(1, ...) and use input data that genuinely evaluates to 1. NEVER name a test '...Returns1' while asserting -1.
            - ENCAPSULATION & PRIVATE MEMBERS: Tests MUST NOT access private fields of the class under test directly (e.g. userStore, internal maps, private state). Always invoke public API methods (e.g. underTest.findById(id), underTest.getUserCount()).
            - STANDARD IMPORTS: Always include standard Java imports for collections and utility types you use (import java.util.*;, import java.util.Optional;, import java.util.List;, import java.util.Map;).
            """;

    public static final String ANTI_ASSERTION_FLIPPING_RULE = """
            - ANTI-ASSERTION-FLIPPING RULE: When fixing a failed test assertion (e.g. expected: <1> but was: <-1>), NEVER simply invert or change the expected assertion value to match the failed output if the test was intended to test a positive or specific business logic path.
              Instead:
              a) Trace the source code logic and understand why the test input produced the unexpected value.
              b) Modify the test input data (e.g., provide an input string or arguments that genuinely satisfy the condition) so it legitimately reaches and asserts the intended outcome.
              c) Only change expected X to Y if X was mathematically/logically computed incorrectly in the first place, AND ensure the test method name is updated to accurately describe the new behavior.
            """;
}
