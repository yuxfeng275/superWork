import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  ExclamationCircleOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons';
import type { TableProps } from 'antd';
import {
  Alert,
  App,
  Button,
  Card,
  Collapse,
  Input,
  Modal,
  Popconfirm,
  Space,
  Spin,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ApiRequestError,
  type OaAffair,
  type OaAffairAction,
  type OaBatchApproveResult,
  type OaCaptchaChallenge,
  type OaSessionStatus,
  superworkApi,
} from '@/services/superwork/api';
import '../workbench/style.less';
import './style.less';

type AffairColumns = NonNullable<TableProps<OaAffair>['columns']>;
type AffairTab = 'pending' | 'done';
/** checking：打开弹窗后自动尝试免验证码登录；captcha：需要人工输验证码。 */
type AuthStep = 'checking' | 'captcha';

const ACTION_LABELS: Record<OaAffairAction, string> = {
  approve: '同意',
  reject: '驳回',
};

const errorText = (e: unknown, fallback: string) =>
  e instanceof Error && e.message ? e.message : fallback;

/** 会话失效：后端 400 且提示重新授权/会话失效时，引导重新走自助授权。 */
const isSessionExpired = (e: unknown) =>
  e instanceof ApiRequestError &&
  e.status === 400 &&
  /授权|JSESSIONID|会话/.test(e.message || '');

const fmtTime = (value?: string) => {
  if (!value) return '—';
  const parsed = dayjs(value);
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm') : value;
};

export default function OaAffairsPage() {
  const { message } = App.useApp();

  const [session, setSession] = useState<OaSessionStatus>();
  const [sessionLoading, setSessionLoading] = useState(true);
  const [sessionError, setSessionError] = useState('');
  const [tab, setTab] = useState<AffairTab>('pending');
  const [pending, setPending] = useState<OaAffair[]>([]);
  const [done, setDone] = useState<OaAffair[]>([]);
  const [listLoading, setListLoading] = useState(false);
  const [listError, setListError] = useState('');
  const [selected, setSelected] = useState<string[]>([]);
  const [actingId, setActingId] = useState<string>();

  const [authOpen, setAuthOpen] = useState(false);
  const [authAttempt, setAuthAttempt] = useState(0);
  const [authStep, setAuthStep] = useState<AuthStep>('checking');
  const [authHint, setAuthHint] = useState('');
  const [authError, setAuthError] = useState('');
  const [challenge, setChallenge] = useState<OaCaptchaChallenge>();
  const [captcha, setCaptcha] = useState('');
  const [captchaLoading, setCaptchaLoading] = useState(false);
  const [logging, setLogging] = useState(false);
  const [cookie, setCookie] = useState('');
  const [authorizing, setAuthorizing] = useState(false);
  const [pasteOpen, setPasteOpen] = useState(false);
  const autoStartedRef = useRef(0);

  const [batchAction, setBatchAction] = useState<OaAffairAction>();
  const [batchSize, setBatchSize] = useState(0);
  const [batchTitles, setBatchTitles] = useState<Record<string, string>>({});
  const [batchResults, setBatchResults] = useState<OaBatchApproveResult[]>([]);
  const [batchError, setBatchError] = useState('');
  const [batchRunning, setBatchRunning] = useState(false);

  const authorized = session?.authorized === true;

  const openAuthModal = useCallback((hint?: string) => {
    setCookie('');
    setAuthError('');
    setAuthHint('');
    setChallenge(undefined);
    setCaptcha('');
    setAuthStep('checking');
    setPasteOpen(false);
    setAuthOpen(true);
    setAuthAttempt((count) => count + 1);
    if (hint) setSession({ authorized: false, hint });
  }, []);

  const loadSession = useCallback(async () => {
    setSessionLoading(true);
    setSessionError('');
    try {
      const status = await superworkApi.getOaSessionStatus();
      setSession(status);
      return status;
    } catch (e) {
      setSession(undefined);
      setSessionError(errorText(e, '授权状态读取失败'));
      return undefined;
    } finally {
      setSessionLoading(false);
    }
  }, []);

  const loadAffairs = useCallback(async () => {
    setListLoading(true);
    setListError('');
    try {
      const [pendingRows, doneRows] = await Promise.all([
        superworkApi.getOaPendingAffairs(),
        superworkApi.getOaDoneAffairs(),
      ]);
      setPending(pendingRows || []);
      setDone(doneRows || []);
    } catch (e) {
      setPending([]);
      setDone([]);
      if (isSessionExpired(e))
        openAuthModal(errorText(e, 'OA 会话已失效，请重新授权'));
      else setListError(errorText(e, 'OA 待办加载失败'));
    } finally {
      setListLoading(false);
      setSelected([]);
    }
  }, [openAuthModal]);

  const refresh = useCallback(async () => {
    const status = await loadSession();
    if (status?.authorized) {
      await loadAffairs();
      return;
    }
    setPending([]);
    setDone([]);
    setSelected([]);
  }, [loadAffairs, loadSession]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  /** 授权成功：落状态、关弹窗、刷新待办。 */
  const completeAuth = useCallback(
    async (status: OaSessionStatus) => {
      setSession(status);
      setAuthOpen(false);
      setCookie('');
      setCaptcha('');
      message.success(status.hint || '授权成功');
      await loadAffairs();
    },
    [loadAffairs, message],
  );

  /** 拉取验证码图片；challengeId 一次性，登录失败后必须重新拉取。 */
  const loadCaptcha = useCallback(async () => {
    setCaptchaLoading(true);
    try {
      setChallenge(await superworkApi.getOaCaptcha());
      setCaptcha('');
    } catch (e) {
      setChallenge(undefined);
      setAuthError(
        `验证码加载失败：${errorText(e, '请改用下方粘贴 JSESSIONID 授权')}`,
      );
    } finally {
      setCaptchaLoading(false);
    }
  }, []);

  /** 打开弹窗后的第一档：不带验证码的后台自动登录。 */
  const runAutoAuth = useCallback(async () => {
    setAuthStep('checking');
    setAuthError('');
    setAuthHint('');
    try {
      const status = await superworkApi.autoOaLogin();
      if (status.authorized) {
        await completeAuth(status);
        return;
      }
      setAuthHint(status.hint || '自动授权未完成，请输入验证码后登录');
    } catch (e) {
      setAuthError(
        `自动授权失败：${errorText(e, '请改用验证码或粘贴 JSESSIONID 授权')}`,
      );
    }
    setAuthStep('captcha');
    await loadCaptcha();
  }, [completeAuth, loadCaptcha]);

  useEffect(() => {
    if (!authOpen || autoStartedRef.current === authAttempt) return;
    autoStartedRef.current = authAttempt;
    void runAutoAuth();
  }, [authAttempt, authOpen, runAutoAuth]);

  /** 第二档：账号密码（连接器配置）+ 验证码。 */
  const submitCaptcha = async () => {
    const code = captcha.trim();
    if (!code) {
      setAuthError('请输入图中验证码');
      return;
    }
    setLogging(true);
    setAuthError('');
    try {
      const status = await superworkApi.loginOaSession({
        challengeId: challenge?.challengeId,
        captcha: code,
      });
      if (status.authorized) {
        await completeAuth(status);
        return;
      }
      setAuthHint(status.hint || '登录未通过，请重试');
    } catch (e) {
      setAuthError(errorText(e, '登录失败：账号密码或验证码错误'));
    } finally {
      setLogging(false);
    }
    await loadCaptcha();
  };

  /** 第三档兜底：粘贴浏览器里已登录 OA 的 JSESSIONID。 */
  const authorize = async () => {
    const value = cookie.trim();
    if (!value) {
      setAuthError('请先粘贴浏览器里的 JSESSIONID');
      setPasteOpen(true);
      return;
    }
    setAuthorizing(true);
    setAuthError('');
    try {
      await completeAuth(await superworkApi.authorizeOaSession(value));
    } catch (e) {
      setAuthError(errorText(e, '授权失败：JSESSIONID 无效或已过期'));
    } finally {
      setAuthorizing(false);
    }
  };

  const clearSession = async () => {
    try {
      await superworkApi.clearOaSession();
      setPending([]);
      setDone([]);
      setSelected([]);
      await loadSession();
      message.success('已清除 OA 网页会话授权');
    } catch (e) {
      message.error(errorText(e, '清除授权失败'));
    }
  };

  const subjectById = useMemo(
    () => Object.fromEntries(pending.map((row) => [row.id, row.subject])),
    [pending],
  );
  const allSelected = pending.length > 0 && selected.length === pending.length;
  const batchSuccess = batchResults.filter((row) => row.success).length;
  const batchFailed = batchResults.length - batchSuccess;
  const batchExpired = batchResults.some(
    (row) => !row.success && /授权|JSESSIONID|会话/.test(row.result || ''),
  );

  const act = async (affair: OaAffair, action: OaAffairAction) => {
    setActingId(affair.id);
    try {
      const result = await superworkApi.approveOaAffair(affair.id, action);
      message.success(
        result
          ? `${result}：${affair.subject || affair.id}`
          : `已${ACTION_LABELS[action]}：${affair.subject || affair.id}`,
      );
      await loadAffairs();
    } catch (e) {
      if (isSessionExpired(e))
        openAuthModal(errorText(e, 'OA 会话已失效，请重新授权'));
      else message.error(errorText(e, `${ACTION_LABELS[action]}失败`));
    } finally {
      setActingId(undefined);
    }
  };

  const openBatch = (action: OaAffairAction) => {
    setBatchAction(action);
    setBatchSize(selected.length);
    setBatchTitles(subjectById);
    setBatchResults([]);
    setBatchError('');
  };

  const closeBatch = () => {
    setBatchAction(undefined);
    setBatchResults([]);
    setBatchError('');
  };

  const runBatch = async () => {
    if (!batchAction) return;
    setBatchRunning(true);
    setBatchError('');
    try {
      const results =
        (await superworkApi.batchApproveOaAffairs(selected, batchAction)) || [];
      setBatchResults(results);
      const failed = results.filter((row) => !row.success).length;
      if (failed)
        message.warning(
          `批量${ACTION_LABELS[batchAction]}完成：成功 ${results.length - failed} 项，失败 ${failed} 项`,
        );
      else
        message.success(
          `批量${ACTION_LABELS[batchAction]}完成：共 ${results.length} 项`,
        );
      await loadAffairs();
    } catch (e) {
      if (isSessionExpired(e)) {
        closeBatch();
        openAuthModal(errorText(e, 'OA 会话已失效，请重新授权'));
      } else {
        setBatchError(errorText(e, `批量${ACTION_LABELS[batchAction]}失败`));
      }
    } finally {
      setBatchRunning(false);
    }
  };

  const baseColumns: AffairColumns = [
    {
      title: '标题',
      dataIndex: 'subject',
      render: (value: string) => (
        <Typography.Text strong>{value || '（无标题）'}</Typography.Text>
      ),
    },
    {
      title: '发起人',
      dataIndex: 'senderName',
      width: 140,
      render: (value: string) => value || '—',
    },
    {
      title: '接收时间',
      dataIndex: 'createDate',
      width: 180,
      render: (value: string) => fmtTime(value),
    },
    {
      title: '类型',
      dataIndex: 'appName',
      width: 170,
      render: (value: string, row) => value || row.state || '—',
    },
  ];
  const pendingColumns: AffairColumns = [
    ...baseColumns,
    {
      title: '操作',
      key: 'action',
      width: 170,
      render: (_: unknown, row) => (
        <Space size={0}>
          <Popconfirm
            title={`确认同意「${row.subject || row.id}」？`}
            okText="同意"
            cancelText="取消"
            onConfirm={() => void act(row, 'approve')}
          >
            <Button
              type="link"
              size="small"
              icon={<CheckCircleOutlined />}
              loading={actingId === row.id}
            >
              同意
            </Button>
          </Popconfirm>
          <Popconfirm
            title={`确认驳回「${row.subject || row.id}」？`}
            okText="驳回"
            cancelText="取消"
            okButtonProps={{ danger: true }}
            onConfirm={() => void act(row, 'reject')}
          >
            <Button
              type="link"
              size="small"
              danger
              icon={<CloseCircleOutlined />}
              loading={actingId === row.id}
            >
              驳回
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const renderAffairs = (rows: OaAffair[], selectable: boolean) => {
    if (sessionLoading) return <Spin className="sw-oa-loading" />;
    if (sessionError)
      return (
        <Alert
          type="error"
          showIcon
          message="授权状态读取失败"
          description={sessionError}
          action={
            <Button size="small" onClick={() => void refresh()}>
              重试
            </Button>
          }
        />
      );
    if (!authorized)
      return (
        <Alert
          type="warning"
          showIcon
          message="尚未完成 OA 网页会话授权"
          description="致远网关会拦截 REST 取数：点「去授权」会自动登录，要求验证码时按图输入即可，也可粘贴浏览器里的 JSESSIONID 兜底；授权后待办列表与批量审批即可使用。"
          action={
            <Button type="primary" size="small" onClick={() => openAuthModal()}>
              去授权
            </Button>
          }
        />
      );
    if (listError)
      return (
        <Alert
          type="error"
          showIcon
          message="待办加载失败"
          description={listError}
          action={
            <Button size="small" onClick={() => void refresh()}>
              重试
            </Button>
          }
        />
      );
    return (
      <Table<OaAffair>
        rowKey="id"
        loading={listLoading}
        columns={selectable ? pendingColumns : baseColumns}
        dataSource={rows}
        rowSelection={
          selectable
            ? {
                selectedRowKeys: selected,
                onChange: (keys) => setSelected(keys.map(String)),
              }
            : undefined
        }
        pagination={{
          pageSize: 20,
          showSizeChanger: false,
          hideOnSinglePage: true,
        }}
        locale={{ emptyText: selectable ? '暂无待办事项' : '暂无已办事项' }}
      />
    );
  };

  const toolbar = (
    <Space wrap>
      {tab === 'pending' && (
        <>
          <Typography.Text type="secondary">
            已选 {selected.length} 项
          </Typography.Text>
          <Button
            disabled={!authorized || !pending.length}
            onClick={() =>
              setSelected(allSelected ? [] : pending.map((row) => row.id))
            }
          >
            {allSelected ? '取消全选' : '全选'}
          </Button>
          <Button
            type="primary"
            icon={<CheckCircleOutlined />}
            disabled={!selected.length}
            onClick={() => openBatch('approve')}
          >
            批量同意
          </Button>
          <Button
            danger
            icon={<CloseCircleOutlined />}
            disabled={!selected.length}
            onClick={() => openBatch('reject')}
          >
            批量驳回
          </Button>
        </>
      )}
      <Button
        icon={<ReloadOutlined />}
        loading={sessionLoading || listLoading}
        onClick={() => void refresh()}
      >
        刷新
      </Button>
    </Space>
  );

  return (
    <div className="sw-page sw-oa-affairs">
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">
            WORKBENCH / OA AFFAIRS
          </Typography.Text>
          <Typography.Title level={2}>OA 待办审批台</Typography.Title>
          <Typography.Paragraph type="secondary">
            当前授权账号在致远 OA
            的待办与已办事项；可逐项或勾选后批量同意/驳回，逐项执行并展示结果。
          </Typography.Paragraph>
        </div>
      </div>

      <Card
        className="sw-oa-session"
        variant="borderless"
        title={
          <Space>
            <SafetyCertificateOutlined />
            <span>网页会话授权</span>
          </Space>
        }
        extra={
          authorized ? (
            <Space>
              <Button onClick={() => openAuthModal()}>重新授权</Button>
              <Popconfirm
                title="清除 OA 网页会话授权？"
                description="清除后需重新授权（自动登录 / 验证码 / 粘贴 JSESSIONID）才能取数与审批"
                okText="清除"
                cancelText="取消"
                okButtonProps={{ danger: true }}
                onConfirm={() => void clearSession()}
              >
                <Button type="link" danger>
                  清除授权
                </Button>
              </Popconfirm>
            </Space>
          ) : (
            <Button
              type="primary"
              disabled={sessionLoading}
              onClick={() => openAuthModal()}
            >
              去授权
            </Button>
          )
        }
      >
        {session ? (
          <Space size={12} wrap>
            <Tag
              color={authorized ? 'success' : 'warning'}
              icon={
                authorized ? (
                  <CheckCircleOutlined />
                ) : (
                  <ExclamationCircleOutlined />
                )
              }
            >
              {authorized ? '已授权' : '未授权'}
            </Tag>
            <Typography.Text type="secondary">
              {session.hint || (authorized ? '已授权（网页会话）' : '未授权')}
            </Typography.Text>
          </Space>
        ) : sessionLoading ? (
          <Spin size="small" />
        ) : (
          <Typography.Text type="secondary">{sessionError}</Typography.Text>
        )}
      </Card>

      <Card className="sw-oa-list" variant="borderless">
        <Tabs
          activeKey={tab}
          onChange={(key) => setTab(key as AffairTab)}
          tabBarExtraContent={toolbar}
          items={[
            {
              key: 'pending',
              label: authorized ? `待办（${pending.length}）` : '待办',
              children: renderAffairs(pending, true),
            },
            {
              key: 'done',
              label: authorized ? `已办（${done.length}）` : '已办',
              children: renderAffairs(done, false),
            },
          ]}
        />
      </Card>

      <Modal
        title={
          batchAction
            ? `批量${ACTION_LABELS[batchAction]}（${batchSize} 项）`
            : ''
        }
        open={Boolean(batchAction)}
        onCancel={closeBatch}
        maskClosable={!batchRunning}
        width={560}
        footer={
          batchResults.length ? (
            <Button type="primary" onClick={closeBatch}>
              关闭
            </Button>
          ) : (
            <Space>
              <Button onClick={closeBatch}>取消</Button>
              <Button
                type="primary"
                danger={batchAction === 'reject'}
                loading={batchRunning}
                onClick={() => void runBatch()}
              >
                确认{batchAction ? ACTION_LABELS[batchAction] : ''}
              </Button>
            </Space>
          )
        }
      >
        <Space orientation="vertical" size={12} className="sw-oa-batch">
          {batchResults.length ? (
            <>
              <Space>
                <Tag color="success">成功 {batchSuccess}</Tag>
                <Tag color="error">失败 {batchFailed}</Tag>
              </Space>
              {batchExpired && (
                <Alert
                  type="warning"
                  showIcon
                  message="部分事项因会话失效未执行，请重新授权后重试"
                  action={
                    <Button size="small" onClick={() => openAuthModal()}>
                      重新授权
                    </Button>
                  }
                />
              )}
              <ul className="sw-oa-batch-list">
                {batchResults.map((row) => (
                  <li key={row.affairId}>
                    <Tag color={row.success ? 'success' : 'error'}>
                      {row.success ? '成功' : '失败'}
                    </Tag>
                    <div className="sw-oa-batch-copy">
                      <Typography.Text strong>
                        {batchTitles[row.affairId] || row.affairId}
                      </Typography.Text>
                      <Typography.Text type="secondary">
                        {row.result || '—'}
                      </Typography.Text>
                    </div>
                  </li>
                ))}
              </ul>
            </>
          ) : (
            <>
              <Typography.Text>
                将对已选中的 {batchSize} 项待办逐项执行「
                {batchAction ? ACTION_LABELS[batchAction] : ''}
                」，单项失败不影响其余事项。
              </Typography.Text>
              <ul className="sw-oa-batch-list sw-oa-batch-preview">
                {selected.map((id) => (
                  <li key={id}>
                    <Typography.Text>
                      {batchTitles[id] || subjectById[id] || id}
                    </Typography.Text>
                  </li>
                ))}
              </ul>
              {batchError && (
                <Alert type="error" showIcon message={batchError} />
              )}
            </>
          )}
        </Space>
      </Modal>

      <Modal
        title="OA 网页会话授权"
        open={authOpen}
        onCancel={() => setAuthOpen(false)}
        maskClosable={!logging && !authorizing}
        width={560}
        footer={
          authStep === 'checking' ? (
            <Button onClick={() => setAuthOpen(false)}>取消</Button>
          ) : (
            <Space>
              <Button
                disabled={logging || authorizing}
                onClick={() => setAuthOpen(false)}
              >
                取消
              </Button>
              <Button
                type="primary"
                loading={logging}
                disabled={!challenge || authorizing}
                onClick={() => void submitCaptcha()}
              >
                登录
              </Button>
            </Space>
          )
        }
      >
        <Space orientation="vertical" size={12} className="sw-oa-auth">
          {authStep === 'checking' ? (
            <Space align="center" size={8}>
              <Spin size="small" />
              <Typography.Text>
                正在尝试自动授权（OA 未强制验证码时可直接完成）…
              </Typography.Text>
            </Space>
          ) : (
            <>
              <Alert
                type="info"
                showIcon
                message={authHint || '自动授权未完成，请输入验证码完成登录'}
              />
              <div className="sw-oa-auth-captcha">
                <div className="sw-oa-auth-captcha-frame">
                  {challenge ? (
                    <img
                      src={`data:image/png;base64,${challenge.imageBase64}`}
                      alt="OA 登录验证码"
                    />
                  ) : (
                    <Spin size="small" />
                  )}
                </div>
                <Button
                  icon={<ReloadOutlined />}
                  loading={captchaLoading}
                  disabled={logging}
                  onClick={() => {
                    setAuthError('');
                    void loadCaptcha();
                  }}
                >
                  刷新验证码
                </Button>
              </div>
              <Input
                value={captcha}
                onChange={(event) => setCaptcha(event.target.value)}
                onPressEnter={() => void submitCaptcha()}
                placeholder="输入图中验证码（5 分钟内有效）"
                disabled={!challenge}
                allowClear
              />
              {authError && <Alert type="error" showIcon message={authError} />}
              <Collapse
                ghost
                activeKey={pasteOpen ? ['paste'] : []}
                onChange={(keys) => setPasteOpen(keys.includes('paste'))}
                items={[
                  {
                    key: 'paste',
                    label: '兜底：粘贴 JSESSIONID 授权',
                    children: (
                      <Space
                        orientation="vertical"
                        size={12}
                        className="sw-oa-auth-paste"
                      >
                        <ol className="sw-oa-auth-steps">
                          <li>在浏览器登录致远 OA（如 oa.lucidata.cn）</li>
                          <li>
                            按 F12 打开开发者工具，切到 Application（应用）→
                            Cookies
                          </li>
                          <li>
                            选中 OA 域名，复制名为 JSESSIONID 的 Cookie
                            值（复制整行 Cookie 也可以）
                          </li>
                          <li>
                            粘贴到下方输入框并保存；会话失效时按提示重新粘贴
                          </li>
                        </ol>
                        <Input.TextArea
                          value={cookie}
                          onChange={(event) => setCookie(event.target.value)}
                          placeholder="粘贴 JSESSIONID 的值，或整段 Cookie（如 JSESSIONID=abc123…）"
                          autoSize={{ minRows: 2, maxRows: 4 }}
                          allowClear
                        />
                        <Button
                          type="primary"
                          loading={authorizing}
                          disabled={logging}
                          onClick={() => void authorize()}
                        >
                          保存授权
                        </Button>
                      </Space>
                    ),
                  },
                ]}
              />
            </>
          )}
        </Space>
      </Modal>
    </div>
  );
}
