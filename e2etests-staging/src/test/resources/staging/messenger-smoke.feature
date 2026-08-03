@staging @smoke
Feature: Messenger Service — staging smoke (auth via karate-authflow Kratos session)

  # No auth steps: the karate-authflow plugin injects the Ory Kratos session cookie
  # on every request. Verifies the deployed Messenger (behind Oathkeeper, reading
  # identities from the IDS over gRPC) is reachable and behaves for an authed user.

  Background:
    * url baseUrl

  Scenario: Identity is a resolvable Kratos session
    Given url kratosUrl + '/sessions/whoami'
    When method GET
    Then status 200
    And match response.identity.traits.email == '#string'

  Scenario: Authenticated user can list their chats
    Given path 'api', 'chats'
    When method GET
    Then status 200
    And match response == '#[]'

  Scenario: Presence endpoint is reachable
    Given path 'api', 'presence'
    When method GET
    Then status 200
    And match response == '#[]'

  Scenario: Legacy /api/history is removed (history is per-chat, watermark-floored)
    # The un-watermarked GET /api/history was dropped — conversation history is read only
    # through GET /api/chats/{id}/messages (delete-watermark honoured). Guard the removal.
    Given path 'api', 'history'
    When method GET
    Then status 404
