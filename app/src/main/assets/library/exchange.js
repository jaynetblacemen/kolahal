// exchange.js — currency conversion with daily rates from open.er-api.com (no key).
// @command
// @desc convert currency: exchange [amount] <from> [to…] (e.g. exchange 100 usd inr eur)
// @usage [<amount>] <from> [<to…>]
// @title Currency Converter
//
// Usage:
//   exchange 100 usd inr       100 US dollars in Indian rupees
//   exchange usd inr eur       1 USD in INR and EUR
//   exchange 50 eur            50 EUR in USD
//   exchange                   USD -> EUR, GBP, INR, JPY (good on a timer as a status line)
//
// Rates update once a day, so a timer more often than every few hours is wasted.

const words = (argline || "").trim().split(/\s+/).filter(Boolean);

let amount = 1;
if (words.length > 0 && /^[\d.,]+$/.test(words[0])) amount = parseFloat(words.shift().replace(/,/g, ""));
if (!isFinite(amount)) return "exchange: bad amount";

const codes = words.map((w) => w.toUpperCase());
if (codes.some((c) => !/^[A-Z]{3}$/.test(c))) {
  return "usage: exchange [amount] <from> [to…]   e.g. exchange 100 usd inr";
}
const background = codes.length === 0;
const from = codes[0] || "USD";
let targets = codes.slice(1);
if (targets.length === 0) targets = background ? ["EUR", "GBP", "INR", "JPY"] : [from === "USD" ? "EUR" : "USD"];

let r;
try {
  r = await http.get(`https://open.er-api.com/v6/latest/${from}`);
} catch (e) {
  return `exchange: network error (${e.message || e})`;
}
if (!r.ok) return `exchange: HTTP ${r.status}`;

let data;
try {
  data = JSON.parse(r.body);
} catch (e) {
  return "exchange: unexpected response";
}
if (data.result !== "success") return `exchange: unknown currency ${from}`;

// 1234567.891 -> "1,234,567.89"
function num(n) {
  const digits = n >= 100 ? 2 : n >= 1 ? 3 : 5;
  const [int, frac] = n.toFixed(digits).split(".");
  return int.replace(/\B(?=(\d{3})+(?!\d))/g, ",") + (frac ? "." + frac : "");
}
// Whole amounts print without decimals: "100 USD", not "100.00 USD".
const amountText = Number.isInteger(amount) ? num(amount).split(".")[0] : num(amount);

const parts = [];
for (const to of targets) {
  const rate = data.rates[to];
  if (rate == null) {
    log(`${to}: unknown currency`);
    continue;
  }
  const value = num(amount * rate);
  log(`${amountText} ${from} = ${fmt.bold(value)} ${to}`, { copy: value.replace(/,/g, "") });
  parts.push(`${to} ${num(rate)}`);
}

if (background && parts.length > 0) status(`${from}: ` + parts.join(" · "));
