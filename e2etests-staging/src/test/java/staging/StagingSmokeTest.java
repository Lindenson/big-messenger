package staging;

import com.intuit.karate.Results;
import com.intuit.karate.Runner;
import io.github.lindenson.karate.authflow.session.KratosSessionStrategy;
import io.github.lindenson.karate.authflow.spi.KarateAuth;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Staging smoke runner for the Messenger service.
 *
 * <p>Auth is applied transparently by {@code karate-authflow} ({@link KratosSessionStrategy}
 * injects the {@code ory_kratos_session} cookie). Credentials are supplied at runtime:
 * <pre>
 *   mvn -f e2etests-staging/pom.xml test \
 *       -Dkratos.email=user@example.com -Dkratos.password=secret
 * </pre>
 */
class StagingSmokeTest {

    private static final String KRATOS_URL = System.getProperty(
            "kratos.url", "https://hormi.isolutions.io/.ory/kratos/public");

    @Test
    void messengerSmoke() {
        String email = System.getProperty("kratos.email");
        String password = System.getProperty("kratos.password");
        assertNotNull(email, "provide -Dkratos.email=<identity email>");
        assertNotNull(password, "provide -Dkratos.password=<password>");

        Results results = KarateAuth
                .register(Runner.path("classpath:staging/messenger-smoke.feature"),
                        new KratosSessionStrategy(KRATOS_URL, email, password))
                .outputCucumberJson(true)
                .parallel(1);

        assertEquals(0, results.getFailCount(), results.getErrorMessages());
    }
}
