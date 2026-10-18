// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import { satteri } from '@astrojs/markdown-satteri';

// Where the site is published. For a fork, change these two values (or set the env vars):
//   SITE  https://<github-user>.github.io
//   BASE  /<repository-name>        (use '/' for a custom domain or a <user>.github.io repo)
const SITE = process.env.DOCS_SITE ?? 'https://rscipher001.github.io';
const BASE = process.env.DOCS_BASE ?? '/kolahal';
const REPO = 'https://github.com/rscipher001/kolahal';

/**
 * Pages link to each other site-absolute (`/reference/commands/`); this adds the base path at
 * build time. Relative links would break whenever a page is opened without its trailing slash.
 */
function withBase(path) {
	const base = BASE.replace(/\/$/, '');
	if (!path.startsWith('/') || path.startsWith('//') || path === base || path.startsWith(base + '/')) return path;
	return base + path;
}
/** Sätteri (Astro's Markdown processor) hast plugin: runs on every Markdown / MDX link. */
const baseLinks = {
	name: 'base-links',
	element: {
		filter: ['a'],
		visit(node, ctx) {
			const href = node.properties?.href;
			if (typeof href === 'string' && withBase(href) !== href) ctx.setProperty(node, 'href', withBase(href));
		},
	},
};

export default defineConfig({
	site: SITE,
	base: BASE,
	markdown: { processor: satteri({ hastPlugins: [baseLinks] }) },
	integrations: [
		starlight({
			title: 'Kolahal',
			description: 'A keyboard-first terminal launcher for Android, with work profile support and Lua / JavaScript scripts.',
			logo: { src: './src/assets/logo.svg', replacesTitle: false },
			favicon: '/favicon.svg',
			social: [{ icon: 'github', label: 'GitHub', href: REPO }],
			editLink: { baseUrl: `${REPO}/edit/main/docs/` },
			customCss: [
				'@fontsource/jetbrains-mono/400.css',
				'@fontsource/jetbrains-mono/600.css',
				'@fontsource/jetbrains-mono/700.css',
				'./src/styles/terminal.css',
			],
			components: {
				Hero: './src/components/Hero.astro',
			},
			expressiveCode: {
				themes: ['github-dark-dimmed', 'gruvbox-light-medium'],
				styleOverrides: {
					borderRadius: '0.25rem',
					codeFontFamily: "'JetBrains Mono', ui-monospace, monospace",
					uiFontFamily: "'JetBrains Mono', ui-monospace, monospace",
				},
			},
			sidebar: [
				{
					label: 'Getting started',
					items: [
						{ slug: 'getting-started/install' },
						{ slug: 'getting-started/setup' },
						{ slug: 'getting-started/basics' },
					],
				},
				{
					label: 'Guides',
					items: [
						{ slug: 'guides/profiles' },
						{ slug: 'guides/apps' },
						{ slug: 'guides/notifications' },
						{ slug: 'guides/media-and-controls' },
						{ slug: 'guides/feeds' },
						{ slug: 'guides/people' },
						{ slug: 'guides/productivity' },
						{ slug: 'guides/web-and-calculator' },
						{ slug: 'guides/widgets' },
						{ slug: 'guides/gestures' },
						{ slug: 'guides/themes-and-settings' },
						{ slug: 'guides/backup' },
					],
				},
				{
					label: 'Scripting',
					items: [
						{ slug: 'scripting/overview' },
						{ slug: 'scripting/api' },
						{ slug: 'scripting/library' },
						{ slug: 'scripting/examples' },
						{ slug: 'scripting/editor' },
					],
				},
				{
					label: 'Reference',
					items: [
						{ slug: 'reference/commands' },
						{ slug: 'reference/config' },
						{ slug: 'reference/privacy' },
					],
				},
			],
		}),
	],
});
