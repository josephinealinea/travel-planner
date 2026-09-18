/**
 * Serves published trip pages straight from R2, so a stranger opening a shared
 * link never wakes the API.
 *
 * This is PublicPageController (planner-api, publish/web) in another runtime,
 * and it mirrors it route for route — a change to one is a change to both:
 *
 *   /p/<slug>  and  /p/<slug>/            published/<slug>/index.html
 *   /p/<slug>/trip.json                   published/<slug>/trip.json
 *   /p/<slug>/m/<member>  (slash or not)  published/<slug>/m/<member>/index.html
 *   /p/<slug>/m/<member>/trip.json        published/<slug>/m/<member>/trip.json
 *
 * Anything else, or a file that is not there, is a 404.
 *
 * Only ever `published/`. The bucket also holds `pending/` — pages whose
 * publish request the owner has not decided — and no path through here can
 * name it: the key is assembled from a fixed prefix and segments that have
 * each passed the same rule as Slugs.requireSafe. The members-only preview of a
 * staged page still goes through the API.
 *
 * One difference from the controller, deliberately: a segment that fails the
 * rule is a 404 here rather than the API's 400. To a public reader there is no
 * useful distinction, and a 404 says nothing about what the rule is.
 *
 * `PAGES_BUCKET` is the R2 binding, declared in wrangler.toml.
 */

/** Slugs.requireSafe: lower-case letters, digits and hyphens, not starting with a hyphen. */
const SAFE = /^[a-z0-9][a-z0-9-]*$/;

const FILES = {
  'index.html': 'text/html; charset=utf-8',
  'trip.json': 'application/json',
};

/** Same as the controller, and short because a republish should show up soon. */
const CACHE_CONTROL = 'public, max-age=300';

/**
 * The R2 key for a path, or null for anything that is not one of the four
 * routes. Works on the raw, still percent-encoded path, so an encoded slash or
 * dot can never pass SAFE; `..` segments are already resolved by URL parsing,
 * and whatever that leaves still has to pass SAFE.
 */
function keyFor(pathname) {
  const parts = pathname.split('/');
  // "/p/latam/" splits to ["", "p", "latam", ""]: a trailing slash is an
  // empty last segment, allowed only where the controller allows it.
  if (parts[0] !== '' || parts[1] !== 'p') return null;
  const rest = parts.slice(2);

  const slash = rest.length > 0 && rest[rest.length - 1] === '';
  const segments = slash ? rest.slice(0, -1) : rest;
  if (segments.some((segment) => segment === '')) return null;

  const [slug, ...tail] = segments;
  if (!slug || !SAFE.test(slug)) return null;
  const trip = `published/${slug}/`;

  // /p/<slug>  /p/<slug>/
  if (tail.length === 0) return `${trip}index.html`;

  // /p/<slug>/trip.json
  if (tail.length === 1 && tail[0] === 'trip.json' && !slash) return `${trip}trip.json`;

  if (tail[0] !== 'm' || tail.length < 2 || !SAFE.test(tail[1])) return null;
  const member = `${trip}m/${tail[1]}/`;

  // /p/<slug>/m/<member>  /p/<slug>/m/<member>/
  if (tail.length === 2) return `${member}index.html`;

  // /p/<slug>/m/<member>/trip.json
  if (tail.length === 3 && tail[2] === 'trip.json' && !slash) return `${member}trip.json`;

  return null;
}

function notFound() {
  return new Response('Not found', {
    status: 404,
    headers: { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' },
  });
}

export async function onRequest(context) {
  const { request, env } = context;

  if (request.method !== 'GET' && request.method !== 'HEAD') {
    return new Response('Method not allowed', { status: 405, headers: { Allow: 'GET, HEAD' } });
  }

  const url = new URL(request.url);
  const key = keyFor(url.pathname);
  if (!key) return notFound();

  // The query never changes the file (?show= is read by the page itself), so
  // it is left out of the cache key and every filter shares one entry. The
  // Cache API is per data centre and only takes effect on a custom domain,
  // not on *.pages.dev — there every request simply reads R2.
  const cacheKey = new Request(`${url.origin}${url.pathname}`, { method: 'GET' });
  const cache = caches.default;
  const cached = await cache.match(cacheKey);
  if (cached) return request.method === 'HEAD' ? new Response(null, cached) : cached;

  const object = await env.PAGES_BUCKET.get(key);
  // Not cached: a page published a minute from now should not 404 for five.
  if (!object) return notFound();

  const filename = key.slice(key.lastIndexOf('/') + 1);
  const response = new Response(object.body, {
    headers: {
      'Content-Type': FILES[filename],
      'Cache-Control': CACHE_CONTROL,
      ETag: object.httpEtag,
    },
  });

  context.waitUntil(cache.put(cacheKey, response.clone()));
  return request.method === 'HEAD' ? new Response(null, response) : response;
}
