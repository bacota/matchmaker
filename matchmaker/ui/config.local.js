// LOCAL DEVELOPMENT ONLY. Copy to config.js to run against LocalServer:
//
//     cp config.local.js config.js
//
// authMode "header" means there is no authentication: the identity below is asserted in the
// X-External-Id header and believed, exactly as LocalServer does. Never serve this anywhere but
// localhost. Pointing it at a deployed API would not work anyway — the Lambda defaults to
// AUTH_MODE=gateway and the terraform sets it explicitly, so the gateway answers 401.
//
// To be a second player, change localExternalId and open the page in another browser profile.
window.matchmakerConfig = {
  authMode: "header",
  localExternalId: "local-dev-1",
  apiEndpoint: "http://localhost:8080"
};
