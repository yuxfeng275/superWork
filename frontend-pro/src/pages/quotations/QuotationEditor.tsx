import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  CopyOutlined,
  DownloadOutlined,
  PlusOutlined,
  SaveOutlined,
  SendOutlined,
  StarOutlined,
} from '@ant-design/icons';
import { history } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Divider,
  Empty,
  Input,
  InputNumber,
  message,
  Modal,
  Row,
  Select,
  Space,
  Spin,
  Switch,
  Table,
  Tag,
  Typography,
} from 'antd';
import type { TableProps } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import {
  type Quotation,
  type QuotationLineItem,
  type QuotationPricingResult,
  superworkApi,
} from '@/services/superwork/api';
import '../workbench/style.less';
import './style.less';

const money = (value?: number) =>
  value == null
    ? '—'
    : `¥ ${Number(value).toLocaleString('zh-CN', {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2,
      })}`;
const pct = (value?: number) =>
  value == null ? '—' : `${(Number(value) * 100).toFixed(1)}%`;

type Draft = QuotationLineItem & { dirty?: boolean };

/**
 * 报价单编辑工作台：行级可编辑（单价/数量/折扣/自定义行/首年免费），
 * 金额全部由后端 /api/quotations/preview 计算，前端只做展示，避免两套算法分叉。
 */
export default function QuotationEditor({ quotationId }: { quotationId: number }) {
  const [quotation, setQuotation] = useState<Quotation>();
  const [rows, setRows] = useState<Draft[]>([]);
  const [pricing, setPricing] = useState<QuotationPricingResult>();
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const detail = await superworkApi.getQuotationDetail(quotationId);
      setQuotation(detail);
      setRows((detail.lineItems || []).map((item) => ({ ...item })));
      setPricing({
        lineItems: detail.lineItems || [],
        firstYearTotalExTax: detail.firstYearTotalExTax,
        firstYearTotalInclTax: detail.firstYearTotalInclTax,
        subsequentYearTotalExTax: detail.subsequentYearTotalExTax,
        subsequentYearTotalInclTax: detail.subsequentYearTotalInclTax,
        grossMarginRate: detail.grossMarginRate,
        discountOverride: detail.discountOverride === 1,
        violations: [],
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : '报价单加载失败');
    } finally {
      setLoading(false);
    }
  }, [quotationId]);

  useEffect(() => {
    void load();
  }, [load]);

  const editable = quotation?.status === 'DRAFT' || quotation?.status === 'INTERNAL_REVIEW';

  const preview = useCallback(
    async (lineItems: Draft[]) => {
      if (!quotation?.policyId) return;
      try {
        const result = await superworkApi.previewQuotation({
          policyId: quotation.policyId,
          customerName: quotation.customerName || '',
          lineItemOverrides: lineItems.map((item) => ({
            lineItemId: item.id || undefined,
            policyItemId: item.policyItemId,
            isSelected: item.isSelected === 1,
            quantity: item.quantity,
            discountRate: item.discountRate,
            unitPriceExTax: item.unitPriceExTax,
            costPriceExTax: item.costPriceExTax,
            isCustom: item.isCustom === 1,
            isFirstYearFree: item.isFirstYearFree === 1,
            priceOverrideReason: item.priceOverrideReason,
            section: item.section,
            itemName: item.itemName,
            description: item.description,
            sortOrder: item.sortOrder,
          })),
        });
        setPricing(result);
        setRows(
          result.lineItems.map((item) => ({
            ...item,
            quantity: item.quantity ?? 1,
            discountRate: item.discountRate ?? 1,
          }))
        );
      } catch (e) {
        message.error(e instanceof Error ? e.message : '试算失败');
      }
    },
    [quotation?.policyId, quotation?.customerName]
  );

  const updateRow = (id: number, patch: Partial<Draft>) => {
    setRows((current) =>
      current.map((row) => (row.id === id ? { ...row, ...patch, dirty: true } : row))
    );
  };

  const commitAndPreview = () => void preview(rows);

  const addCustomRow = () => {
    const draft: Draft = {
      id: -Date.now(),
      quotationId,
      section: '定制开发',
      itemName: '自定义项',
      isSelected: 1,
      isCustom: 1,
      isFirstYearFree: 0,
      quantity: 1,
      discountRate: 1,
      unitPriceExTax: 0,
      taxRate: 0.06,
      sortOrder: rows.length,
    };
    setRows([...rows, draft]);
  };

  const removeRow = (id: number) => {
    setRows(rows.filter((row) => row.id !== id));
  };

  const save = async () => {
    if (!quotation) return;
    setSaving(true);
    try {
      await superworkApi.updateQuotation(quotationId, {
        customerName: quotation.customerName,
        contactPerson: quotation.contactPerson,
        contactPhone: quotation.contactPhone,
        contactEmail: quotation.contactEmail,
        deliveryPeriod: quotation.deliveryPeriod,
        quoteDate: quotation.quoteDate,
        validityDays: quotation.validityDays,
        quotationNote: quotation.quotationNote,
        lineItemOverrides: rows.map((item) => ({
          lineItemId: item.id > 0 ? item.id : undefined,
          policyItemId: item.policyItemId,
          isSelected: item.isSelected === 1,
          quantity: item.quantity,
          discountRate: item.discountRate,
          unitPriceExTax: item.unitPriceExTax,
          costPriceExTax: item.costPriceExTax,
          isCustom: item.isCustom === 1,
          isFirstYearFree: item.isFirstYearFree === 1,
          priceOverrideReason: item.priceOverrideReason,
          section: item.section,
          itemName: item.itemName,
          description: item.description,
          sortOrder: item.sortOrder,
        })),
      });
      message.success('报价单已保存');
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const transition = async (status: string) => {
    try {
      await superworkApi.updateQuotationStatus(quotationId, status);
      message.success('状态已更新');
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '状态更新失败');
    }
  };

  const createNewVersion = async () => {
    try {
      const created = await superworkApi.createQuotationVersion(quotationId);
      message.success(`已生成新版本 ${created.quotationNo}`);
      history.push(`/quotations/${created.id}`);
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '生成版本失败');
    }
  };

  const setPreferred = async () => {
    try {
      await superworkApi.setQuotationPreferred(quotationId);
      message.success('已设为该商机的首选方案');
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '设置首选失败');
    }
  };

  const exportExcel = async () => {
    if (!quotation) return;
    try {
      const blob = await superworkApi.exportQuotation(quotationId);
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = `报价单_${quotation.quotationNo}.xlsx`;
      anchor.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '导出失败');
    }
  };

  const requestDiscount = () => {
    let reason = '';
    Modal.confirm({
      title: '提交折扣审批',
      content: (
        <Input.TextArea
          rows={3}
          placeholder="请说明折扣原因，如竞品对标、客户预算限制"
          onChange={(e) => {
            reason = e.target.value;
          }}
        />
      ),
      onOk: async () => {
        await superworkApi.requestQuotationDiscount(quotationId, {
          requestedDiscount: pricing?.effectiveDiscount,
          reason,
        });
        message.success('折扣审批已提交');
        await load();
      },
    });
  };

  const columns: TableProps<Draft>['columns'] = [
    {
      title: '启用',
      width: 60,
      render: (_, row) => (
        <Switch
          size="small"
          checked={row.isSelected === 1}
          disabled={!editable}
          onChange={(checked) => updateRow(row.id, { isSelected: checked ? 1 : 0 })}
        />
      ),
    },
    {
      title: '分组 / 明细',
      render: (_, row) => (
        <div>
          <Typography.Text strong>
            {row.itemName}
            {row.isCustom === 1 && (
              <Tag color="purple" style={{ marginLeft: 6 }}>
                自定义
              </Tag>
            )}
          </Typography.Text>
          <Typography.Text type="secondary" className="sw-quotation-subtitle">
            {row.section}
            {row.description ? ` · ${row.description}` : ''}
          </Typography.Text>
        </div>
      ),
    },
    {
      title: '数量',
      width: 90,
      render: (_, row) => (
        <InputNumber
          min={0}
          step={1}
          disabled={!editable}
          value={row.quantity}
          onChange={(value) => updateRow(row.id, { quantity: Number(value || 0) })}
        />
      ),
    },
    {
      title: '未税单价',
      width: 130,
      render: (_, row) =>
        row.chargeMethod === 'PERCENT_OF_BASE' ? (
          <Typography.Text type="secondary">
            {pct(row.unitPriceExTax)} × {row.category || '基数'}
          </Typography.Text>
        ) : (
          <InputNumber
            min={0}
            disabled={!editable}
            value={row.unitPriceExTax}
            onChange={(value) => updateRow(row.id, { unitPriceExTax: Number(value || 0) })}
          />
        ),
    },
    {
      title: '折扣',
      width: 90,
      render: (_, row) => (
        <InputNumber
          min={0}
          max={1}
          step={0.05}
          disabled={!editable}
          value={row.discountRate}
          onChange={(value) => updateRow(row.id, { discountRate: Number(value ?? 1) })}
        />
      ),
    },
    {
      title: '首年免收费',
      width: 90,
      render: (_, row) => (
        <Switch
          size="small"
          checked={row.isFirstYearFree === 1}
          disabled={!editable}
          onChange={(checked) => updateRow(row.id, { isFirstYearFree: checked ? 1 : 0 })}
        />
      ),
    },
    {
      title: '首年小计',
      width: 130,
      render: (_, row) => <Typography.Text>{money(row.subtotalInclTax)}</Typography.Text>,
    },
    {
      title: '',
      width: 70,
      render: (_, row) =>
        editable && row.isCustom === 1 ? (
          <Button type="link" danger size="small" onClick={() => removeRow(row.id)}>
            删除
          </Button>
        ) : null,
    },
  ];

  const violations = pricing?.violations || [];
  const margin = pricing?.grossMarginRate ?? quotation?.grossMarginRate;

  if (loading) {
    return (
      <div className="sw-page sw-quotations">
        <Spin />
      </div>
    );
  }

  if (error || !quotation) {
    return (
      <div className="sw-page sw-quotations">
        <Alert type="error" showIcon message="无法打开报价单" description={error} />
      </div>
    );
  }

  return (
    <div className="sw-page sw-quotations">
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">SALES / QUOTATION EDITOR</Typography.Text>
          <Typography.Title level={2}>
            {quotation.quotationNo}
            {quotation.isPreferred === 1 && (
              <Tag color="gold" style={{ marginLeft: 8 }}>
                首选方案
              </Tag>
            )}
          </Typography.Title>
          <Typography.Paragraph type="secondary">
            {quotation.variantName || '默认方案'} · V{quotation.versionNo || 1} ·{' '}
            {quotation.opportunityName || '未关联商机'}
          </Typography.Paragraph>
        </div>
        <Space wrap>
          <Button icon={<ArrowLeftOutlined />} onClick={() => history.push('/quotations')}>
            返回
          </Button>
          {editable && (
            <Button icon={<SaveOutlined />} loading={saving} onClick={() => void save()}>
              保存草稿
            </Button>
          )}
          <Button icon={<CopyOutlined />} onClick={() => void createNewVersion()}>
            生成新版本
          </Button>
          {quotation.opportunityId && quotation.isPreferred !== 1 && (
            <Button icon={<StarOutlined />} onClick={() => void setPreferred()}>
              设为首选
            </Button>
          )}
          <Button icon={<DownloadOutlined />} onClick={() => void exportExcel()}>
            导出 Excel
          </Button>
          {editable && quotation.status === 'DRAFT' && (
            <Button
              type="primary"
              icon={<SendOutlined />}
              onClick={() => void transition('INTERNAL_REVIEW')}
            >
              提交审核
            </Button>
          )}
          {quotation.status === 'INTERNAL_REVIEW' && (
            <>
              <Button type="primary" icon={<CheckCircleOutlined />} onClick={() => void transition('APPROVED')}>
                批准
              </Button>
              <Button onClick={() => void transition('DRAFT')}>退回</Button>
            </>
          )}
          {quotation.status === 'APPROVED' && (
            <Button type="primary" onClick={() => void transition('SENT')}>
              标记已发送
            </Button>
          )}
          {quotation.status === 'SENT' && (
            <>
              <Button type="primary" onClick={() => void transition('ACCEPTED')}>
                客户已接受
              </Button>
              <Button danger onClick={() => void transition('REJECTED')}>
                客户已拒绝
              </Button>
            </>
          )}
        </Space>
      </div>

      {violations.length > 0 && (
        <Alert
          type="warning"
          showIcon
          message="报价校验提示"
          description={
            <ul style={{ margin: 0, paddingLeft: 18 }}>
              {violations.map((v) => (
                <li key={v}>{v}</li>
              ))}
            </ul>
          }
          action={
            pricing?.discountOverride ? (
              <Button size="small" onClick={requestDiscount}>
                提交折扣审批
              </Button>
            ) : undefined
          }
        />
      )}

      <Row gutter={16}>
        <Col xs={24} xl={17}>
          <Card
            variant="borderless"
            title="报价明细"
            extra={
              editable && (
                <Space>
                  <Button size="small" icon={<PlusOutlined />} onClick={addCustomRow}>
                    新增自定义行
                  </Button>
                  <Button size="small" onClick={commitAndPreview}>
                    重新试算
                  </Button>
                </Space>
              )
            }
          >
            <Table
              rowKey="id"
              size="small"
              columns={columns}
              dataSource={rows}
              pagination={false}
              scroll={{ x: 1050 }}
              locale={{ emptyText: <Empty description="暂无报价明细" /> }}
            />
          </Card>
        </Col>
        <Col xs={24} xl={7}>
          <Card variant="borderless" title="报价汇总">
            <Space direction="vertical" size={6} style={{ width: '100%' }}>
              <div>
                <Typography.Text type="secondary">首年未税</Typography.Text>
                <Typography.Title level={4}>
                  {money(pricing?.firstYearTotalExTax)}
                </Typography.Title>
              </div>
              <div>
                <Typography.Text type="secondary">首年含税</Typography.Text>
                <Typography.Title level={4}>
                  {money(pricing?.firstYearTotalInclTax)}
                </Typography.Title>
              </div>
              <div>
                <Typography.Text type="secondary">次年及以后（含税）</Typography.Text>
                <Typography.Title level={5}>
                  {money(pricing?.subsequentYearTotalInclTax)}
                </Typography.Title>
              </div>
              <div>
                <Typography.Text type="secondary">最低折扣 / 底价</Typography.Text>
                <div>
                  <Tag color={pricing?.discountOverride ? 'error' : 'default'}>
                    {pct(pricing?.effectiveDiscount)}
                  </Tag>
                  <Typography.Text type="secondary">
                    底价 {pct(pricing?.floorDiscount)}
                  </Typography.Text>
                </div>
              </div>
              <div>
                <Typography.Text type="secondary">首年毛利率</Typography.Text>
                <div>
                  <Tag color={(margin ?? 1) < 0.3 ? 'warning' : 'success'}>{pct(margin)}</Tag>
                </div>
              </div>
            </Space>
          </Card>
          <Divider />
          <Descriptions column={1} size="small" bordered>
            <Descriptions.Item label="客户">{quotation.customerName || '—'}</Descriptions.Item>
            <Descriptions.Item label="状态">
              <Tag>{quotation.status}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="报价日期">
              {quotation.quoteDate?.slice(0, 10) || '—'}
            </Descriptions.Item>
            <Descriptions.Item label="有效期">
              {quotation.validityDays || 30} 天
            </Descriptions.Item>
            <Descriptions.Item label="计税模式">
              {quotation.taxMode === 'TAX_INCLUDED' ? '含税' : '未税'}
            </Descriptions.Item>
          </Descriptions>
          {editable && (
            <Select
              style={{ width: '100%', marginTop: 12 }}
              placeholder="有效期"
              value={String(quotation.validityDays || 30)}
              onChange={(value) =>
                setQuotation({ ...quotation, validityDays: Number(value) })
              }
              options={[15, 30, 45, 60, 90].map((d) => ({
                label: `${d} 天`,
                value: String(d),
              }))}
            />
          )}
        </Col>
      </Row>
    </div>
  );
}
