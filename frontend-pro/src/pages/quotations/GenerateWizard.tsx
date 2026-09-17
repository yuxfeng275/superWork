import {
  Button,
  Card,
  Checkbox,
  Col,
  Divider,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  message,
  Row,
  Space,
  Steps,
  Table,
  Tag,
  Typography,
} from 'antd';
import type { TableProps } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import {
  type Quotation,
  type QuotationGenerateRequest,
  type QuotationPolicy,
  type QuotationPolicyItem,
  superworkApi,
} from '@/services/superwork/api';
import './style.less';

type CustomerForm = {
  customerName: string;
  contactPerson?: string;
  contactPhone?: string;
  contactEmail?: string;
  deliveryPeriod?: string;
  opportunityId?: number;
};
type Selection = {
  isSelected: boolean;
  quantity: number;
  discountRate: number;
};
export type QuotationWizardDefaults = {
  opportunityId?: number;
  opportunityName?: string;
  customerName?: string;
  contactPerson?: string;
  contactPhone?: string;
  contactEmail?: string;
};

const policyTypeLabel: Record<string, string> = {
  SAAS: 'SaaS全渠道',
  PRIVATE_DEPLOYMENT: '私有化全渠道',
  MEMBERSHIP: '会员通',
};
const taxLabel = (value?: string) =>
  value === 'TAX_INCLUDED' ? '含税' : '未税';
const money = (value?: number) =>
  value == null
    ? '—'
    : `¥ ${Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
const taxRate = (value?: number) => {
  const rate = Number(value || 0);
  return rate > 1 ? rate / 100 : rate;
};

export default function QuotationGenerateWizard({
  open,
  onClose,
  onGenerated,
  defaults,
  lockOpportunity = false,
}: {
  open: boolean;
  onClose: () => void;
  onGenerated?: (quotation: Quotation) => void;
  defaults?: QuotationWizardDefaults;
  lockOpportunity?: boolean;
}) {
  const [customerForm] = Form.useForm<CustomerForm>();
  const [wizardStep, setWizardStep] = useState(0);
  const [wizardLoading, setWizardLoading] = useState(false);
  const [policies, setPolicies] = useState<QuotationPolicy[]>([]);
  const [selectedPolicy, setSelectedPolicy] = useState<QuotationPolicy>();
  const [policyItems, setPolicyItems] = useState<QuotationPolicyItem[]>([]);
  const [selections, setSelections] = useState<Record<number, Selection>>({});

  useEffect(() => {
    if (!open) return;
    setWizardStep(0);
    setSelectedPolicy(undefined);
    setPolicyItems([]);
    setSelections({});
    customerForm.setFieldsValue({
      customerName: defaults?.customerName || '',
      contactPerson: defaults?.contactPerson,
      contactPhone: defaults?.contactPhone,
      contactEmail: defaults?.contactEmail,
      deliveryPeriod: undefined,
      opportunityId: defaults?.opportunityId,
    });
    void (async () => {
      try {
        setPolicies(
          await superworkApi.getQuotationPolicies({ status: 'PUBLISHED' }),
        );
      } catch (e) {
        setPolicies([]);
        message.error(e instanceof Error ? e.message : '报价策略加载失败');
      }
    })();
  }, [
    customerForm,
    defaults?.contactEmail,
    defaults?.contactPerson,
    defaults?.contactPhone,
    defaults?.customerName,
    defaults?.opportunityId,
    open,
  ]);

  const choosePolicy = async (policy: QuotationPolicy) => {
    setSelectedPolicy(policy);
    setWizardLoading(true);
    try {
      const items = await superworkApi.getQuotationPolicyItems(policy.id);
      setPolicyItems(items);
      const initial: Record<number, Selection> = {};
      items.forEach((item) => {
        initial[item.id] = {
          isSelected: item.isRequired === 1,
          quantity: 1,
          discountRate: 1,
        };
      });
      setSelections(initial);
    } catch (e) {
      setPolicyItems([]);
      message.error(e instanceof Error ? e.message : '策略明细加载失败');
    } finally {
      setWizardLoading(false);
    }
  };
  const firstYearTotal = useMemo(
    () =>
      policyItems.reduce((total, item) => {
        const sel = selections[item.id];
        if (!sel?.isSelected) return total;
        return (
          total +
          Number(item.unitPrice || 0) *
            sel.quantity *
            sel.discountRate *
            (1 + taxRate(item.taxRate))
        );
      }, 0),
    [policyItems, selections],
  );
  const subsequentYearTotal = useMemo(
    () =>
      policyItems.reduce((total, item) => {
        const sel = selections[item.id];
        if (!sel?.isSelected || item.section !== '运维服务') return total;
        return (
          total +
          Number(item.unitPrice || 0) *
            sel.quantity *
            sel.discountRate *
            (1 + taxRate(item.taxRate))
        );
      }, 0),
    [policyItems, selections],
  );
  const nextWizardStep = async () => {
    if (wizardStep === 0 && !selectedPolicy) {
      message.warning('请先选择报价策略');
      return;
    }
    if (wizardStep === 1) {
      try {
        await customerForm.validateFields();
      } catch {
        return;
      }
    }
    setWizardStep((step) => Math.min(2, step + 1));
  };
  const submitQuotation = async () => {
    if (!selectedPolicy) return;
    const values = await customerForm.validateFields();
    const payload: QuotationGenerateRequest = {
      policyId: selectedPolicy.id,
      ...values,
      customerName: values.customerName.trim(),
      opportunityId: defaults?.opportunityId ?? values.opportunityId,
      lineItemOverrides: policyItems.map((item) => ({
        policyItemId: item.id,
        isSelected: selections[item.id]?.isSelected ?? item.isRequired === 1,
        quantity: selections[item.id]?.quantity || 1,
        discountRate: selections[item.id]?.discountRate ?? 1,
      })),
    };
    setWizardLoading(true);
    try {
      const quotation = await superworkApi.generateQuotation(payload);
      message.success('报价单已生成');
      onGenerated?.(quotation);
      onClose();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '报价单生成失败');
    } finally {
      setWizardLoading(false);
    }
  };
  const itemColumns: TableProps<QuotationPolicyItem>['columns'] = [
    {
      title: '选择',
      width: 60,
      render: (_: unknown, item) => (
        <Checkbox
          checked={selections[item.id]?.isSelected}
          disabled={item.isRequired === 1}
          onChange={(event) =>
            setSelections((current) => ({
              ...current,
              [item.id]: {
                ...current[item.id],
                isSelected: event.target.checked,
              },
            }))
          }
        />
      ),
    },
    {
      title: '明细项',
      dataIndex: 'itemName',
      render: (value, item) => (
        <div>
          <Typography.Text strong>{value}</Typography.Text>
          <Typography.Text type="secondary" className="sw-quotation-subtitle">
            {item.section} · {item.description || '无描述'}
          </Typography.Text>
        </div>
      ),
    },
    {
      title: '价格说明',
      dataIndex: 'priceDescription',
      width: 150,
      render: (value, item) =>
        value ||
        `${money(item.unitPrice)}${item.chargeUnit ? ` / ${item.chargeUnit}` : ''}`,
    },
    {
      title: '数量',
      width: 100,
      render: (_: unknown, item) => (
        <InputNumber
          min={1}
          max={9999}
          value={selections[item.id]?.quantity || 1}
          onChange={(value) =>
            setSelections((current) => ({
              ...current,
              [item.id]: { ...current[item.id], quantity: Number(value || 1) },
            }))
          }
        />
      ),
    },
    {
      title: '折扣率',
      width: 100,
      render: (_: unknown, item) => (
        <InputNumber
          min={0}
          max={1}
          step={0.05}
          value={selections[item.id]?.discountRate ?? 1}
          onChange={(value) =>
            setSelections((current) => ({
              ...current,
              [item.id]: {
                ...current[item.id],
                discountRate: Number(value ?? 1),
              },
            }))
          }
        />
      ),
    },
  ];

  return (
    <Modal
      title="新建报价单"
      open={open}
      width={1040}
      destroyOnHidden
      onCancel={onClose}
      footer={
        <Space>
          <Button onClick={onClose}>取消</Button>
          {wizardStep > 0 && (
            <Button onClick={() => setWizardStep((step) => step - 1)}>
              上一步
            </Button>
          )}
          {wizardStep < 2 ? (
            <Button type="primary" onClick={() => void nextWizardStep()}>
              下一步
            </Button>
          ) : (
            <Button
              type="primary"
              loading={wizardLoading}
              onClick={() => void submitQuotation()}
            >
              生成报价单
            </Button>
          )}
        </Space>
      }
    >
      <Steps
        current={wizardStep}
        items={[
          { title: '选择报价策略' },
          { title: '客户信息' },
          { title: '配置明细' },
        ]}
      />
      <div className="sw-quotation-wizard">
        {wizardStep === 0 && (
          <div className="sw-policy-grid">
            {policies.map((policy) => (
              <Card
                key={policy.id}
                hoverable
                onClick={() => void choosePolicy(policy)}
                className={
                  selectedPolicy?.id === policy.id
                    ? 'sw-policy-card is-selected'
                    : 'sw-policy-card'
                }
                loading={wizardLoading && selectedPolicy?.id === policy.id}
              >
                <Space orientation="vertical" size={4}>
                  <Space>
                    <Tag color="blue">
                      {policyTypeLabel[policy.type] || policy.type}
                    </Tag>
                    <Tag>{taxLabel(policy.taxMode)}</Tag>
                  </Space>
                  <Typography.Text strong>{policy.name}</Typography.Text>
                  <Typography.Text type="secondary">
                    V{policy.version} · 生效{' '}
                    {policy.effectiveDate?.slice(0, 10) || '未设置'}
                  </Typography.Text>
                </Space>
              </Card>
            ))}
            {!policies.length && <Empty description="暂无已发布报价策略" />}
          </div>
        )}
        {wizardStep === 1 && (
          <Form form={customerForm} layout="vertical">
            <Form.Item
              name="customerName"
              label="委托方 / 品牌公司名称"
              rules={[
                { required: true, message: '请输入委托方或品牌公司名称' },
              ]}
            >
              <Input placeholder="请输入公司全称" />
            </Form.Item>
            <Row gutter={14}>
              <Col span={12}>
                <Form.Item name="contactPerson" label="项目负责人">
                  <Input />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item name="contactPhone" label="电话">
                  <Input />
                </Form.Item>
              </Col>
            </Row>
            <Row gutter={14}>
              <Col span={12}>
                <Form.Item name="contactEmail" label="邮件">
                  <Input />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item name="deliveryPeriod" label="交货期">
                  <Input placeholder="如：30个工作日" />
                </Form.Item>
              </Col>
            </Row>
            {lockOpportunity ? (
              <Form.Item label="关联商机">
                <Typography.Text>
                  {defaults?.opportunityName ||
                    `商机 #${defaults?.opportunityId || '—'}`}
                </Typography.Text>
                <Form.Item name="opportunityId" style={{ display: 'none' }}>
                  <InputNumber />
                </Form.Item>
              </Form.Item>
            ) : (
              <Form.Item name="opportunityId" label="关联商机ID">
                <InputNumber
                  min={1}
                  style={{ width: '100%' }}
                  placeholder="可选"
                />
              </Form.Item>
            )}
          </Form>
        )}
        {wizardStep === 2 && (
          <div>
            <Table
              rowKey="id"
              size="small"
              loading={wizardLoading}
              columns={itemColumns}
              dataSource={policyItems}
              pagination={false}
              scroll={{ x: 850 }}
              locale={{ emptyText: <Empty description="该策略暂无明细项" /> }}
            />
            <Divider />
            <div className="sw-quotation-totals">
              <div>
                <Typography.Text type="secondary">首年含税总价</Typography.Text>
                <Typography.Title level={3}>
                  {money(firstYearTotal)}
                </Typography.Title>
              </div>
              <div>
                <Typography.Text type="secondary">
                  次年及以后含税总价
                </Typography.Text>
                <Typography.Title level={3}>
                  {money(subsequentYearTotal)}
                </Typography.Title>
              </div>
            </div>
          </div>
        )}
      </div>
    </Modal>
  );
}
