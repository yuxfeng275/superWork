import type { ProLayoutProps } from '@ant-design/pro-components';

const settings: ProLayoutProps & { logo?: string } = {
  navTheme: 'light',
  colorPrimary: '#2563eb',
  layout: 'mix',
  // mix 模式下开启菜单分割：顶部导航只保留一级，左侧只展示当前一级分组下的二/三级（三级用 group 平铺，不折叠）
  splitMenus: true,
  contentWidth: 'Fluid',
  fixedHeader: true,
  fixSiderbar: true,
  siderWidth: 248,
  colorWeak: false,
  title: '电商BU管理系统',
  logo: undefined,
  token: {
    sider: {
      colorMenuBackground: '#111827',
      colorTextMenu: '#a8b3c7',
      colorTextMenuSelected: '#ffffff',
      colorBgMenuItemSelected: '#2563eb',
    },
  },
};

export default settings;
