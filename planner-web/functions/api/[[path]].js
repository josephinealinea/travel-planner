/**
 * /api/* → the API on Cloud Run, so the browser only ever sees one origin.
 *
 * That is what keeps auth unchanged: the session and CSRF cookies stay
 * host-only and SameSite=Lax, and js/api.js can still read the CSRF cookie
 * from document.cookie. Everything is passed through untouched — method, body,
 * query, cookies, every Set-Cookie on the way back — apart from the headers
 * below. See docs/deploy.md.
 */
export async function onRequest({ request, env }) {
  const incoming = new URL(request.url);
  const target = new URL(incoming.pathname + incoming.search, env.API_ORIGIN);

  const headers = new Headers(request.headers);
  // fetch sets Host from the target, which is what Cloud Run routes on.
  headers.delete('host');
  // Only this proxy speaks for where the request came from; the API trusts
  // these (server.forward-headers-strategy), so a client's own are dropped.
  for (const name of ['forwarded', 'x-forwarded-port', 'x-forwarded-prefix']) headers.delete(name);
  headers.set('X-Forwarded-Host', incoming.host);
  headers.set('X-Forwarded-Proto', incoming.protocol.slice(0, -1)); // https once deployed
  headers.delete('x-proxy-secret');
  if (env.PROXY_SECRET) headers.set('X-Proxy-Secret', env.PROXY_SECRET);

  return fetch(target, {
    method: request.method,
    headers,
    body: request.method === 'GET' || request.method === 'HEAD' ? undefined : request.body,
    // The API's own redirects go back to the browser as they are.
    redirect: 'manual',
  });
}
