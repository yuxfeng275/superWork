import { InboxOutlined } from '@ant-design/icons';
import {
  Button,
  Input,
  List,
  message,
  Select,
  Space,
  Tabs,
  Tag,
  Typography,
  Upload,
} from 'antd';
import type { UploadFile, UploadProps } from 'antd';
import type { ReactNode } from 'react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  type ProjectFact,
  type ProjectFactChatMessage,
  type ProjectFactSourceType,
  type ProjectFactVisibility,
  type ProjectFactsSummary,
  type ProjectTreeNode,
  superworkApi,
} from '@/services/superwork/api';

/**
 * 可见性选项。首项「跟随默认」是正确姿势：不显式发 visibility，
 * 后端的 FactVisibilityPolicy 才会按文件名判（命中敏感关键词 → OWNER，其余 PROJECT）。
 */
const VISIBILITY_OPTIONS = [
  { label: '跟随默认（敏感文件名仅自己可见）', value: '' },
  { label: '项目可见', value: 'PROJECT' },
  { label: '仅本公司', value: 'PRIVATE_COMPANY' },
];

/** Select 的空串 = 「跟随默认」，提交前转回 undefined（请求体里就整条不出现）。 */
const toVisibility = (value: string): ProjectFactVisibility | undefined =>
  value ? (value as ProjectFactVisibility) : undefined;

/** 反向：undefined 在 Select 里显示成空串，即「跟随默认」那一项。 */
const fromVisibility = (value?: ProjectFactVisibility) => value ?? '';

/** 投递箱只关心人工喂进去的几类事实，制品是夜间回流进来的。 */
const INTAKE_TYPES: ProjectFactSourceType[] = [
  'FILE_UPLOAD',
  'NOTE',
  'CHAT_EXPORT',
  'ARTIFACT',
];

const SOURCE_LABEL: Record<string, string> = {
  FILE_UPLOAD: '文件',
  NOTE: '说明',
  CHAT_EXPORT: '聊天',
  ARTIFACT: '制品',
};

const SOURCE_COLOR: Record<string, string> = {
  FILE_UPLOAD: 'blue',
  NOTE: 'green',
  CHAT_EXPORT: 'purple',
  ARTIFACT: 'gold',
};

const INTAKE_DONE_TEXT = '已入事实，今晚会用到';

/**
 * 公司邮箱域名：项目邮箱别名 = inbox+<project.code>@<公司邮箱域名>。
 * 口径：后端别名路由只认 project.code，域名只决定展示出来的地址长什么样；
 * 域名由构建期环境变量 COMPANY_MAIL_DOMAIN 提供（同 Footer 的 process.env 口径），
 * 不硬编码真实公司域名入库，也不编造占位域名——未配置时宁可不展示地址。
 */
const COMPANY_MAIL_DOMAIN = (process.env.COMPANY_MAIL_DOMAIN || '')
  .trim()
  .replace(/^@+/, '');

/** 域名没配好时不能猜地址，直接告诉运维要配什么。 */
const MAIL_DOMAIN_MISSING_TEXT =
  '项目邮箱别名未配置：请运维设置 COMPANY_MAIL_DOMAIN（公司邮箱域名）后重新构建';

const visibilityLabel = (visibility?: string) =>
  visibility === 'PRIVATE_COMPANY'
    ? '仅本公司'
    : visibility === 'OWNER'
      ? '仅负责人'
      : '项目可见';

/** happenedAt 可能是「yyyy-MM-dd HH:mm:ss」墙上时间，不走 new Date，直接截到分钟。 */
const formatMoment = (value?: string) =>
  value ? value.replace('T', ' ').slice(0, 16) : '—';

const truncate = (value: string | undefined, max: number) => {
  const text = (value || '').trim();
  if (!text) return '暂无摘要';
  return text.length > max ? `${text.slice(0, max)}…` : text;
};

/** 「[2026-09-01 09:30] 张三: 内容」；也兼容「09:30 张三: 内容」与「张三: 内容」。 */
const TIME_PREFIX_LINE = /^\s*[[【]([^\]】]*)[\]】]\s*([^:：]{1,24})\s*[:：]\s?([\s\S]*)$/;
const PLAIN_LINE = /^\s*([^:：\s]{1,20})\s*[:：]\s?([\s\S]*)$/;

/**
 * 把导出的聊天文本按行拆成 messages。
 * 一行都认不出来（不是聊天格式）就整段当一条，不丢内容。
 */
export const parseChatText = (raw: string): ProjectFactChatMessage[] => {
  if (!raw.trim()) return [];
  const messages: ProjectFactChatMessage[] = [];
  let matched = 0;
  raw.split(/\r?\n/).forEach((line) => {
    if (!line.trim()) return;
    const withTime = TIME_PREFIX_LINE.exec(line);
    if (withTime) {
      matched += 1;
      messages.push({
        time: withTime[1].trim(),
        sender: withTime[2].trim(),
        text: withTime[3].trim(),
      });
      return;
    }
    const plain = PLAIN_LINE.exec(line);
    if (plain) {
      matched += 1;
      messages.push({
        time: '',
        sender: plain[1].trim(),
        text: plain[2].trim(),
      });
      return;
    }
    // 续行（消息里换行）并到上一条；前面没有消息就先挂着。
    const last = messages[messages.length - 1];
    if (last) last.text = `${last.text}\n${line.trim()}`;
    else messages.push({ time: '', sender: '', text: line.trim() });
  });
  if (!matched) return [{ time: '', sender: '', text: raw.trim() }];
  return messages;
};

const Field = ({ label, children }: { label: string; children: ReactNode }) => (
  <div style={{ marginBottom: 12 }}>
    <Typography.Text type="secondary">{label}</Typography.Text>
    <div style={{ marginTop: 4 }}>{children}</div>
  </div>
);

/**
 * 项目详情抽屉里的「投递箱」：成员把文件、补充说明、聊天记录直接倒进 project_fact。
 * 夜里 agent 只认事实，这里投得越全，它越不用猜。
 */
export default function ProjectIntakeSection({
  projectId,
  projectCode,
}: {
  projectId: number;
  /** 项目编码（邮箱别名要用）；不传就按 projectId 回查一次项目树 */
  projectCode?: string;
}) {
  const [activeTab, setActiveTab] = useState('file');
  const [facts, setFacts] = useState<ProjectFact[]>([]);
  const [loading, setLoading] = useState(false);
  /** 投递概览：统计失败只在本块显示占位，不影响投递本身。 */
  const [summary, setSummary] = useState<ProjectFactsSummary>();
  const [summaryLoading, setSummaryLoading] = useState(false);
  const [summaryError, setSummaryError] = useState('');
  /** 邮箱别名用的项目编码：没传就回查项目树，查不到就不显示别名。 */
  const [resolvedCode, setResolvedCode] = useState(projectCode || '');
  const [uploadingCount, setUploadingCount] = useState(0);
  const [submitting, setSubmitting] = useState(false);

  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [fileNote, setFileNote] = useState('');
  /**
   * 可见性初值一律 undefined（= 跟随默认）：显式发 PROJECT 会让后端的敏感文件名策略失效，
   * 敏感文件就默认公开给全体项目成员了。
   */
  const [fileVisibility, setFileVisibility] = useState<
    ProjectFactVisibility | undefined
  >(undefined);

  const [noteTitle, setNoteTitle] = useState('');
  const [noteBody, setNoteBody] = useState('');
  const [noteVisibility, setNoteVisibility] = useState<
    ProjectFactVisibility | undefined
  >(undefined);

  const [chatText, setChatText] = useState('');
  const [senderEdits, setSenderEdits] = useState<Record<number, string>>({});
  const [chatVisibility, setChatVisibility] = useState<
    ProjectFactVisibility | undefined
  >(undefined);

  const uploading = uploadingCount > 0;

  const load = useCallback(async () => {
    setLoading(true);
    try {
      // 投递箱只看投递物：后端不传 types 会返回全部来源，所以这里必须显式限定。
      const data = await superworkApi.listProjectFacts(projectId, {
        types: INTAKE_TYPES,
        limit: 50,
      });
      setFacts(data ?? []);
    } catch (error) {
      message.error(
        error instanceof Error ? error.message : '加载投递记录失败，请稍后重试',
      );
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 投递概览：各来源计数 / 低质 / 24h / 未归类 / 本周是否 0 新增。挖不到就占位。 */
  const loadSummary = useCallback(async () => {
    setSummaryLoading(true);
    setSummaryError('');
    try {
      setSummary(await superworkApi.getProjectFactsSummary(projectId));
    } catch {
      setSummary(undefined);
      setSummaryError('概览统计不到，稍后重试即可，不影响投递');
    } finally {
      setSummaryLoading(false);
    }
  }, [projectId]);

  useEffect(() => {
    void loadSummary();
  }, [loadSummary]);

  /** 投完一轮：列表和概览一起刷，别让人以为没投进去。 */
  const refresh = useCallback(() => {
    void load();
    void loadSummary();
  }, [load, loadSummary]);

  // 没传 projectCode 时从项目树里摸一次；摸不到就空着，不编一个编码出来。
  useEffect(() => {
    if (projectCode) {
      setResolvedCode(projectCode);
      return;
    }
    let cancelled = false;
    const findCode = (nodes: ProjectTreeNode[]): string => {
      for (const node of nodes) {
        if (node.id === projectId) return node.code || '';
        const hit = findCode(node.children || []);
        if (hit) return hit;
      }
      return '';
    };
    void superworkApi
      .getProjectTree()
      .then((tree) => {
        if (!cancelled) setResolvedCode(findCode(tree || []));
      })
      .catch(() => {
        if (!cancelled) setResolvedCode('');
      });
    return () => {
      cancelled = true;
    };
  }, [projectCode, projectId]);

  // 文本一改就重新解析，顺手清掉旧的发送人改写，避免张冠李戴。
  useEffect(() => {
    setSenderEdits({});
  }, [chatText]);

  const parsedMessages = useMemo(() => parseChatText(chatText), [chatText]);
  const chatMessages = parsedMessages.map((item, index) => ({
    ...item,
    sender: senderEdits[index] ?? item.sender,
  }));

  const submitFile: UploadProps['customRequest'] = (options) => {
    const file = options.file as unknown as File;
    setUploadingCount((count) => count + 1);
    void superworkApi
      .uploadProjectFactFile(projectId, {
        file,
        note: fileNote.trim() || undefined,
        visibility: fileVisibility,
        onProgress: (percent) => options.onProgress?.({ percent }),
      })
      .then((fact) => {
        options.onSuccess?.(fact);
        message.success(INTAKE_DONE_TEXT);
        refresh();
      })
      .catch((error: unknown) => {
        options.onError?.(
          error instanceof Error ? error : new Error('上传失败'),
        );
        message.error(
          error instanceof Error ? error.message : '上传失败，请稍后重试',
        );
      })
      .finally(() => setUploadingCount((count) => count - 1));
  };

  const submitNote = async () => {
    const title = noteTitle.trim();
    const body = noteBody.trim();
    if (!title) {
      message.error('给这条说明起个标题');
      return;
    }
    if (!body) {
      message.error('说明正文是空的');
      return;
    }
    setSubmitting(true);
    try {
      await superworkApi.addProjectFactNote(projectId, {
        title,
        body,
        visibility: noteVisibility,
      });
      message.success(INTAKE_DONE_TEXT);
      setNoteTitle('');
      setNoteBody('');
      await load();
      void loadSummary();
    } catch (error) {
      message.error(
        error instanceof Error ? error.message : '保存失败，请稍后重试',
      );
    } finally {
      setSubmitting(false);
    }
  };

  const submitChat = async () => {
    const messages = chatMessages
      .map((item) => ({ ...item, sender: item.sender.trim(), text: item.text.trim() }))
      .filter((item) => item.text);
    if (!messages.length) {
      message.error('先贴一段聊天记录');
      return;
    }
    if (messages.some((item) => !item.sender)) {
      message.error('有消息还没填发送人，改一下再导入');
      return;
    }
    setSubmitting(true);
    try {
      await superworkApi.ingestProjectChat(projectId, {
        messages,
        visibility: chatVisibility,
      });
      message.success(INTAKE_DONE_TEXT);
      setChatText('');
      await load();
      void loadSummary();
    } catch (error) {
      message.error(
        error instanceof Error ? error.message : '导入失败，请稍后重试',
      );
    } finally {
      setSubmitting(false);
    }
  };

  const overview = summary;
  const bySource = overview?.bySource || {};
  const sourceCount = (type: string) => bySource[type] ?? 0;
  /**
   * 未归类邮件数：**用户级**口径（我名下没归属的邮件），后端统计失败给 null，别当成 0。
   * 这里只看本项目这一份，不做跨项目加总。
   */
  const ungroupedText =
    typeof overview?.ungrouped === 'number'
      ? `${overview.ungrouped} 封`
      : '统计不到';
  /** 待补文件（ungroupedFiles，项目级可求和）：投上来但没正文的文件。 */
  const pendingFilesText =
    typeof overview?.ungroupedFiles === 'number'
      ? `${overview.ungroupedFiles} 个`
      : '统计不到';
  /**
   * 统计是否被截断（后端只统计了前 N 条事实）：截断时上面这几个数字都只是**下限**，
   * 不是确定值——真实条数只会更多，不会更少。所以数字照旧显示，但必须标一条
   * 「仅覆盖部分事实」，不能再让人把它当准确值去对账。
   *
   * 更不能据此下「本周 0 新增」这种结论：这个 0 可能是「没统计到」，而不是「真的没有」；
   * 按 SOP 去补料、找运维这类确定性动作建立在「确实为 0」之上，截断时站不住脚，
   * 所以这里只提示「可能 0 新增」，不重复那套确定建议。
   */
  const truncated = overview?.truncated === true;

  return (
    <div>
      <div
        style={{
          border: '1px solid #f0f0f0',
          borderRadius: 8,
          padding: '10px 12px',
          marginBottom: 12,
        }}
      >
        <Space size={8} wrap>
          <Typography.Text strong>投递概览</Typography.Text>
          {summaryLoading && !overview ? (
            <Typography.Text type="secondary">统计中…</Typography.Text>
          ) : overview ? (
            <>
              <Typography.Text type="secondary">
                {SOURCE_LABEL.FILE_UPLOAD} {sourceCount('FILE_UPLOAD')}
              </Typography.Text>
              <Typography.Text type="secondary">
                {SOURCE_LABEL.NOTE} {sourceCount('NOTE')}
              </Typography.Text>
              <Typography.Text type="secondary">
                {SOURCE_LABEL.CHAT_EXPORT} {sourceCount('CHAT_EXPORT')}
              </Typography.Text>
              <Typography.Text type="secondary">
                {SOURCE_LABEL.ARTIFACT} {sourceCount('ARTIFACT')}
              </Typography.Text>
              <Tag color={Number(overview.lowQuality || 0) > 0 ? 'orange' : 'default'}>
                低质 {overview.lowQuality || 0}
              </Tag>
              <Tag color="blue">24h 投递 {overview.last24h || 0}</Tag>
              <Tag
                color={
                  typeof overview.ungrouped === 'number' && overview.ungrouped > 0
                    ? 'gold'
                    : 'default'
                }
              >
                未归类 {ungroupedText}
              </Tag>
              {/* 待补文件是新字段（项目级）；isolated 恒为 null、只有本机知道，这里不展示 */}
              <Tag
                color={
                  typeof overview.ungroupedFiles === 'number' &&
                  overview.ungroupedFiles > 0
                    ? 'gold'
                    : 'default'
                }
              >
                待补文件 {pendingFilesText}
              </Tag>
              {/* 截断时「本周有新增」仍然成立（看到的事实是真的），但「0 新增」只是个下限结论 */}
                {overview.thisWeekZero ? (
                  <Tag color={truncated ? 'default' : 'red'}>
                    {truncated ? '本周可能 0 新增' : '本周 0 新增'}
                  </Tag>
              ) : (
                <Tag color="green">本周有新增</Tag>
              )}
              {/* 截断提示放在数据行末尾：数字照旧给，但明确它们只是下限 */}
              {truncated && (
                <Tag color="warning">统计仅覆盖部分事实，上面数字为下限</Tag>
              )}
            </>
          ) : (
            <Typography.Text type="secondary">
              {summaryLoading ? '统计中…' : summaryError || '概览统计不到'}
            </Typography.Text>
          )}
        </Space>
        {/* 截断时不下定论：不能说「一条都没有」，也不该让人按 SOP 去做确定性的补救动作 */}
          {truncated && overview?.thisWeekZero && (
            <div style={{ marginTop: 6 }}>
              <Typography.Text type="warning">
              本周可能 0 新增（统计未覆盖全部事实），先按上面的下限看，别据此判断要不要补料。
            </Typography.Text>
          </div>
        )}
        {!truncated && overview?.thisWeekZero && (
          <div style={{ marginTop: 6 }}>
            <Typography.Text type="secondary">
              本周一条新事实都没有：按 SOP 先确认归档邮箱在收信（找运维），
              再把客户原话转发到下面这个项目邮箱；否则夜里 agent 只能空转。
            </Typography.Text>
          </div>
        )}
        {resolvedCode ? (
          <div style={{ marginTop: 6 }}>
            <Typography.Text type="secondary">
              {COMPANY_MAIL_DOMAIN ? (
                  <>
                    项目邮箱别名：{' '}
                    <Typography.Text code>
                      {`inbox+${resolvedCode}@${COMPANY_MAIL_DOMAIN}`}
                    </Typography.Text>{' '}
                    （外部客户直接发到这里，自动归到本项目）
                  </>
              ) : (
                MAIL_DOMAIN_MISSING_TEXT
              )}
            </Typography.Text>
          </div>
        ) : null}
        <div style={{ marginTop: 4 }}>
          <Typography.Text type="secondary">
            文件名命中敏感关键词的投递默认仅自己可见。
          </Typography.Text>
        </div>
      </div>
      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        items={[
          {
            key: 'file',
            label: '拖文件',
            children: (
              <>
                <Upload.Dragger
                  multiple
                  disabled={uploading}
                  fileList={fileList}
                  customRequest={submitFile}
                  onChange={({ fileList: next }) => setFileList(next)}
                >
                  <p className="ant-upload-drag-icon">
                    <InboxOutlined />
                  </p>
                  <p className="ant-upload-text">把文件拖到这里，或点击选择</p>
                  <p className="ant-upload-hint">
                    合同、报价、客户截图、导出表都行，一次可以拖多个
                  </p>
                </Upload.Dragger>
                <Field label="可见性">
                  <Select
                    value={fromVisibility(fileVisibility)}
                    options={VISIBILITY_OPTIONS}
                    onChange={(value) => setFileVisibility(toVisibility(value))}
                    style={{ maxWidth: 260 }}
                  />
                </Field>
                <Field label="备注（选填，会一起进事实）">
                  <Input
                    value={fileNote}
                    onChange={(event) => setFileNote(event.target.value)}
                    placeholder="例如：客户 9/1 口头确认的价格，以这份报价为准"
                    allowClear
                  />
                </Field>
              </>
            ),
          },
          {
            key: 'note',
            label: '写说明',
            children: (
              <>
                <Field label="标题">
                  <Input
                    value={noteTitle}
                    onChange={(event) => setNoteTitle(event.target.value)}
                    placeholder="例如：客户砍价到 8 折，还没最终点头"
                    maxLength={300}
                    allowClear
                  />
                </Field>
                <Field label="正文">
                  <Input.TextArea
                    value={noteBody}
                    onChange={(event) => setNoteBody(event.target.value)}
                    autoSize={{ minRows: 5, maxRows: 12 }}
                    placeholder="把没写在合同里的约定、口头承诺、风险都写下来"
                  />
                </Field>
                <Field label="可见性">
                  <Select
                    value={fromVisibility(noteVisibility)}
                    options={VISIBILITY_OPTIONS}
                    onChange={(value) => setNoteVisibility(toVisibility(value))}
                    style={{ maxWidth: 260 }}
                  />
                </Field>
                <Button
                  type="primary"
                  loading={submitting}
                  onClick={() => void submitNote()}
                >
                  记进事实
                </Button>
              </>
            ),
          },
          {
            key: 'chat',
            label: '贴聊天',
            children: (
              <>
                <Field label="聊天记录（企微/微信导出，一行一条）">
                  <Input.TextArea
                    value={chatText}
                    onChange={(event) => setChatText(event.target.value)}
                    autoSize={{ minRows: 6, maxRows: 14 }}
                    placeholder={
                      '[2026-09-01 09:30] 张三: 这批货下周能到\n[2026-09-01 09:32] 李四: 合同先签，货后补'
                    }
                  />
                </Field>
                {chatMessages.length > 0 && (
                  <div style={{ marginBottom: 12 }}>
                    <Typography.Text type="secondary">
                      解析出 {chatMessages.length} 条，发送人可以手改
                    </Typography.Text>
                    <List
                      size="small"
                      bordered
                      style={{ marginTop: 4 }}
                      dataSource={chatMessages}
                      renderItem={(item, index) => (
                        <List.Item>
                          <Space direction="vertical" size={4} style={{ width: '100%' }}>
                            <Space wrap>
                              <Input
                                size="small"
                                value={item.sender}
                                placeholder="发送人"
                                status={item.sender.trim() ? undefined : 'error'}
                                style={{ width: 150 }}
                                onChange={(event) =>
                                  setSenderEdits((prev) => ({
                                    ...prev,
                                    [index]: event.target.value,
                                  }))
                                }
                              />
                              <Typography.Text type="secondary">
                                {item.time || '时间未识别'}
                              </Typography.Text>
                            </Space>
                            <Typography.Paragraph
                              style={{ margin: 0, whiteSpace: 'pre-wrap' }}
                            >
                              {item.text}
                            </Typography.Paragraph>
                          </Space>
                        </List.Item>
                      )}
                    />
                  </div>
                )}
                <Field label="可见性">
                  <Select
                    value={fromVisibility(chatVisibility)}
                    options={VISIBILITY_OPTIONS}
                    onChange={(value) => setChatVisibility(toVisibility(value))}
                    style={{ maxWidth: 260 }}
                  />
                </Field>
                <Button
                  type="primary"
                  loading={submitting}
                  onClick={() => void submitChat()}
                >
                  导入聊天记录
                </Button>
              </>
            ),
          },
        ]}
      />
      <Typography.Title level={5} style={{ marginTop: 8 }}>
        最近投递
      </Typography.Title>
      <List
        loading={loading}
        locale={{ emptyText: '还没有投进来的素材，夜里 agent 只能靠猜' }}
        dataSource={facts}
        renderItem={(fact) => (
          <List.Item>
            <List.Item.Meta
              title={
                <Space size={6} wrap>
                  <Tag color={SOURCE_COLOR[fact.sourceType] || 'default'}>
                    {SOURCE_LABEL[fact.sourceType] || fact.sourceType}
                  </Tag>
                  <Typography.Text strong>
                    {fact.title || '（无标题）'}
                  </Typography.Text>
                </Space>
              }
              description={
                <div>
                  <div>{truncate(fact.digest || fact.body, 80)}</div>
                  <Space size={8} wrap style={{ marginTop: 4 }}>
                    <Typography.Text type="secondary">
                      {formatMoment(fact.happenedAt || fact.createdAt)}
                    </Typography.Text>
                    <Tag
                      color={
                        fact.visibility === 'PRIVATE_COMPANY' ? 'orange' : 'blue'
                      }
                    >
                      {visibilityLabel(fact.visibility)}
                    </Tag>
                  </Space>
                </div>
              }
            />
          </List.Item>
        )}
      />
    </div>
  );
}
