package com.qe.agent.agents;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * GeneratorAgent - produces complete, compilable JUnit 5 test classes from
 * retrieved code context, and repairs failing tests during the self-healing loop.
 *
 * <p>Contains two service methods:
 * <ol>
 *   <li>{@link #generateTests} - initial test generation from context.</li>
 *   <li>{@link #repairTests} - self-healing repair using Maven failure stack traces.</li>
 * </ol>
 *
 * <p>Architecture role (PROJECT_SPECIFICATION.md Sections 4.2 and 5):
 * "Prompts LLM with extracted context and generates clean JUnit 5 test class code."
 * "Send prompt back to GeneratorAgent ... Fix the compilation or assertion error."
 */
public interface GeneratorAgent {

    // -------------------------------------------------------------------------
    // Initial test generation
    // -------------------------------------------------------------------------

    @SystemMessage("""
            You are an expert Java unit test writer specialising in JUnit 5 and Mockito.
            
            MANDATORY rules you MUST follow without exception:
            1. Output a SINGLE complete, standalone, compilable Java test class. NEVER use placeholder comments like '// ...' or '// rest of methods'. Write full implementations for every method.
            2. Include the correct package statement derived from the target class package.
            3. Name the test class <TargetClassName>Test (e.g. UserServiceTest).
            4. If using @ExtendWith(MockitoExtension.class), you MUST include EXACT imports:
               import org.junit.jupiter.api.extension.ExtendWith;
               import org.mockito.junit.jupiter.MockitoExtension;
            5. For assertions, use:
               import static org.junit.jupiter.api.Assertions.*;
            6. For Mockito stubbing, use:
               import static org.mockito.Mockito.*;
            7. Write @Test methods covering: happy path, null/empty inputs, exceptions thrown.
            8. Return ONLY the raw Java code inside a SINGLE ```java ... ``` code block. Zero prose outside.
            9. ARITHMETIC RULE: Before writing any numeric assertion, explicitly verify the expected value
               by tracing the full state: initial value + every mutation applied = final expected value.
               Example: account starts at $500, $500 is deposited → assertEquals(1000.0, ...). NEVER guess.
            10. VOID METHODS: NEVER use when(mock.voidMethod(...)).thenReturn(...). Void methods on Mockito
                mocks are no-ops by default. Use verify(mock).voidMethod(...) after the act to assert they
                were called, or doNothing().when(mock).voidMethod(...) if explicit setup is needed.
            11. PRIMITIVES: NEVER assign null to a primitive type (double, int, boolean, long). This is a
                Java compile error. Use 0.0, 0, false etc. for zero-value edge cases instead.
            12. NPE ASSERTIONS: Only use assertThrows(NullPointerException.class, ...) if the target source
                code explicitly contains Objects.requireNonNull(...) or an explicit null check that throws
                NullPointerException. If no such guard exists, do NOT fabricate an NPE test.
            """)
    @UserMessage("""
            Generate a complete JUnit 5 test class for the following target:
            
            Target Class Name: {{className}}
            Testing Strategy:  {{strategy}}
            
            USER-SPECIFIED QE DIRECTIVES:
            {{directives}}
            
            Collected Code Context (class structure, method bodies, call graph, similar methods):
            {{context}}
            """)
    String generateTests(
            @V("className") String className,
            @V("context") String context,
            @V("strategy") String strategy,
            @V("directives") String directives
    );

    // -------------------------------------------------------------------------
    // Self-healing repair (PROJECT_SPECIFICATION.md Section 5, Step 5)
    // -------------------------------------------------------------------------

    @SystemMessage("""
            You are an expert Java debugger and test repair specialist.
            
            You will receive:
            1. The original Java class source code (the class under test).
            2. A failing JUnit 5 test class that does NOT compile or has failing assertions.
            3. The exact Maven build failure stack trace (stdout + stderr combined).
            
            MANDATORY repair rules:
            1. Analyse the stack trace carefully to identify the EXACT root cause.
            2. Fix ALL compilation errors (missing imports like org.junit.jupiter.api.extension.ExtendWith or org.mockito.junit.jupiter.MockitoExtension, wrong method signatures, missing assertThrows).
            3. Fix ALL assertion errors.
            4. Write the ENTIRE Java test class in full. NEVER output placeholder comments like '// ...' or '// rest of code'.
            5. Return ONLY the corrected Java code inside a SINGLE ```java ... ``` code block. Zero prose outside.
            6. ARITHMETIC RULE: When fixing assertion errors, re-derive every expected numeric value from
               scratch by tracing: initial state + all mutations applied = expected result.
               Example: account starts at $500, $500 transferred in → assertEquals(1000.0, ...). NEVER copy
               the wrong value from the failing test — re-compute it from the source code logic.
            7. VOID METHODS: NEVER use when(mock.voidMethod(...)).thenReturn(...). Void methods on Mockito
               mocks are no-ops by default. Fix by removing the stub and using verify() after the act.
            8. PRIMITIVES: NEVER assign null to a primitive type (double, int, boolean, long). Fix by
               using a valid literal (e.g. 0.0, -1.0) or removing the test if it makes no sense.
            9. NPE ASSERTIONS: Only keep assertThrows(NullPointerException.class, ...) if the source code
               explicitly calls Objects.requireNonNull(...) or has a null-guard that throws NPE. Otherwise,
               remove or replace with a test that reflects the actual observable behaviour.
            """)
    @UserMessage("""
            Fix the failing JUnit 5 test suite based on the Maven build output below.
            
            === ORIGINAL CLASS SOURCE CODE ===
            {{originalCode}}
            
            === FAILING TEST CODE ===
            {{failingTestCode}}
            
            === MAVEN FAILURE STACK TRACE ===
            {{errorTrace}}
            """)
    String repairTests(
            @V("originalCode") String originalCode,
            @V("failingTestCode") String failingTestCode,
            @V("errorTrace") String errorTrace
    );
}
