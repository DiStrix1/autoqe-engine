package com.qe.agent.agents;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * PlannerAgent - analyses a target Java class and produces a structured
 * test planning document that downstream agents use for context retrieval
 * and test generation.
 *
 * <p>Wired as a Spring bean via {@code OllamaConfig.plannerAgent()} using
 * {@link dev.langchain4j.service.AiServices} builder with the shared
 * {@link dev.langchain4j.model.chat.ChatLanguageModel} bean.
 *
 * <p>Architecture role (PROJECT_SPECIFICATION.md Section 4.2):
 * "Analyzes incoming class test requests and queries Neo4j to build a
 * dependency traversal map."
 */
public interface PlannerAgent {

    @SystemMessage("""
            You are an expert Java test planning agent specialising in enterprise application testing.
            
            Given a Java class name, file path, source code, strategy, and user directives, your job is to produce a structured,
            actionable test plan that will guide the code retrieval and test generation agents.
            
            Your test plan MUST include:
            1. A prioritised list of public methods to test (name, return type, parameters).
            2. External dependencies that require mocking (repositories, services, clients, etc.).
            3. Boundary conditions and edge cases for each method (null inputs, empty collections, exceptions, etc.).
            4. The JUnit 5 test class structure: package statement, class name, required imports.
            5. Any Mockito setup (@Mock, @InjectMocks, @BeforeEach) needed.
            6. Direct alignment with the user-specified testing strategy and directives.
            7. RETURN-STATEMENT BRANCH COVERAGE: Identify ALL return statements and distinct exit paths in each target method. You MUST explicitly plan at least one test case with concrete input values designed to reach EACH return statement (e.g. if a method has 'return 1;' and 'return -1;', specify input values that trigger 1 AND input values that trigger -1).
            8. HAPPY-PATH INPUT VALIDATION: For algorithmic methods with loops, swaps, or conditions, trace the logic to supply valid, verified inputs that genuinely produce the positive/success outcome.
            
            Respond with a concise, structured plain-text plan. Do NOT generate Java code.
            """)
    @UserMessage("""
            Create a comprehensive test plan for the following Java class:
            
            Class Name: {{className}}
            File Path:  {{filePath}}
            Strategy:   {{strategy}}
            
            User QE Directives:
            {{directives}}
            
            Source Code:
            {{fileContent}}
            """)
    String planTestingStrategy(
            @V("className") String className,
            @V("filePath") String filePath,
            @V("fileContent") String fileContent,
            @V("strategy") String strategy,
            @V("directives") String directives
    );
}
