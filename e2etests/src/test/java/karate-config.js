function fn() {
  var env = karate.env || 'dev';
  karate.log('karate.env =', env);

  var config = {
    // Messenger REST base + WS base (no /v1 prefix; endpoints under /api and /ws)
    baseUrl: 'http://localhost:8080',
    wsUrl:   'ws://localhost:8080/ws',

    // Test identities (master ↔ client pair + a third party for authz tests)
    masterId:    'test-master-1',
    masterName:  'Test Master 1',
    masterEmail: 'master1@test.com',
    clientId:    'test-client-1',
    clientName:  'Test Client 1',
    clientEmail: 'client1@test.com',
    otherId:     'test-other-9',
    otherName:   'Outsider',
    otherEmail:  'outsider@test.com'
  };

  // Preprod host (single-IP deploy). 'staging' kept as an alias for older invocations.
  if (env === 'preprod' || env === 'staging') {
    config.baseUrl = 'http://preprod.example.com:8080';
    config.wsUrl   = 'ws://preprod.example.com:8080/ws';
  }

  karate.configure('connectTimeout', 2000);
  karate.configure('readTimeout', 5000);

  // Build the Ory Oathkeeper-style identity headers the service trusts.
  // role: 'MASTER' | 'CLIENT'
  config.idHeaders = function (id, name, role, email) {
    return {
      'X-User-Id': id,
      'X-User': name,
      'X-Role': role,
      'X-User-Email': email,
      'Content-Type': 'application/json',
      'Accept': 'application/json'
    };
  };
  config.masterHeaders = function () { return config.idHeaders(config.masterId, config.masterName, 'MASTER', config.masterEmail); };
  config.clientHeaders = function () { return config.idHeaders(config.clientId, config.clientName, 'CLIENT', config.clientEmail); };
  // Admin/service access is a shared-secret X-Admin-Key check (AdminKeyFilter, ADR-006), NOT an
  // X-Role gate — the edge passes the key straight through. Simulate the edge by injecting it.
  config.adminKey = karate.properties['admin.key'] || 'test-admin-key-secret';
  // Chat creation is service-to-service / admin only (D4) — chats are provisioned by the platform,
  // not by end clients. Use this for POST /api/chats (@AdminOnly → needs X-Admin-Key).
  config.serviceHeaders = function () { var h = config.idHeaders('platform-svc', 'Platform Service', 'SERVICE', config.masterEmail); h['X-Admin-Key'] = config.adminKey; return h; };
  // ADMIN console (@AdminOnly): identity headers + the shared admin key.
  config.adminHeaders = function (id, name, email) { var h = config.idHeaders(id, name, 'ADMIN', email); h['X-Admin-Key'] = config.adminKey; return h; };
  config.noAuthHeaders = function () { return { 'Content-Type': 'application/json', 'Accept': 'application/json' }; };

  // NB: Karate 1.4.1 + GraalVM on this JDK cannot build a JsFunction WS handler — WS tests use a
  // null handler and the `listen` keyword + listenResult, collecting a few frames to skip presence.

  return config;
}
