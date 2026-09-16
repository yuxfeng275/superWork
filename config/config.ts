import { join } from 'node:path';
import { defineConfig } from '@umijs/max';
import proxy from './proxy';
import routes from './routes';

const { UMI_ENV = 'dev' } = process.env;
const publicPath = '/';

export default defineConfig({
  hash: true,
  publicPath,
  routes,
  proxy: proxy[UMI_ENV as keyof typeof proxy],
  fastRefresh: true,
  esbuildMinifyIIFE: true,
  routePrefetch: {},
  manifest: {},
  title: '电商BU管理系统',
  initialState: {},
  access: {},
  model: {},
  reactQuery: {},
  locale: { default: 'zh-CN', antd: true, baseNavigator: false },
  antd: {
    configProvider: {
      variant: 'outlined',
      theme: {
        token: {
          colorPrimary: '#1966ff',
          colorInfo: '#1966ff',
          colorBgLayout: '#f1f5fe',
          colorText: '#252931',
          colorTextSecondary: '#667085',
          borderRadius: 8,
          fontFamily:
            '-apple-system, BlinkMacSystemFont, PingFang SC, Microsoft YaHei, Arial, sans-serif',
        },
      },
    },
  },
  request: {},
  layout: {
    locale: false,
    navTheme: 'light',
    layout: 'mix',
    contentWidth: 'Fluid',
    fixedHeader: true,
    fixSiderbar: true,
    siderWidth: 281,
    headerHeight: 64,
    colorPrimary: '#1966ff',
    title: '电商BU管理系统',
    logo: undefined,
  },
  headScripts: [{ src: join(publicPath, 'scripts/loading.js'), async: true }],
  tailwindcss: {},
  mock: { include: ['src/pages/**/_mock.ts'] },
  define: {
    'process.env.CI': process.env.CI,
    __APP_VERSION__: require('../package.json').version,
    __UMI_VERSION__: require('@umijs/max/package.json').version,
    __UTOO_VERSION__: require('@utoo/pack/package.json').version,
  },
});
