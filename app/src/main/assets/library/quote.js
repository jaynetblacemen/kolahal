// quote.js — a random quote from ZenQuotes (free, no key; ~5 requests / 30 s).
// @command
// @desc a random quote; long-press to copy it
//
// On a timer (e.g. every morning) it also sends the quote as a notification.

let r;
try {
  r = await http.get("https://zenquotes.io/api/random");
} catch (e) {
  return `quote: network error (${e.message || e})`;
}
if (!r.ok) return `quote: HTTP ${r.status}`;

let q;
try {
  q = JSON.parse(r.body)[0];
} catch (e) {
  return "quote: unexpected response";
}
if (!q || !q.q) return "quote: empty response";

const author = q.a || "Unknown";
const text = `“${q.q.trim()}” — ${author}`;
log(text, { copy: text });

// When run in the background (no arguments) the notification is the point.
if (args.length === 0) notify(author, q.q.trim());
