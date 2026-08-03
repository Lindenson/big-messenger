package hormiga;

import com.intuit.karate.junit5.Karate;

/**
 * Runs the Messenger REST acceptance suite (Karate OSS 1.4.1). WebSocket scenarios are NOT here —
 * they live in the JDK-WebSocket JUnit suite (hormiga.ws.*WsTest), because Karate 2.x paywalls
 * WebSocket and 1.4.1 can't build a JS handler on this JDK.
 * Run: mvn test -Dkarate.env=dev   (app + infra must be up — see docker-compose.yml).
 */
class MessengerRunner {

    @Karate.Test
    Karate all() {
        // Always exclude spec-only (@wip) and the retired in-feature WebSocket scenarios (@ws).
        // Optional positive filter: -Dkarate.tags=@smoke (or @chat/@auth/…) narrows to a group.
        var tags = new java.util.ArrayList<>(java.util.List.of("~@wip", "~@ws"));
        String group = System.getProperty("karate.tags", "").trim();
        if (!group.isEmpty()) {
            tags.add(group);
        }
        return Karate.run("classpath:karate").tags(tags.toArray(new String[0])).relativeTo(getClass());
    }
}
