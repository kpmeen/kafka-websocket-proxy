module.exports = {
  docs: [
    {
      type: 'category',
      label: 'Kafka WebSocket Proxy',
      items: [
        'index',
        'getting-started',
        'configuration',
        'logging',
        'monitoring',
        {
            type: 'category',
            label: 'Endpoints and APIs',
            link: {
                type: 'doc',
                id: 'apis'
            },
            items: ['http', 'websockets']
        },
        'development',
        'faq'
      ],
    },
  ],
};
