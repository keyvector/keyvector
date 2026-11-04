// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import starlightBlog from 'starlight-blog';

export default defineConfig({
	site: 'https://keyvector.dev',
	integrations: [
		starlight({
			title: 'Keyvector',
			description:
				'Semantic search for Amazon Keyspaces, without moving your data. Open source, runs in your own AWS account.',
			logo: { src: './src/assets/logo.svg', replacesTitle: false },
			favicon: '/favicon.svg',
			social: [
				{ icon: 'github', label: 'GitHub', href: 'https://github.com/keyvector/keyvector' },
				{ icon: 'email', label: 'Email hello@keyvector.dev', href: 'mailto:hello@keyvector.dev' },
			],
			editLink: { baseUrl: 'https://github.com/keyvector/keyvector/edit/main/site/' },
			customCss: ['./src/styles/custom.css'],
			lastUpdated: true,
			plugins: [
				starlightBlog({
					prefix: 'notes',
					title: 'Dev notes',
					authors: {
						yogesh: { name: 'Yogesh Sharma', title: 'Maintainer', url: 'https://github.com/yogeshnath' },
					},
				}),
			],
			sidebar: [
				{
					label: 'Start here',
					items: [
						{ label: 'Why Keyvector', slug: 'start/why' },
						{ label: 'Search options for Keyspaces', slug: 'start/options' },
						{ label: 'Quick start', slug: 'start/quick-start' },
						{ label: 'How it works', slug: 'start/how-it-works' },
					],
				},
				{
					label: 'Guides',
					items: [
						{ label: 'Tutorial: search research papers', slug: 'guides/arxiv-tutorial' },
						{ label: 'Deploy to your AWS account', slug: 'guides/deploy' },
						{ label: 'Choose an embedding model', slug: 'guides/models' },
						{ label: 'Backfill, resync and redrive', slug: 'guides/backfill' },
						{ label: 'Query', slug: 'guides/query' },
					],
				},
				{
					label: 'Reference',
					items: [
						{ label: 'Commands', slug: 'reference/commands' },
						{ label: 'Configuration', slug: 'reference/configuration' },
						{ label: 'Guarantees and limits', slug: 'reference/limits' },
						{ label: 'IAM permissions', slug: 'reference/iam' },
					],
				},
				{
					label: 'Project',
					items: [
						{ label: 'Status and testing', slug: 'project/status' },
						{ label: 'Roadmap', slug: 'project/roadmap' },
						{ label: 'Contributing', slug: 'project/contributing' },
					],
				},
			],
		}),
	],
});
