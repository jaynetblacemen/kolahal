// crypto.js — cryptocurrency prices from CoinGecko (free public API, no key).
// @command
// @desc coin prices: crypto [ids…] [--vs eur] (default: bitcoin ethereum, usd)
// @usage [<ids…>] [--vs <currency>]
// @title Crypto Prices
// @every 30m
//
// Usage:
//   crypto                       bitcoin and ethereum in USD
//   crypto solana dogecoin       other coins (CoinGecko ids)
//   crypto bitcoin --vs eur      in another currency
//
// On a timer (no arguments) it pins a compact price line, e.g. "BTC 67.1k ▲1.2% · ETH 3.1k ▼0.4%".

const DEFAULT_IDS = ["bitcoin", "ethereum"];
const SYMBOLS = { bitcoin: "BTC", ethereum: "ETH", solana: "SOL", dogecoin: "DOGE", litecoin: "LTC", monero: "XMR", cardano: "ADA", ripple: "XRP" };

const words = (argline || "").trim().split(/[\s,]+/).filter(Boolean);
let vs = "usd";
const ids = [];
for (let i = 0; i < words.length; i++) {
  if (words[i] === "--vs" && words[i + 1]) vs = words[++i].toLowerCase();
  else ids.push(words[i].toLowerCase());
}
if (ids.length === 0) ids.push(...DEFAULT_IDS);

const url =
  "https://api.coingecko.com/api/v3/simple/price" +
  `?ids=${encodeURIComponent(ids.join(","))}&vs_currencies=${encodeURIComponent(vs)}&include_24hr_change=true`;

let r;
try {
  r = await http.get(url, { Accept: "application/json" });
} catch (e) {
  return `crypto: network error (${e.message || e})`;
}
if (r.status === 429) return "crypto: rate limited by CoinGecko, try again in a minute";
if (!r.ok) return `crypto: HTTP ${r.status}`;

let prices;
try {
  prices = JSON.parse(r.body);
} catch (e) {
  return "crypto: unexpected response";
}

// 67123.4 -> "67,123.40"; tiny prices keep more decimals.
function num(n) {
  if (n >= 1) {
    const [int, frac] = n.toFixed(2).split(".");
    return int.replace(/\B(?=(\d{3})+(?!\d))/g, ",") + "." + frac;
  }
  return n.toPrecision(4);
}
const compact = (n) => (n >= 1000 ? (n / 1000).toFixed(1) + "k" : num(n));
const arrow = (c) => (c >= 0 ? "▲" : "▼") + Math.abs(c).toFixed(1) + "%";

const parts = [];
for (const id of ids) {
  const p = prices[id];
  if (!p || p[vs] == null) {
    log(`${id}: unknown coin or currency`);
    continue;
  }
  const change = p[`${vs}_24h_change`];
  const sym = SYMBOLS[id] || id.toUpperCase();
  log(`${sym} ${num(p[vs])} ${vs.toUpperCase()}` + (change != null ? `  ${arrow(change)} 24h` : ""), {
    url: `https://www.coingecko.com/en/coins/${id}`,
  });
  parts.push(`${sym} ${compact(p[vs])}` + (change != null ? ` ${arrow(change)}` : ""));
}

if (words.length === 0 && parts.length > 0) status(parts.join(" · "));
