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
  superworkApi,
} from '@/services/superwork/api';

const VISIBILITY_OPTIONS = [
  { label: '项目可见', value: 'PROJECT' },
  { label: '仅本公司', value: 'PRIVATE_COMPANY' },
];

const DEFAULT_VISIBILITY: ProjectFactVisibility = 'PROJECT';

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
}: {
  projectId: number;
}) {
  const [activeTab, setActiveTab] = useState('file');
  const [facts, setFacts] = useState<ProjectFact[]>([]);
  const [loading, setLoading] = useState(false);
  const [uploadingCount, setUploadingCount] = useState(0);
  const [submitting, setSubmitting] = useState(false);

  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [fileNote, setFileNote] = useState('');
  const [fileVisibility, setFileVisibility] =
    useState<ProjectFactVisibility>(DEFAULT_VISIBILITY);

  const [noteTitle, setNoteTitle] = useState('');
  const [noteBody, setNoteBody] = useState('');
  const [noteVisibility, setNoteVisibility] =
    useState<ProjectFactVisibility>(DEFAULT_VISIBILITY);

  const [chatText, setChatText] = useState('');
  const [senderEdits, setSenderEdits] = useState<Record<number, string>>({});
  const [chatVisibility, setChatVisibility] =
    useState<ProjectFactVisibility>(DEFAULT_VISIBILITY);

  const uploading = uploadingCount > 0;

  const load = useCallback(async () => {
    setLoading(true);
    try {
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
        void load();
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
    } catch (error) {
      message.error(
        error instanceof Error ? error.message : '导入失败，请稍后重试',
      );
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div>
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
                    value={fileVisibility}
                    options={VISIBILITY_OPTIONS}
                    onChange={setFileVisibility}
                    style={{ maxWidth: 200 }}
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
                    value={noteVisibility}
                    options={VISIBILITY_OPTIONS}
                    onChange={setNoteVisibility}
                    style={{ maxWidth: 200 }}
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
                    value={chatVisibility}
                    options={VISIBILITY_OPTIONS}
                    onChange={setChatVisibility}
                    style={{ maxWidth: 200 }}
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
