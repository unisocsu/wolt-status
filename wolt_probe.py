#!/usr/bin/env python3
"""Wolt probe: takes a venue page URL and prints the raw responses of several
candidate endpoints, so we can see which ones work and what fields they hold.

Env vars:
  WOLT_URL  - venue page link (required)
  WOLT_LAT  - optional latitude  (if empty, taken from the venue's own data)
  WOLT_LON  - optional longitude
"""
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

MAX_BODY = 60000  # max characters printed per response (full copy saved to out/)

HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    ),
    "Accept": "application/json, text/plain, */*",
    "Accept-Language": "en",
    "app-language": "en",
}

OUT_DIR = "out"
os.makedirs(OUT_DIR, exist_ok=True)


def parse_url(url):
    """Returns (country, city, slug) from a Wolt venue link."""
    path = urllib.parse.urlparse(url.strip()).path
    parts = [p for p in path.split("/") if p]
    slug = None
    for i, p in enumerate(parts):
        if p in ("restaurant", "venue") and i + 1 < len(parts):
            slug = parts[i + 1]
            break
    if not slug:
        sys.exit("Could not find a slug in the URL (expected .../restaurant/<slug> or .../venue/<slug>)")
    # /en/isr/tel-aviv/restaurant/<slug>
    country = parts[1] if len(parts) > 1 else ""
    city = parts[2] if len(parts) > 2 else ""
    return country, city, slug


def fetch(label, url):
    print("=" * 78)
    print(f"[{label}]")
    print(f"GET {url}")
    start = time.time()
    req = urllib.request.Request(url, headers=HEADERS)
    status, body, err = None, "", None
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            status = resp.status
            body = resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        status = e.code
        body = e.read().decode("utf-8", errors="replace")
    except Exception as e:  # network / TLS errors
        err = repr(e)
    took = time.time() - start
    print(f"-> status={status} time={took:.2f}s" + (f" error={err}" if err else ""))

    data = None
    if body:
        try:
            data = json.loads(body)
            pretty = json.dumps(data, ensure_ascii=False, indent=2)
        except ValueError:
            pretty = body
        fname = re.sub(r"[^a-z0-9]+", "_", label.lower()).strip("_") + ".json"
        with open(os.path.join(OUT_DIR, fname), "w", encoding="utf-8") as f:
            f.write(pretty)
        print("--- body ---")
        print(pretty[:MAX_BODY])
        if len(pretty) > MAX_BODY:
            print(f"... [truncated, {len(pretty)} chars total; full copy in artifact]")
    return status, data


def find_coords(obj):
    """Looks for [lon, lat] coordinates in a venue JSON (Wolt uses GeoJSON order)."""
    if isinstance(obj, dict):
        for key in ("location", "coordinates"):
            v = obj.get(key)
            if isinstance(v, list) and len(v) == 2 and all(isinstance(x, (int, float)) for x in v):
                return v[1], v[0]
        for v in obj.values():
            r = find_coords(v)
            if r:
                return r
    elif isinstance(obj, list):
        for v in obj:
            r = find_coords(v)
            if r:
                return r
    return None


def find_item_by_slug(obj, slug):
    """Walks a discovery page JSON and returns the first dict whose slug matches."""
    if isinstance(obj, dict):
        venue = obj.get("venue")
        if isinstance(venue, dict) and venue.get("slug") == slug:
            return obj
        if obj.get("slug") == slug:
            return obj
        for v in obj.values():
            r = find_item_by_slug(v, slug)
            if r:
                return r
    elif isinstance(obj, list):
        for v in obj:
            r = find_item_by_slug(v, slug)
            if r:
                return r
    return None


def page_probe(url):
    """Fetches the public venue web page and prints status-related snippets."""
    print("=" * 78)
    print("[0 venue web page HTML]")
    print(f"GET {url}")
    headers = dict(HEADERS)
    headers["Accept"] = "text/html,application/xhtml+xml"
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            status, html = resp.status, resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        status, html = e.code, e.read().decode("utf-8", errors="replace")
    except Exception as e:
        print(f"-> error={e!r}")
        return ""
    print(f"-> status={status} length={len(html)} chars")
    with open(os.path.join(OUT_DIR, "0_venue_page.html"), "w", encoding="utf-8") as f:
        f.write(html)

    # embedded JSON blocks (state of the page)
    for m in re.finditer(r'<script[^>]*(?:application/(?:ld\+)?json|__NEXT_DATA__)[^>]*>(.*?)</script>',
                         html, re.S):
        print(f"\n--- embedded JSON block ({len(m.group(1))} chars), first 3000: ---")
        print(m.group(1)[:3000])

    # context around keywords that hint at open/closed state
    print("\n--- keyword snippets ---")
    seen = 0
    for m in re.finditer(r'(?i)(temporarily offline|offline|"online"|is_online|isOnline|'
                         r'closed|opens at|delivery_open|ordering_unavailable)', html):
        s = max(0, m.start() - 120)
        print("...", html[s:m.end() + 160].replace("\n", " "), "...")
        seen += 1
        if seen >= 25:
            break
    if not seen:
        print("(no keywords found in the HTML; the page is probably rendered client-side)")
    return html


def bundle_probe(html):
    """Downloads the page's JS bundles and lists URL patterns that look like
    the live (dynamic) venue-status endpoint the web client calls."""
    print("=" * 78)
    print("[0b JS bundles: searching for the live venue-status endpoint]")
    srcs = re.findall(r'<script[^>]+src="(https://wolt-com-static-assets\.wolt\.com/[^"]+\.js)"', html)
    print(f"{len(srcs)} bundles found")
    pat = re.compile(r'["\'`]([^"\'`\s]{0,120}(?:/dynamic|venue-content|order-xp|/venues/slug|pages/venue)[^"\'`\s]{0,120})["\'`]')
    found = {}
    for u in srcs:
        try:
            req = urllib.request.Request(u, headers=HEADERS)
            with urllib.request.urlopen(req, timeout=30) as r:
                js = r.read().decode("utf-8", errors="replace")
        except Exception as e:
            print(f"  skip {u.rsplit('/', 1)[-1]}: {e!r}")
            continue
        for m in pat.finditer(js):
            key = m.group(1)
            if key not in found:
                ctx = js[max(0, m.start() - 150):m.end() + 150].replace("\n", " ")
                found[key] = (u.rsplit("/", 1)[-1], ctx)
    lines = [f"{len(found)} distinct candidate patterns from {len(srcs)} bundles\n"]
    for key, (bundle, ctx) in sorted(found.items()):
        lines.append(f"* {key}\n    in {bundle}\n    ...{ctx}...\n")
    report = "\n".join(lines)
    with open(os.path.join(OUT_DIR, "0b_bundle_endpoints.txt"), "w", encoding="utf-8") as f:
        f.write(report)
    print(report[:MAX_BODY])


def main():
    url = os.environ.get("WOLT_URL", "").strip()
    if not url:
        sys.exit("WOLT_URL is empty")
    country, city, slug = parse_url(url)
    print(f"Parsed: country={country!r} city={city!r} slug={slug!r}")

    html = page_probe(url)
    if html:
        bundle_probe(html)

    lat = os.environ.get("WOLT_LAT", "").strip()
    lon = os.environ.get("WOLT_LON", "").strip()

    # 1. static venue info (usually contains 'online', opening_times, coordinates)
    _, static = fetch("1 restaurant-api v3 venue by slug",
                      f"https://restaurant-api.wolt.com/v3/venues/slug/{slug}")

    if (not lat or not lon) and static:
        c = find_coords(static)
        if c:
            lat, lon = str(c[0]), str(c[1])
            print(f"\nCoordinates taken from the venue's own data: lat={lat} lon={lon}")

    if not lat or not lon:
        print("\nNo coordinates available, skipping location-based endpoints.")
        return

    # 2. dynamic venue info (candidate endpoints, may need adjustment)
    q = urllib.parse.urlencode({"lat": lat, "lon": lon})
    candidates = [
        ("2a consumer-api order-xp dynamic",
         f"https://consumer-api.wolt.com/order-xp/web/v1/venue/slug/{slug}/dynamic/?{q}"),
        ("2b consumer-api venue-content-api dynamic",
         f"https://consumer-api.wolt.com/venue-content-api/v3/venues/slug/{slug}/dynamic/?{q}"),
        ("2c restaurant-api dynamic",
         f"https://restaurant-api.wolt.com/v4/venues/slug/{slug}/dynamic/?{q}"),
    ]
    for label, u in candidates:
        fetch(label, u)

    # 3. discovery page around the location; find our venue inside it
    status, page = fetch("3 consumer-api discovery restaurants (raw, may be large)",
                         f"https://consumer-api.wolt.com/v1/pages/restaurants?{q}")
    if page:
        item = find_item_by_slug(page, slug)
        print("\n" + "=" * 78)
        print("[3b our venue inside the discovery page]")
        if item:
            print(json.dumps(item, ensure_ascii=False, indent=2)[:MAX_BODY])
        else:
            print("Venue not found in the discovery page (maybe it does not deliver to this point).")


if __name__ == "__main__":
    main()
