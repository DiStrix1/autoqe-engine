package com.qe.agent.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * GraphQueryTool - LangChain4j tool bean for querying the Neo4j graph database.
 *
 * <p>Implements the graph-query side of the hybrid RAG pipeline
 * (PROJECT_SPECIFICATION.md Section 2 and 3.1). The {@link Driver} bean is
 * auto-configured by Spring Boot from {@code spring.neo4j.*} application properties.
 *
 * <p>Each public method is annotated with {@code @Tool} so the LangChain4j
 * {@link dev.langchain4j.service.AiServices} framework exposes it as a callable
 * tool to the {@code RetrievalAgent}.
 */
@Slf4j
@Component
public class GraphQueryTool {

    private final Driver neo4jDriver;

    public GraphQueryTool(Driver neo4jDriver) {
        this.neo4jDriver = neo4jDriver;
    }

    // -------------------------------------------------------------------------
    // Tools
    // -------------------------------------------------------------------------

    @Tool("Retrieve all methods defined in a Java class from the Neo4j graph database. "
            + "Provide the fully qualified class name (e.g. com.example.UserService). "
            + "Returns a list of method IDs, names, return types, and signatures that belong to that class.")
    public String getClassMethods(
            @P("Fully qualified class name, e.g. com.example.UserService") String fullyQualifiedClassName) {

        log.debug("[GraphQueryTool] getClassMethods({})", fullyQualifiedClassName);
        try (Session session = neo4jDriver.session()) {
            var result = session.run(
                    "MATCH (c:CLASS {fullName: $fullName})-[:HAS_METHOD]->(m:METHOD) "
                            + "RETURN m.id AS id, m.name AS name, "
                            + "m.returnType AS returnType, m.signature AS signature",
                    Map.of("fullName", fullyQualifiedClassName)
            );

            List<String> methods = result.list(record -> String.format(
                    "id=%s | name=%s | returnType=%s | signature=%s",
                    record.get("id").asString("?"),
                    record.get("name").asString("?"),
                    record.get("returnType").asString("void"),
                    record.get("signature").asString("?")
            ));

            if (methods.isEmpty()) {
                return "No methods found in Neo4j for class: " + fullyQualifiedClassName
                        + ". Ensure the codebase has been ingested via the parser-service.";
            }
            return "Methods in " + fullyQualifiedClassName + ":\n" + String.join("\n", methods);

        } catch (Exception ex) {
            log.error("[GraphQueryTool] getClassMethods failed: {}", ex.getMessage(), ex);
            return "Error querying Neo4j: " + ex.getMessage();
        }
    }

    @Tool("Retrieve the direct method call dependencies of a given method from Neo4j. "
            + "Provide the method ID in the format ClassName#methodName. "
            + "Returns a list of all methods that this method directly invokes.")
    public String getMethodCallDependencies(
            @P("Method ID in the format ClassName#methodName") String methodId) {

        log.debug("[GraphQueryTool] getMethodCallDependencies({})", methodId);
        try (Session session = neo4jDriver.session()) {
            var result = session.run(
                    "MATCH (caller:METHOD {id: $id})-[:CALLS]->(callee:METHOD) "
                            + "RETURN callee.id AS id, callee.name AS name, callee.signature AS signature",
                    Map.of("id", methodId)
            );

            List<String> callees = result.list(record -> String.format(
                    "id=%s | name=%s | signature=%s",
                    record.get("id").asString("?"),
                    record.get("name").asString("?"),
                    record.get("signature").asString("?")
            ));

            if (callees.isEmpty()) {
                return "No outbound call dependencies found for method: " + methodId;
            }
            return "Methods called by " + methodId + ":\n" + String.join("\n", callees);

        } catch (Exception ex) {
            log.error("[GraphQueryTool] getMethodCallDependencies failed: {}", ex.getMessage(), ex);
            return "Error querying Neo4j: " + ex.getMessage();
        }
    }

    @Tool("Retrieve the raw source body of a specific Java method from Neo4j by its method ID. "
            + "Provide the method ID in the format ClassName#methodName. "
            + "Returns the stored method body text as extracted by the AST parser.")
    public String getMethodBody(
            @P("Method ID in the format ClassName#methodName") String methodId) {

        log.debug("[GraphQueryTool] getMethodBody({})", methodId);
        try (Session session = neo4jDriver.session()) {
            var result = session.run(
                    "MATCH (m:METHOD {id: $id}) RETURN m.name AS name, m.body AS body",
                    Map.of("id", methodId)
            );

            if (!result.hasNext()) {
                return "Method not found in Neo4j: " + methodId;
            }
            var record = result.single();
            return String.format("Method [%s]:\n%s",
                    record.get("name").asString("?"),
                    record.get("body").asString("// body not available"));

        } catch (Exception ex) {
            log.error("[GraphQueryTool] getMethodBody failed: {}", ex.getMessage(), ex);
            return "Error querying Neo4j: " + ex.getMessage();
        }
    }

    /**
     * Improvement #M — Reverse graph traversal.
     *
     * <p>Finds all METHOD nodes in the parsed corpus that have a {@code [:CALLS]}
     * relationship pointing TO the given method. This is essential for understanding
     * which higher-level methods depend on the method under test, and for generating
     * integration-style tests that need to mock those callers.
     */
    @Tool("Find all methods that call a specific Java method (reverse dependency traversal). "
            + "Provide the method ID in the format ClassName#methodName. "
            + "Returns a list of caller methods with their class, name, and signature. "
            + "Use this to understand what higher-level methods depend on the method under test.")
    public String getMethodCallers(
            @P("Method ID in the format ClassName#methodName") String methodId) {

        log.debug("[GraphQueryTool] getMethodCallers({})", methodId);
        try (Session session = neo4jDriver.session()) {
            var result = session.run(
                    "MATCH (caller:METHOD)-[:CALLS]->(m:METHOD {id: $id}) "
                            + "RETURN caller.id AS id, caller.name AS name, caller.signature AS signature",
                    Map.of("id", methodId)
            );

            List<String> callers = result.list(record -> String.format(
                    "id=%s | name=%s | signature=%s",
                    record.get("id").asString("?"),
                    record.get("name").asString("?"),
                    record.get("signature").asString("?")
            ));

            if (callers.isEmpty()) {
                return "No callers found for method: " + methodId
                        + ". This method may be an entry point or was not resolved during ingestion.";
            }
            return "Methods that call " + methodId + ":\n" + String.join("\n", callers);

        } catch (Exception ex) {
            log.error("[GraphQueryTool] getMethodCallers failed: {}", ex.getMessage(), ex);
            return "Error querying Neo4j: " + ex.getMessage();
        }
    }
}
