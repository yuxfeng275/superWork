import {
  CheckCircleOutlined,
  CloudSyncOutlined,
  ReloadOutlined,
  SaveOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import { useModel } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Col,
  Empty,
  Form,
  Input,
  InputNumber,
  message,
  Row,
  Space,
  Spin,
  Switch,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  type SystemConfigGroup,
  type SystemConfigGroupSummary,
  type SystemConfigItem,
  superworkApi,
} from '@/services/superwork/api';
import '../workbench/style.less';
import './style.less';

const integrationMeta: Record<string, { label: string; key: string }> = {
  deepseek: { label: '测试 DeepSeek', key: 'deepseek' },
  wecom: { label: '测试企微', key: 'wecom' },
  worktime: { label: '测试工时系统', key: 'worktime' },
  yuque: { label: '测试语雀', key: 'yuque' },
};
const canManageRole = (role?: string) =>
  [
    'admin',
    '系统管理员',
    'DIRECTOR',
    'DEPUTY_DIRECTOR',
    'BUSINESS_OWNER',
    'EFFECTIVENESS_OWNER',
    'BU_ADMIN',
  ].includes(role || '');

export default function SystemConfigsPage() {
  const { initialState } = useModel('@@initialState');
  const canManage = canManageRole(initialState?.currentUser?.role);
  const [groups, setGroups] = useState<SystemConfigGroupSummary[]>([]);
  const [group, setGroup] = useState<SystemConfigGroup>();
  const [selected, setSelected] = useState('');
  const [values, setValues] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState('');
  const [error, setError] = useState('');

  const applyGroup = (next: SystemConfigGroup) => {
    setGroup(next);
    const nextValues: Record<string, string> = {};
    next.items.forEach((item) => {
      nextValues[item.key] = item.sensitive ? '' : item.value || '';
    });
    setValues(nextValues);
  };
  const loadGroup = useCallback(async (code: string) => {
    setSelected(code);
    setError('');
    try {
      applyGroup(await superworkApi.getSystemConfigGroup(code));
    } catch (e) {
      setError(e instanceof Error ? e.message : '配置组加载失败');
    }
  }, []);
  const loadGroups = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const result = await superworkApi.getSystemConfigGroups();
      setGroups(result || []);
      const code = selected || result?.[0]?.groupCode;
      if (code) await loadGroup(code);
    } catch (e) {
      setError(e instanceof Error ? e.message : '配置列表加载失败');
    } finally {
      setLoading(false);
    }
  }, [loadGroup, selected]);
  useEffect(() => {
    void loadGroups();
  }, [loadGroups]);
  const configuredCount = useMemo(
    () => group?.items.filter((item) => item.configured).length || 0,
    [group],
  );
  const setValue = (key: string, value: string) =>
    setValues((current) => ({ ...current, [key]: value }));
  const save = async () => {
    if (!group || !canManage) return;
    setSaving(true);
    try {
      applyGroup(
        await superworkApi.saveSystemConfigGroup(group.groupCode, values),
      );
      setGroups(await superworkApi.getSystemConfigGroups());
      message.success('系统配置已保存');
    } catch (e) {
      message.error(e instanceof Error ? e.message : '系统配置保存失败');
    } finally {
      setSaving(false);
    }
  };
  const test = async (integration: string) => {
    if (!group || !canManage) return;
    setTesting(integration);
    try {
      const result = await superworkApi.testSystemConfigIntegration(
        group.groupCode,
        integration,
      );
      result.success
        ? message.success(result.message)
        : message.error(result.message);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '连接测试失败');
    } finally {
      setTesting('');
    }
  };
  const renderItem = (item: SystemConfigItem) => {
    const value = values[item.key] || '';
    if (item.valueType === 'BOOLEAN')
      return (
        <Switch
          checked={value === 'true'}
          disabled={!canManage}
          onChange={(checked) => setValue(item.key, String(checked))}
        />
      );
    if (item.valueType === 'NUMBER')
      return (
        <InputNumber
          value={value ? Number(value) : undefined}
          disabled={!canManage}
          style={{ width: '100%' }}
          onChange={(next) =>
            setValue(item.key, next == null ? '' : String(next))
          }
        />
      );
    return (
      <Input
        value={value}
        disabled={!canManage}
        type={
          item.sensitive || item.valueType === 'PASSWORD' ? 'password' : 'text'
        }
        autoComplete={item.sensitive ? 'new-password' : 'off'}
        placeholder={
          item.sensitive && item.configured
            ? '已配置；留空保持不变'
            : item.description || `请输入${item.name}`
        }
        onChange={(event) => setValue(item.key, event.target.value)}
      />
    );
  };
  const activeIntegrations = Object.keys(integrationMeta).filter((key) =>
    group?.items.some((item) => item.key.startsWith(`${key}.`)),
  );

  const sortedItems = group
    ? [...group.items].sort((a, b) => a.sortOrder - b.sortOrder)
    : [];
  return (
    <div className="sw-page sw-system-configs">
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">
            SYSTEM / CONFIGURATION
          </Typography.Text>
          <Typography.Title level={2}>配置管理</Typography.Title>
          <Typography.Paragraph type="secondary">
            统一维护系统集成、服务地址、功能开关与敏感凭据。敏感信息不会回显。
          </Typography.Paragraph>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void loadGroups()}>
            刷新
          </Button>
          {canManage && (
            <Button
              type="primary"
              icon={<SaveOutlined />}
              loading={saving}
              onClick={() => void save()}
            >
              保存配置
            </Button>
          )}
        </Space>
      </div>
      {error && (
        <Alert
          type="error"
          showIcon
          message="配置读取失败"
          description={error}
          action={
            <Button size="small" onClick={() => void loadGroups()}>
              重试
            </Button>
          }
        />
      )}
      <Spin spinning={loading}>
        <Row gutter={16} align="top">
          <Col xs={24} lg={7}>
            <Card variant="borderless" className="sw-config-group-card">
              <Typography.Text strong>配置分组</Typography.Text>
              <div className="sw-config-group-list">
                {groups.map((item) => (
                  <button
                    type="button"
                    key={item.groupCode}
                    className={selected === item.groupCode ? 'is-selected' : ''}
                    onClick={() => void loadGroup(item.groupCode)}
                  >
                    <SettingOutlined />
                    <span>
                      <strong>{item.groupName}</strong>
                      <small>
                        {item.configuredCount}/{item.itemCount} 项已配置
                      </small>
                    </span>
                  </button>
                ))}
                {!groups.length && (
                  <Empty
                    image={Empty.PRESENTED_IMAGE_SIMPLE}
                    description="暂无配置组"
                  />
                )}
              </div>
            </Card>
          </Col>
          <Col xs={24} lg={17}>
            {group ? (
              <Card variant="borderless" className="sw-config-editor">
                <div className="sw-config-editor-head">
                  <div>
                    <Typography.Title level={4}>
                      {group.groupName}
                    </Typography.Title>
                    <Typography.Text type="secondary">
                      {group.description || '按后端元数据编辑配置项'}
                    </Typography.Text>
                  </div>
                  <Tag color={configuredCount ? 'blue' : 'default'}>
                    {configuredCount}/{group.items.length} 项已配置
                  </Tag>
                </div>
                <Form layout="vertical">
                  <Row gutter={16}>
                    {sortedItems.map((item) => (
                      <Col
                        xs={24}
                        md={item.valueType === 'BOOLEAN' ? 12 : 24}
                        key={item.key}
                      >
                        <Form.Item label={item.name} required={item.required}>
                          {renderItem(item)}
                          <Typography.Text
                            type="secondary"
                            className="sw-config-help"
                          >
                            {item.description || item.key}
                            {item.sensitive ? ' · 保存后不回显' : ''}
                          </Typography.Text>
                        </Form.Item>
                      </Col>
                    ))}
                  </Row>
                </Form>
                {activeIntegrations.length > 0 && (
                  <>
                    <Typography.Title level={5}>集成测试</Typography.Title>
                    <Space wrap>
                      {activeIntegrations.map((integration) => (
                        <Button
                          key={integration}
                          icon={
                            testing === integration ? (
                              <CloudSyncOutlined spin />
                            ) : (
                              <CheckCircleOutlined />
                            )
                          }
                          loading={testing === integration}
                          disabled={!canManage}
                          onClick={() => void test(integration)}
                        >
                          {integrationMeta[integration].label}
                        </Button>
                      ))}
                    </Space>
                  </>
                )}
              </Card>
            ) : (
              <Card variant="borderless">
                <Empty description="请选择配置分组" />
              </Card>
            )}
          </Col>
        </Row>
      </Spin>
    </div>
  );
}
