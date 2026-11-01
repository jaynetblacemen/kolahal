// quote.js — a random quote from ZenQuotes (free, no key; ~5 requests / 30 s).
// @command
// @desc A random quote; long-press to copy it
// @usage
// @title Quote of the day
// @at 08:00
// @notify
//
// At its daily time it also sends the quote as a notification.

// A failure: what didn't work, in the theme's bad colour, and why under it.
const fail = (what, why) => log(fmt.color("bad", what), why);

let r;
try {
  r = await http.get("https://zenquotes.io/api/random");
} catch (e) {
  return fail("No quote", `Network error: ${e.message || e}`);
}
if (!r.ok) return fail("No quote", `ZenQuotes answered HTTP ${r.status}`);

let q;
try {
  q = JSON.parse(r.body)[0];
} catch (e) {
  return fail("No quote", "ZenQuotes sent something unexpected");
}
if (!q || !q.q) return fail("No quote", "ZenQuotes sent an empty answer");

const author = q.a || "Unknown";
const text = `“${q.q.trim()}” — ${author}`;
log(fmt.italic(`“${q.q.trim()}”`), `— ${author}`, { copy: text });

// Typed, the quote is on screen; at its daily time, the notification is the point.
if (event) notify(author, q.q.trim());
