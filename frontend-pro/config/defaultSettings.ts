import type { ProLayoutProps } from '@ant-design/pro-components';

const settings: ProLayoutProps & { logo?: string } = {
  navTheme: 'light',
  colorPrimary: '#1966ff',
  layout: 'mix',
  // 双栏菜单自行管理一级、二级和三级展示，不再依赖 mix 的顶部菜单分割。
  splitMenus: false,
  contentWidth: 'Fluid',
  fixedHeader: true,
  fixSiderbar: true,
  siderWidth: 281,
  colorWeak: false,
  title: '电商BU管理系统',
  logo: undefined,
  token: {
    bgLayout: '#f1f5fe',
    sider: {
      colorMenuBackground: '#ffffff',
      colorTextMenu: '#697586',
      colorTextMenuSelected: '#1677ff',
      colorBgMenuItemSelected: '#edf4ff',
    },
  },
};

export default settings;
