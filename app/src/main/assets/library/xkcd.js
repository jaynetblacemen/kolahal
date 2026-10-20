// xkcd.js — the latest xkcd comic (or a given / random one).
// @command
// @desc xkcd comic: xkcd | xkcd <number> | xkcd random
//
// On a timer (no arguments) it notifies only when a new comic is out.
// The comic shows in the log under its title; tap the title to open it on xkcd.com.

const arg = (argline || "").trim().toLowerCase();

async function comic(num) {
  const url = num ? `https://xkcd.com/${num}/info.0.json` : "https://xkcd.com/info.0.json";
  const r = await http.get(url);
  if (r.status === 404) throw new Error(`no comic #${num}`);
  if (!r.ok) throw new Error(`HTTP ${r.status}`);
  return JSON.parse(r.body);
}

let c;
try {
  if (arg === "random") {
    const latest = await comic(null);
    let n = 1 + Math.floor(Math.random() * latest.num);
    if (n === 404) n = 405; // #404 famously does not exist
    c = await comic(n);
  } else if (/^\d+$/.test(arg)) {
    c = await comic(parseInt(arg, 10));
  } else if (arg === "") {
    c = await comic(null);
  } else {
    return "usage: xkcd | xkcd <number> | xkcd random";
  }
} catch (e) {
  return `xkcd: ${e.message || e}`;
}

const link = `https://xkcd.com/${c.num}/`;
log(`#${c.num} ${c.safe_title || c.title} (${c.year}-${String(c.month).padStart(2, "0")}-${String(c.day).padStart(2, "0")})`, {
  url: link,
  copy: link,
  image: c.img,
});
if (c.alt) log(c.alt, { url: c.img });

// Background runs: notify once per new comic.
if (arg === "") {
  const last = store.get("last");
  if (last !== String(c.num)) {
    store.set("last", String(c.num));
    if (last != null) notify(`xkcd #${c.num}`, c.safe_title || c.title, { url: link });
  }
}
