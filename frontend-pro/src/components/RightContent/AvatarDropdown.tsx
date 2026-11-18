import {
  LockOutlined,
  LogoutOutlined,
  SettingOutlined,
  SkinOutlined,
} from '@ant-design/icons';
import { history, useModel } from '@umijs/max';
import type { MenuProps } from 'antd';
import { Form, Input, Modal, Spin, message } from 'antd';
import React, { startTransition, useState } from 'react';
import { outLogin } from '@/services/ant-design-pro/api';
import { superworkApi } from '@/services/superwork/api';
import HeaderDropdown from '../HeaderDropdown';

type GlobalHeaderRightProps = {
  children?: React.ReactNode;
};

const menuItems: MenuProps['items'] = [
  {
    key: 'settings',
    icon: <SettingOutlined />,
    label: '个人设置',
  },
  {
    key: 'theme',
    icon: <SkinOutlined />,
    label: '主题设置',
  },
  {
    key: 'password',
    icon: <LockOutlined />,
    label: '修改密码',
  },
  {
    type: 'divider' as const,
  },
  {
    key: 'logout',
    icon: <LogoutOutlined />,
    label: '退出登录',
  },
];

const loginOut = async () => {
  try {
    await outLogin();
  } catch {
    // Local logout has already cleared user state; redirect should still proceed.
  }
  const { search, pathname } = window.location;
  const urlParams = new URL(window.location.href).searchParams;
  const searchParams = new URLSearchParams({
    redirect: pathname + search,
  });
  const redirect = urlParams.get('redirect');
  if (window.location.pathname !== '/user/login' && !redirect) {
    history.replace({
      pathname: '/user/login',
      search: searchParams.toString(),
    });
  }
};

export const AvatarDropdown: React.FC<GlobalHeaderRightProps> = ({
  children,
}) => {
  const { initialState, setInitialState } = useModel('@@initialState');
  const [passwordForm] = Form.useForm();
  const [passwordOpen, setPasswordOpen] = useState(false);
  const [changing, setChanging] = useState(false);

  const submitPassword = async () => {
    const values = await passwordForm.validateFields();
    setChanging(true);
    try {
      await superworkApi.changePassword(values.oldPassword, values.newPassword);
      message.success('密码已修改，请用新密码重新登录');
      setPasswordOpen(false);
      passwordForm.resetFields();
      startTransition(() => {
        setInitialState((s) => ({ ...s, currentUser: undefined }));
      });
      await loginOut();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '修改密码失败');
    } finally {
      setChanging(false);
    }
  };

  const onMenuClick: MenuProps['onClick'] = (event) => {
    const { key } = event;
    if (key === 'logout') {
      startTransition(() => {
        setInitialState((s) => ({ ...s, currentUser: undefined }));
      });
      loginOut();
      return;
    }
    if (key === 'theme') {
      setInitialState((s) => ({ ...s, settingDrawerOpen: true }));
      return;
    }
    if (key === 'password') {
      setPasswordOpen(true);
      return;
    }
    history.push(`/account/${key}`);
  };

  if (!initialState) {
    return <Spin size="small" />;
  }

  const { currentUser } = initialState;

  if (!currentUser) {
    return <Spin size="small" />;
  }

  return (
    <>
    <HeaderDropdown
      placement="bottomRight"
      menu={{
        selectedKeys: [],
        onClick: onMenuClick,
        items: menuItems,
      }}
      arrow
    >
      {children}
    </HeaderDropdown>
    <Modal
      title="修改密码"
      open={passwordOpen}
      confirmLoading={changing}
      onCancel={() => setPasswordOpen(false)}
      onOk={() => passwordForm.submit()}
      okText="修改并重新登录"
      cancelText="取消"
      destroyOnHidden
    >
      <Form form={passwordForm} layout="vertical" onFinish={() => void submitPassword()}>
        <Form.Item
          name="oldPassword"
          label="原密码"
          rules={[{ required: true, message: '请输入原密码' }]}
        >
          <Input.Password autoComplete="current-password" />
        </Form.Item>
        <Form.Item
          name="newPassword"
          label="新密码"
          rules={[
            { required: true, message: '请输入新密码' },
            { min: 8, message: '新密码至少 8 位' },
          ]}
        >
          <Input.Password autoComplete="new-password" />
        </Form.Item>
        <Form.Item
          name="confirmPassword"
          label="确认新密码"
          dependencies={['newPassword']}
          rules={[
            { required: true, message: '请再次输入新密码' },
            ({ getFieldValue }) => ({
              validator(_, value) {
                if (!value || getFieldValue('newPassword') === value) {
                  return Promise.resolve();
                }
                return Promise.reject(new Error('两次输入的新密码不一致'));
              },
            }),
          ]}
        >
          <Input.Password autoComplete="new-password" />
        </Form.Item>
      </Form>
    </Modal>
    </>
  );
};
