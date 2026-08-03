@attachment
Feature: Attachments — two-phase presigned upload, confirm→deliver, download (concept §10, ADR-010)

  # Flow: POST upload-url (declared size/type checks, PENDING row, presigned PUT) → client PUTs bytes
  # DIRECTLY to storage → POST confirm (verify object, mark CONFIRMED, emit the attachment as a normal
  # persistent chat message) → GET download-url (membership-gated presigned GET). All endpoints are
  # rate-limited (group "attachments", strictest) and require Ory identity headers.

  Background:
    * url baseUrl
    * def uid = java.util.UUID.randomUUID() + ''
    * def c = 'att-client-' + uid
    * def m = 'att-master-' + uid
    * def master = idHeaders(m, 'M', 'MASTER', m + '@test.com')
    * def client = idHeaders(c, 'C', 'CLIENT', c + '@test.com')
    * def outsider = idHeaders('att-out-' + uid, 'O', 'CLIENT', 'out@test.com')
    # chat is provisioned service-to-service (D4)
    Given path '/api/chats'
    And headers serviceHeaders()
    And request { clientId: '#(c)', masterId: '#(m)', metadata: {} }
    When method POST
    Then status 201
    * def chatId = response.id

  @smoke
  Scenario: happy path — request upload, PUT bytes to storage, confirm (published), then download round-trips
    # phase 1 — presigned upload URL
    Given path '/api/chats', chatId, 'attachments', 'upload-url'
    And headers master
    And request { fileName: 'note.txt', contentType: 'text/plain', sizeBytes: 11 }
    When method POST
    Then status 201
    And match response.attachmentId == '#present'
    And match response.uploadUrl == '#present'
    * def attId = response.attachmentId
    * def uploadUrl = response.uploadUrl
    # phase 2 — upload the bytes DIRECTLY to storage (presigned PUT; content-type is not signed)
    Given url uploadUrl
    And header Content-Type = 'text/plain'
    And request 'hello world'
    When method PUT
    Then status 200
    # phase 3 — confirm → the attachment is emitted as a persistent chat message
    Given url baseUrl
    And path '/api/chats', chatId, 'attachments', attId, 'confirm'
    And headers master
    When method POST
    Then status 202
    And match response.status == 'published'
    # confirm is idempotent — a re-confirm of a delivered attachment is a no-op
    Given url baseUrl
    And path '/api/chats', chatId, 'attachments', attId, 'confirm'
    And headers master
    When method POST
    Then status 200
    And match response.status == 'already-confirmed'
    # the recipient (a participant) can resolve a download URL and fetch the exact bytes
    Given url baseUrl
    And path '/api/chats', chatId, 'attachments', attId, 'download-url'
    And headers client
    When method GET
    Then status 200
    And match response.downloadUrl == '#present'
    * def downloadUrl = response.downloadUrl
    Given url downloadUrl
    When method GET
    Then status 200
    And match response == 'hello world'

  Scenario: confirm before the object was uploaded is a conflict (409 not-uploaded)
    Given path '/api/chats', chatId, 'attachments', 'upload-url'
    And headers master
    And request { fileName: 'x.txt', contentType: 'text/plain', sizeBytes: 5 }
    When method POST
    Then status 201
    * def attId = response.attachmentId
    Given url baseUrl
    And path '/api/chats', chatId, 'attachments', attId, 'confirm'
    And headers master
    When method POST
    Then status 409
    And match response.status == 'not-uploaded'

  Scenario: a declared size over the cap is rejected up front (413)
    Given path '/api/chats', chatId, 'attachments', 'upload-url'
    And headers master
    And request { fileName: 'big.bin', contentType: 'application/octet-stream', sizeBytes: 26214401 }
    When method POST
    Then status 413

  Scenario: fileName is required (400)
    Given path '/api/chats', chatId, 'attachments', 'upload-url'
    And headers master
    And request { contentType: 'text/plain', sizeBytes: 5 }
    When method POST
    Then status 400

  Scenario: a non-positive size is rejected (400)
    Given path '/api/chats', chatId, 'attachments', 'upload-url'
    And headers master
    And request { fileName: 'x.txt', contentType: 'text/plain', sizeBytes: 0 }
    When method POST
    Then status 400

  Scenario: without identity the upload endpoint is unauthorized (401)
    Given path '/api/chats', chatId, 'attachments', 'upload-url'
    And headers noAuthHeaders()
    And request { fileName: 'x.txt', contentType: 'text/plain', sizeBytes: 5 }
    When method POST
    Then status 401

  Scenario: a non-participant cannot request an upload (403)
    Given path '/api/chats', chatId, 'attachments', 'upload-url'
    And headers outsider
    And request { fileName: 'x.txt', contentType: 'text/plain', sizeBytes: 5 }
    When method POST
    Then status 403

  Scenario: a blocked pair cannot upload — uploading is sending (403)
    # client blocks the peer
    Given path '/api/chats', chatId, 'block'
    And headers client
    When method POST
    Then status 204
    # now the master cannot request an upload into the blocked chat
    Given path '/api/chats', chatId, 'attachments', 'upload-url'
    And headers master
    And request { fileName: 'x.txt', contentType: 'text/plain', sizeBytes: 5 }
    When method POST
    Then status 403

  Scenario: a non-participant cannot resolve a download URL (403)
    # first produce a CONFIRMED attachment
    Given path '/api/chats', chatId, 'attachments', 'upload-url'
    And headers master
    And request { fileName: 'note.txt', contentType: 'text/plain', sizeBytes: 3 }
    When method POST
    Then status 201
    * def attId = response.attachmentId
    * def uploadUrl = response.uploadUrl
    Given url uploadUrl
    And header Content-Type = 'text/plain'
    And request 'abc'
    When method PUT
    Then status 200
    Given url baseUrl
    And path '/api/chats', chatId, 'attachments', attId, 'confirm'
    And headers master
    When method POST
    Then status 202
    # an outsider is refused the download URL
    Given url baseUrl
    And path '/api/chats', chatId, 'attachments', attId, 'download-url'
    And headers outsider
    When method GET
    Then status 403
