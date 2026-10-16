// reddit.js — today's top posts of a subreddit (public JSON/RSS, no API key).
// @command
// @desc top posts today: reddit [subreddit] (default: android)
//
// Usage:
//   reddit             top 5 posts of r/android today
//   reddit linux       top 5 posts of r/linux today
//
// Uses the JSON listing (with scores and comment counts) and falls back to
// the subreddit's RSS feed when Reddit blocks anonymous JSON requests.
// Tap a line to open the discussion; long-press to copy the link.

const LIMIT = 5;
const UA = { "User-Agent": "android:kolahal-launcher:0.1 (by /u/kolahal)" }; // Reddit rejects generic agents
const sub = (argline || "").trim().replace(/^\/?r\//i, "") || "android";

if (!/^[A-Za-z0-9_]{2,21}$/.test(sub)) {
  return `reddit: invalid subreddit '${sub}'`;
}

// Compact score: 12345 -> 12.3k
const short = (n) => (n >= 1000 ? (n / 1000).toFixed(1) + "k" : String(n));

// 1) JSON listing.
let r = null;
try {
  r = await http.get(`https://www.reddit.com/r/${sub}/top.json?limit=${LIMIT}&t=day&raw_json=1`, UA);
} catch (e) {
  return `reddit: network error (${e.message || e})`;
}
if (r.status === 404) return `reddit: r/${sub} not found`;

if (r.ok) {
  let posts;
  try {
    posts = JSON.parse(r.body).data.children.map((c) => c.data);
  } catch (e) {
    return "reddit: unexpected response";
  }
  if (posts.length === 0) return `r/${sub}: no posts today`;
  for (const p of posts.slice(0, LIMIT)) {
    const link = "https://www.reddit.com" + p.permalink;
    log(`${short(p.score)} ▲ ${p.title} (${p.num_comments} comments)`, { url: link, copy: link });
  }
  return;
}

// 2) RSS fallback (JSON is often 403/429 for anonymous clients).
let feed;
try {
  feed = await rss.fetch(`https://www.reddit.com/r/${sub}/top/.rss?t=day&limit=${LIMIT}`);
} catch (e) {
  return `reddit: HTTP ${r.status}; RSS fallback failed too (${e.message || e})`;
}
const items = feed.items || [];
if (items.length === 0) return `r/${sub}: no posts today (or the subreddit is private)`;
for (const item of items.slice(0, LIMIT)) {
  log(item.title || "(untitled)", { url: item.link, copy: item.link });
}
