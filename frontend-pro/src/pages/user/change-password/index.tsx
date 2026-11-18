import { LockOutlined } from '@ant-design/icons';
import { Button, Card, Form, Input, message, Typography } from 'antd';
import { history } from '@umijs/max';
import { useState } from 'react';
import { superworkApi } from '@/services/superwork/api';
import '../login.less';

type ChangePasswordForm = {
  oldPassword: string;
  newPassword: string;
  confirmPassword: string;
};

/**
 * 管理员重置密码后的强制改密页。
 *
 * 登录响应 passwordChangeRequired=true 时由登录页跳到这里；改完清本地登录态，
 * 用新密码重新登录。页面本身只依赖登录 token，不要求额外权限。
 */
export default function ChangePasswordPage() {
  const [loading, setLoading] = useState(false);

  const onFinish = async (values: ChangePasswordForm) => {
    setLoading(true);
    try {
      await superworkApi.changePassword(values.oldPassword, values.newPassword);
      localStorage.removeItem('token');
      localStorage.removeItem('refreshToken');
      localStorage.removeItem('user');
      message.success('密码已修改，请用新密码重新登录');
      history.replace('/user/login');
    } catch (e) {
      message.error(e instanceof Error ? e.message : '修改密码失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <main className="sw-login-page">
      <section className="sw-login-panel" style={{ margin: '0 auto' }}>
        <div className="sw-login-form-wrap">
          <div className="sw-login-heading">
            <Typography.Title level={2}>修改密码</Typography.Title>
            <Typography.Text type="secondary">
              管理员已重置你的密码，请先设置新密码再进入系统。
            </Typography.Text>
          </div>
          <Form<ChangePasswordForm>
            layout="vertical"
            size="large"
            requiredMark={false}
            onFinish={onFinish}
          >
            <Form.Item
              name="oldPassword"
              label="当前密码"
              rules={[{ required: true, message: '请输入当前密码' }]}
            >
              <Input.Password prefix={<LockOutlined />} autoComplete="current-password" />
            </Form.Item>
            <Form.Item
              name="newPassword"
              label="新密码"
              rules={[
                { required: true, message: '请输入新密码' },
                { min: 8, message: '新密码至少 8 位' },
              ]}
            >
              <Input.Password prefix={<LockOutlined />} autoComplete="new-password" />
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
              <Input.Password prefix={<LockOutlined />} autoComplete="new-password" />
            </Form.Item>
            <Button type="primary" htmlType="submit" block loading={loading}>
              修改并重新登录
            </Button>
          </Form>
          <Card size="small" variant="borderless" style={{ marginTop: 16 }}>
            <Typography.Text type="secondary">
              新密码至少 8 位，且不能与当前密码相同。修改后旧密码立即失效。
            </Typography.Text>
          </Card>
        </div>
      </section>
    </main>
  );
}
