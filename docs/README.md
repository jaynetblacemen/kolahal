# Kolahal docs

The documentation site for Kolahal, built with [Astro](https://astro.build) and
[Starlight](https://starlight.astro.build). It is published to GitHub Pages by
`.github/workflows/docs.yml` on every push to `main` that touches `docs/`.

```sh
cd docs
npm install
npm run dev       # http://localhost:4321/kolahal/
npm run build     # static site in dist/, then checks every internal link
npm run preview   # serve dist/
```

## Layout

```
docs/
├── astro.config.mjs        site URL, base path, sidebar, theme settings
├── scripts/check-links.mjs post-build link and anchor checker (part of npm run build)
├── public/favicon.svg
└── src/
    ├── assets/             logo and screenshots (optimised by astro:assets)
    ├── components/         Hero override and the fake terminal on the landing page
    ├── content/docs/       the pages (.md / .mdx); the folder structure is the URL structure
    └── styles/terminal.css colours (app "green" palette in dark mode, gruvbox light) and fonts
```

Links between pages are relative (`../../reference/commands/`), so the site works under any base
path. `npm run build` fails if one is broken.

## Hosting somewhere else

`site` and `base` are at the top of `astro.config.mjs`:

```js
const SITE = process.env.DOCS_SITE ?? 'https://rscipher001.github.io';
const BASE = process.env.DOCS_BASE ?? '/kolahal';
```

For a fork, change them to `https://<user>.github.io` and `/<repo>`, or set `DOCS_SITE` /
`DOCS_BASE` when building. Use `BASE = '/'` for a custom domain or a `<user>.github.io` repository.
The link checker reads `DOCS_BASE` too.

In the repository's **Settings → Pages**, set the source to **GitHub Actions** once.

Dependency versions are pinned exactly in `package.json`, and `package-lock.json` is committed;
CI installs with `npm ci`.
