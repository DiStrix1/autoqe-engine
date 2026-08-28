package com.qe.agent.agents;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * RetrievalAgent - gathers structural and semantic code context from both
 * the Neo4j graph database and the PostgreSQL pgvector store using the
 * registered {@code GraphQueryTool} and {@code VectorQueryTool}.
 *
 * <p>This agent is configured with {@code .tools(graphQueryTool, vectorQueryTool)}
 * in {@code OllamaConfig.retrievalAgent()}, enabling the LLM to invoke those
 * tools autonomously during response generation.
 *
 * <p>Architecture role (PROJECT_SPECIFICATION.md Section 4.2):
 * "Queries code_embeddings using cosine similarity to fetch semantically
 * relevant utility code."
 */
public interface RetrievalAgent {

    @SystemMessage("""
            You are an expert code context retrieval agent with access to two tools:
            
            - GraphQueryTool: queries the Neo4j graph database for class structure, method
              signatures, and method call dependencies (HAS_METHOD / CALLS relationships).
            - VectorQueryTool: queries the PostgreSQL pgvector store using semantic cosine
              similarity to find methods with related functionality.
            
            MANDATORY retrieval sequence you MUST follow for every request:
            1. Call GraphQueryTool.getClassMethods() with the target class name to retrieve all method IDs and signatures.
            2. For each significant method, call GraphQueryTool.getMethodBody() to read the implementation.
            3. Call GraphQueryTool.getMethodCallDependencies() on key methods to map their outbound call graph.
            4. Call VectorQueryTool.findSimilarMethods() with 2-3 relevant query phrases to find semantically related utility code.
            5. Synthesise everything into a comprehensive context report.
            
            Your final output MUST be a structured context report containing:
            - CLASS STRUCTURE: all method names, return types, and parameters.
            - METHOD BODIES: source code of the most important methods.
            - CALL DEPENDENCIES: what each method calls internally.
            - SIMILAR CODE: semantically related methods from the vector store.
            - MOCKING TARGETS: any injected dependencies inferred from the method calls.
            
            If a tool returns an error or "not found", note it and continue with the remaining steps.
            """)
    @UserMessage("""
            Gather all structural and semantic code context required to generate unit tests for:
            
            Target Class: {{className}}
            
            Test Plan (use this to guide your retrieval queries):
            {{testPlan}}
            
            Raw Source Code (use this as a grounding anchor for the class structure):
            {{fileContent}}
            """)
    String gatherCodeContext(
            @V("className") String className,
            @V("testPlan") String testPlan,
            @V("fileContent") String fileContent
    );
}
