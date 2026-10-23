// forecast.js — 3-day forecast from Open-Meteo (free, no API key).
// @command
// @desc 3-day forecast: forecast [city] | forecast --default <city>
// @usage [<city>|--default <city>]
// @title 3-Day Forecast
// @at 07:00
//
// Usage:
//   forecast                  forecast for the saved default city (London if none)
//   forecast Tokyo            forecast for Tokyo
//   forecast --default Pune   save Pune as the default city
//
// On a timer (no arguments) it pins today's summary as a status line.

const line = (argline || "").trim();

const setDefault = line.match(/^--default\s+(.+)$/);
if (setDefault) {
  store.set("city", setDefault[1].trim());
  return `default city set to ${setDefault[1].trim()}`;
}

const city = line || store.get("city") || "London";

// WMO weather codes -> short text.
function describe(code) {
  if (code === 0) return "clear";
  if (code <= 2) return "partly cloudy";
  if (code === 3) return "overcast";
  if (code <= 48) return "fog";
  if (code <= 57) return "drizzle";
  if (code <= 67) return "rain";
  if (code <= 77) return "snow";
  if (code <= 82) return "showers";
  if (code <= 86) return "snow showers";
  return "thunderstorm";
}

async function getJson(url) {
  const r = await http.get(url);
  if (!r.ok) throw new Error(`HTTP ${r.status}`);
  return JSON.parse(r.body);
}

let place, data;
try {
  const geo = await getJson(
    `https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name=${encodeURIComponent(city)}`
  );
  if (!geo.results || geo.results.length === 0) return `forecast: no place named '${city}'`;
  place = geo.results[0];
  data = await getJson(
    "https://api.open-meteo.com/v1/forecast" +
      `?latitude=${place.latitude}&longitude=${place.longitude}` +
      "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
      "&timezone=auto&forecast_days=3"
  );
} catch (e) {
  return `forecast: ${e.message || e}`;
}

const d = data.daily;
if (!d || !d.time || d.time.length === 0) return "forecast: no data";

const names = ["today", "tomorrow"];
const where = [place.name, place.country_code].filter(Boolean).join(", ");

// The place is the title; one line per day under it.
const days = [];
let todayLine = "";
for (let i = 0; i < d.time.length; i++) {
  const label = names[i] || d.time[i].slice(5); // "MM-DD" for later days
  const hi = Math.round(d.temperature_2m_max[i]);
  const lo = Math.round(d.temperature_2m_min[i]);
  const rain = d.precipitation_probability_max ? d.precipitation_probability_max[i] : null;
  const text =
    `${fmt.bold(label)}: ${describe(d.weather_code[i])}, ${lo}–${hi}°C` +
    // A likely-rain day in the warning colour.
    (rain != null ? `, ${rain >= 60 ? fmt.color("warn", `rain ${rain}%`) : `rain ${rain}%`}` : "");
  days.push(text);
  if (i === 0) todayLine = `${place.name}: ${describe(d.weather_code[i])} ${lo}–${hi}°C`;
}

log(where, days.join("\n"), {
  url: `https://open-meteo.com/en/docs#latitude=${place.latitude}&longitude=${place.longitude}`,
});

if (!line) status(todayLine);
