import {
  ArrowRightOutlined,
  LockOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { history, useModel, useSearchParams } from '@umijs/max';
import { Alert, App, Button, Checkbox, Form, Input, Typography } from 'antd';
import { useState } from 'react';
import { superworkApi } from '@/services/superwork/api';
import './login.less';

type LoginValues = { username: string; password: string; remember: boolean };

export default function LoginPage() {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [params] = useSearchParams();
  const { setInitialState } = useModel('@@initialState');
  const { message: feedback } = App.useApp();
  const remembered = localStorage.getItem('bu-remembered-username') || '';

  const onFinish = async (values: LoginValues) => {
    setLoading(true);
    setError('');
    try {
      const result = await superworkApi.login(
        values.username.trim(),
        values.password,
      );
      localStorage.setItem('token', result.accessToken);
      localStorage.setItem('refreshToken', result.refreshToken);
      setInitialState((state) => ({ ...state, currentUser: result.userInfo }));
      localStorage.setItem('user', JSON.stringify(result.userInfo));
      if (values.remember)
        localStorage.setItem('bu-remembered-username', values.username.trim());
      else localStorage.removeItem('bu-remembered-username');
      feedback.success('登录成功');
      history.replace(params.get('redirect') || '/workbench');
    } catch (e) {
      setError(e instanceof Error ? e.message : '登录失败，请检查账号或密码');
    } finally {
      setLoading(false);
    }
  };

  return (
    <main className="sw-login-page">
      <section className="sw-login-visual" aria-label="产品介绍">
        <div className="sw-login-grid" />
        <div className="sw-login-orbit sw-login-orbit-one" />
        <div className="sw-login-orbit sw-login-orbit-two" />
        <div className="sw-login-visual-copy">
          <div className="sw-login-kicker">SUPERWORK / 2026</div>
          <Typography.Title>
            让每一项工作，
            <br />
            <span>都有清晰的下一步。</span>
          </Typography.Title>
          <Typography.Paragraph>
            统一承接需求、任务、缺陷与周报，让业务状态从“被动汇报”回到“主动推进”。
          </Typography.Paragraph>
          <div className="sw-login-principles">
            <span>01 · 可追踪</span>
            <span>02 · 可协作</span>
            <span>03 · 可交付</span>
          </div>
        </div>
        <div className="sw-login-visual-footer">电商BU · 内部协同平台</div>
      </section>
      <section className="sw-login-panel">
        <div className="sw-login-form-wrap">
          <div className="sw-login-brand">
            <span className="sw-brand-mark">BU</span>
            <span>
              <strong>电商BU</strong>
              <small>管理系统</small>
            </span>
          </div>
          <div className="sw-login-heading">
            <Typography.Title level={2}>欢迎回来</Typography.Title>
            <Typography.Text type="secondary">
              登录后继续处理你的业务工作
            </Typography.Text>
          </div>
          {error && (
            <Alert
              className="sw-login-alert"
              type="error"
              showIcon
              message={error}
            />
          )}
          <Form<LoginValues>
            layout="vertical"
            initialValues={{
              username: remembered,
              remember: Boolean(remembered),
            }}
            onFinish={onFinish}
            requiredMark={false}
            size="large"
          >
            <Form.Item
              label="账号"
              name="username"
              rules={[{ required: true, message: '请输入账号' }]}
            >
              <Input
                prefix={<UserOutlined />}
                placeholder="请输入账号"
                autoComplete="username"
                autoFocus
              />
            </Form.Item>
            <Form.Item
              label="密码"
              name="password"
              rules={[{ required: true, message: '请输入密码' }]}
            >
              <Input.Password
                prefix={<LockOutlined />}
                placeholder="请输入密码"
                autoComplete="current-password"
              />
            </Form.Item>
            <div className="sw-login-options">
              <Form.Item name="remember" valuePropName="checked" noStyle>
                <Checkbox>记住账号</Checkbox>
              </Form.Item>
              <Typography.Text type="secondary">
                账号问题请联系系统管理员
              </Typography.Text>
            </div>
            <Button
              htmlType="submit"
              type="primary"
              block
              loading={loading}
              icon={<ArrowRightOutlined />}
              iconPlacement="end"
            >
              进入系统
            </Button>
          </Form>
          <Typography.Text className="sw-login-note" type="secondary">
            仅限授权成员访问 · 数据权限遵循现有系统配置
          </Typography.Text>
        </div>
      </section>
    </main>
  );
}
