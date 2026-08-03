function fn() {
  // Staging/preprod config. Auth (Ory Kratos session cookie) is injected by the
  // karate-authflow plugin registered on the runner — no auth steps in features.
  var host = karate.properties['staging.host'] || 'https://hormi.isolutions.io';

  var config = {
    // Messenger REST lives under the /messenger edge prefix; endpoints are /api/**.
    baseUrl:   karate.properties['base.url']   || (host + '/messenger'),
    kratosUrl: karate.properties['kratos.url'] || (host + '/.ory/kratos/public')
  };

  // Cloudflare bans default automation user-agents; a browser-like UA is required.
  var UA = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36';
  karate.configure('headers', { 'User-Agent': UA });

  karate.configure('ssl', true);
  karate.configure('connectTimeout', 10000);
  karate.configure('readTimeout', 20000);

  return config;
}
