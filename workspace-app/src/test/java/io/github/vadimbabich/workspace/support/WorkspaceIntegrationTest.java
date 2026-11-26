package io.github.vadimbabich.workspace.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;

/**
 * The one configuration of every integration test: the application in a mock web environment with PostgreSQL from
 * {@link TestDatabase} and the signing keys of {@link TestTokens}. A variant of it is a new Spring context and a new
 * container, so it raises the context budget.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(properties = {
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + TestTokens.ISSUER,
    "spring.security.oauth2.resourceserver.jwt.audiences=" + TestTokens.AUDIENCE})
@AutoConfigureWebTestClient
@Import({TestDatabase.class, TestTokens.class, FailingEndpoints.class})
public @interface WorkspaceIntegrationTest {
}
