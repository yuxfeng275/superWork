/** 开发环境只代理已有 API，不在前端新增接口或改变接口语义。 */
export default {
  dev: {
    '/api/': {
      target: process.env.PROXY_TARGET ?? 'http://100.85.67.82:18080',
      changeOrigin: true,
      secure: false,
    },
  },
};
