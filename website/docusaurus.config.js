/** @type {import('@docusaurus/types').DocusaurusConfig} */
module.exports = {
  title: 'Kafka WebSocket Proxy',
  tagline: 'documentation site',
  url: 'https://kpmeen.gitlab.io',
  baseUrl: '/kafka-websocket-proxy/',
  onBrokenLinks: 'throw',
  onBrokenMarkdownLinks: 'warn',
  favicon: 'img/favicon.ico',
  organizationName: 'kpmeen',
  projectName: 'kafka-websocket-proxy',
  themeConfig: {
    docs: {
      sidebar: {
        hideable: true
      }
    },
    navbar: {
      title: 'Kafka WebSocket Proxy',
      items: [
        {
          to: '/',
          activeBasePath: 'docs/index',
          label: 'Docs',
          position: 'left',
        },
        {
          href: 'https://gitlab.com/kpmeen/kafka-websocket-proxy',
          label: 'GitLab',
          position: 'right',
        },
      ],
    },
    footer: {
      style: 'dark',
      copyright: `Copyright © ${new Date().getFullYear()} Knut Petter Meen. Built with Docusaurus.`,
    },
  },
  presets: [
    [
      '@docusaurus/preset-classic',
      {
        docs: {
          path: '../kafka-websocket-proxy-docs/target/mdoc',
          sidebarPath: require.resolve('./sidebars.js'),
          editUrl: 'https://gitlab.com/kpmeen/kafka-websocket-proxy/edit/master/website/',
          routeBasePath: '/',
        },
        theme: {
          customCss: require.resolve('./src/css/custom.css'),
        },
      },
    ],
  ],
};
