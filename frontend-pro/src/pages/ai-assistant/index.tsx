import {
  CloudUploadOutlined,
  MailOutlined,
  MessageOutlined,
  PaperClipOutlined,
  SearchOutlined,
  UserOutlined,
} from '@ant-design/icons';
import {
  Actions,
  Attachments,
  Bubble,
  Conversations,
  Prompts,
  Sender,
  Suggestion,
  Think,
  ThoughtChain,
  Welcome,
  XProvider,
} from '@ant-design/x';
import type { Attachment } from '@ant-design/x/es/attachments';
import type { BubbleItemType } from '@ant-design/x/es/bubble/interface';
import XMarkdown from '@ant-design/x-markdown';
import '@ant-design/x-markdown/es/XMarkdown/index.css';
import {
  Alert,
  Avatar,
  Badge,
  Button,
  Modal,
  message,
  Select,
  Space,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  type AiAgentMessage,
  type AiAgentModelOption,
  type AiAgentSession,
  type AiAgentSessionSummary,
  type AiAgentStreamEvent,
  type AiConnectorStatus,
  superworkApi,
} from '@/services/superwork/api';
import '../workbench/style.less';
import './style.less';

type ChatItem = {
  id: string;
  role: 'user' | 'assistant' | 'tool' | 'error';
  text: string;
  thinking?: string;
  running?: boolean;
  error?: boolean;
  toolName?: string;
  toolCallId?: string;
};
const welcomePrompts = [
  {
    key: 'hours',
    icon: <SearchOutlined />,
    label: '工时填报分析',
    description: '帮我分析一下我最近三个月的工时填报情况',
  },
  {
    key: 'mail',
    icon: <MailOutlined />,
    label: '搜索邮件',
    description: '搜索一下最近关于项目验收的邮件',
  },
  {
    key: 'yunxiao',
    icon: <MessageOutlined />,
    label: '查云效工作项',
    description: '我名下有哪些进行中的云效工作项？',
  },
  {
    key: 'yuque',
    icon: <SearchOutlined />,
    label: '搜语雀文档',
    description: '在语雀里搜一下新员工入职指引',
  },
];
const slashSuggestions = welcomePrompts.map((item) => ({
  label: String(item.label),
  value: String(item.description),
  icon: item.icon,
  extra: item.label,
}));
const extractText = (content: unknown) => {
  if (typeof content === 'string') return content;
  if (Array.isArray(content))
    return content
      .map((part) =>
        typeof part === 'object' && part
          ? String(
              (part as { text?: unknown }).text ||
                (part as { thinking?: unknown }).thinking ||
                '',
            )
          : '',
      )
      .join('');
  return '';
};

export default function AiAssistantPage() {
  const [sessions, setSessions] = useState<AiAgentSessionSummary[]>([]);
  const [active, setActive] = useState<AiAgentSession>();
  const [items, setItems] = useState<ChatItem[]>([]);
  const [models, setModels] = useState<AiAgentModelOption[]>([]);
  const [selectedModel, setSelectedModel] = useState('');
  const [connectors, setConnectors] = useState<AiConnectorStatus[]>([]);
  const [connectorOpen, setConnectorOpen] = useState(false);
  const [draft, setDraft] = useState('');
  const [files, setFiles] = useState<Attachment[]>([]);
  const [headerOpen, setHeaderOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [streaming, setStreaming] = useState(false);
  const [error, setError] = useState('');
  const controller = useRef<AbortController | undefined>(undefined);
  const messageBox = useRef<HTMLDivElement>(null);
  const loadSessions = useCallback(async () => {
    try {
      setError('');
      setSessions(await superworkApi.getAiAgentSessions());
    } catch (e) {
      setError(e instanceof Error ? e.message : '会话列表加载失败');
    }
  }, []);
  useEffect(() => {
    // 通知中心深链：?prefill=… 填入输入框并清理 query（对齐旧系统 applyPrefillFromRoute）
    const prefill = new URLSearchParams(window.location.search).get('prefill');
    if (prefill) {
      setDraft(prefill);
      window.history.replaceState(
        {},
        '',
        window.location.pathname + window.location.hash,
      );
    }
  }, []);
  useEffect(() => {
    void Promise.all([
      loadSessions(),
      superworkApi
        .getAiAgentModels()
        .then((list) => {
          setModels(list);
          if (list[0]) setSelectedModel(`${list[0].provider}:${list[0].model}`);
        })
        .catch(() => undefined),
      superworkApi
        .getAiAgentConnectors()
        .then(setConnectors)
        .catch(() => undefined),
    ]);
  }, [loadSessions]);
  useEffect(() => {
    if (messageBox.current)
      messageBox.current.scrollTop = messageBox.current.scrollHeight;
  }, [items]);
  const openSession = async (id: number) => {
    setLoading(true);
    try {
      const session = await superworkApi.getAiAgentSession(id);
      setActive(session);
      setItems(rebuildItems(session));
    } catch (e) {
      message.error(e instanceof Error ? e.message : '会话加载失败');
    } finally {
      setLoading(false);
    }
  };
  const rebuildItems = (session: AiAgentSession): ChatItem[] =>
    session.messages.reduce<ChatItem[]>(
      (result, item: AiAgentMessage, index) => {
        if (item.role === 'user')
          result.push({
            id: `m-${index}`,
            role: 'user',
            text: extractText(item.content),
          });
        else if (item.role === 'assistant')
          result.push({
            id: `m-${index}`,
            role: 'assistant',
            text: extractText(item.content),
          });
        return result;
      },
      [],
    );
  const newChat = async () => {
    try {
      const selected = models.find(
        (model) => `${model.provider}:${model.model}` === selectedModel,
      );
      const payload = selected
        ? { provider: selected.provider, model: selected.model }
        : {};
      const session = await superworkApi.createAiAgentSession(payload);
      setActive(session);
      setItems([]);
      await loadSessions();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '新建会话失败');
    }
  };
  const deleteSession = (summary: AiAgentSessionSummary) => {
    Modal.confirm({
      title: '删除会话',
      content: `确定删除「${summary.title || '新对话'}」吗？`,
      okText: '删除',
      cancelText: '取消',
      okButtonProps: { danger: true },
      onOk: async () => {
        await superworkApi.deleteAiAgentSession(summary.id);
        if (active?.id === summary.id) {
          setActive(undefined);
          setItems([]);
        }
        await loadSessions();
      },
    });
  };
  const resyncSession = async (sessionId: number) => {
    setSyncing(true);
    try {
      const latest = await superworkApi.getAiAgentSession(sessionId);
      setActive(latest);
      setItems(rebuildItems(latest));
      await loadSessions();
    } catch (e) {
      message.error(
        e instanceof Error ? e.message : '会话同步失败，请稍后手动刷新',
      );
    } finally {
      setSyncing(false);
    }
  };
  const send = async (nextContent?: string) => {
    const content = (nextContent ?? draft).trim();
    if (!active || !content || streaming || syncing) return;
    const sessionId = active.id;
    const attached = files.filter((file) => file.status !== 'error');
    const uploadedIds: number[] = [];
    const uploadedNames: string[] = [];
    try {
      for (const file of attached) {
        const origin = file.originFileObj as File | undefined;
        if (!origin) continue;
        const saved = await superworkApi.uploadAiAgentAttachment(sessionId, origin);
        uploadedIds.push(saved.id);
        uploadedNames.push(saved.fileName || origin.name);
      }
    } catch (e) {
      message.error(e instanceof Error ? e.message : '附件上传失败');
      return;
    }
    const markers = uploadedNames.map((name) => `[附件: ${name}]`).join(' ');
    const displayText = markers ? `${content}\n\n${markers}` : content;
    setDraft('');
    setFiles([]);
    setHeaderOpen(false);
    setItems((current) => [
      ...current,
      { id: `u-${Date.now()}`, role: 'user', text: displayText },
    ]);
    const abort = new AbortController();
    controller.current = abort;
    setStreaming(true);
    let assistantId = '';
    let streamFailed = false;
    try {
      await superworkApi.streamAiAgentRun(
        sessionId,
        content,
        (event: AiAgentStreamEvent) => {
          if (event.type === 'message_start') {
            assistantId = `a-${Date.now()}`;
            setItems((current) => [
              ...current,
              { id: assistantId, role: 'assistant', text: '', thinking: '' },
            ]);
          } else if (event.type === 'message_delta') {
            const delta = event.delta as
              | { type?: string; text?: string }
              | undefined;
            setItems((current) =>
              current.map((item) =>
                item.id === assistantId
                  ? {
                      ...item,
                      text:
                        delta?.type === 'thinking_delta'
                          ? item.text
                          : item.text + (delta?.text || ''),
                      thinking:
                        delta?.type === 'thinking_delta'
                          ? `${item.thinking || ''}${delta?.text || ''}`
                          : item.thinking,
                    }
                  : item,
              ),
            );
          } else if (event.type === 'message_end') {
            setItems((current) =>
              current.map((item) =>
                item.id === assistantId ? { ...item, running: false } : item,
              ),
            );
          } else if (event.type === 'tool_execution_start') {
            const toolCallId = String(event.toolCallId || `tool-${Date.now()}`);
            setItems((current) => [
              ...current,
              {
                id: toolCallId,
                role: 'tool',
                text: `调用工具 ${String(event.toolName || '')}…`,
                running: true,
                toolName: String(event.toolName || ''),
                toolCallId,
              },
            ]);
          } else if (event.type === 'tool_execution_end') {
            const toolCallId = String(event.toolCallId || '');
            setItems((current) =>
              current.map((item) =>
                item.toolCallId === toolCallId
                  ? {
                      ...item,
                      text:
                        event.isError === true
                          ? `工具 ${item.toolName || ''} 执行失败`
                          : `工具 ${item.toolName || ''} 执行完成`,
                      running: false,
                      error: event.isError === true,
                    }
                  : item,
              ),
            );
          } else if (event.type === 'error') {
            streamFailed = true;
            setItems((current) => [
              ...current,
              {
                id: `e-${Date.now()}`,
                role: 'error',
                text: String(event.message || 'AI 服务异常'),
                error: true,
              },
            ]);
            abort.abort();
          }
        },
        abort.signal,
        uploadedIds.length ? uploadedIds : undefined,
      );
      if (!streamFailed && !abort.signal.aborted)
        await resyncSession(sessionId);
    } catch (e) {
      if (!(e instanceof DOMException && e.name === 'AbortError'))
        message.error(e instanceof Error ? e.message : 'AI 请求失败');
    } finally {
      setStreaming(false);
      controller.current = undefined;
    }
  };
  const stop = () => controller.current?.abort();
  const conversationItems = sessions.map((session) => ({
    key: String(session.id),
    label: session.title || '新对话',
    extra: `${session.messageCount || 0} 条`,
    disabled: streaming || syncing,
  }));
  const applyPrompt = (text: string) => {
    setDraft(text);
    if (!active) void newChat();
  };
  const bubbleItems: BubbleItemType[] = items.map((item) => {
    if (item.role === 'tool') {
      return {
        key: item.id,
        role: 'tool',
        content: item.text,
        contentRender: () => (
          <ThoughtChain
            items={[{
              key: item.id,
              title: item.toolName || '工具调用',
              description: item.text,
              status: item.running ? 'loading' : item.error ? 'error' : 'success',
              blink: Boolean(item.running),
            }]}
          />
        ),
      };
    }
    if (item.role === 'error') {
      return {
        key: item.id,
        role: 'ai',
        content: item.text,
        status: 'error',
        footer: <Alert type="error" message={item.text} showIcon />,
      };
    }
    const bubble: BubbleItemType = {
      key: item.id,
      role: item.role === 'user' ? 'user' : 'ai',
      content: item.text,
      loading: streaming && item.role === 'assistant' && !item.text,
      status: streaming && item.role === 'assistant' ? 'updating' : 'success',
    };
    if (item.thinking) bubble.header = <Think>{item.thinking}</Think>;
    if (item.text) {
      bubble.footer = (
        <Actions
          items={[{ key: 'copy', label: '复制', actionRender: Actions.Copy }]}
          onClick={({ key }) => {
            if (key !== 'copy') return;
            void navigator.clipboard.writeText(item.text);
            message.success('已复制');
          }}
        />
      );
    }
    return bubble;
  });
  const roleConfig = {
    user: {
      placement: 'end' as const,
      avatar: <Avatar icon={<UserOutlined />} />,
    },
    ai: {
      placement: 'start' as const,
      avatar: <Avatar icon={<MessageOutlined />} />,
      contentRender: (content: string, info: { status?: string; loading?: boolean }) => {
        if (info?.loading || !content) return undefined;
        return (
          <XMarkdown
            streaming={{
              hasNextChunk: info?.status === 'updating',
              enableAnimation: true,
            }}
          >
            {content}
          </XMarkdown>
        );
      },
    },
    tool: {
      placement: 'start' as const,
      variant: 'borderless' as const,
    },
  };
  const connectorTag = (status: string) =>
    status === 'READY'
      ? 'success'
      : status === 'NOT_CONFIGURED'
        ? 'warning'
        : 'default';
  return (
    <div className="sw-page sw-ai-assistant">
      <XProvider>
      <div className="sw-ai-layout">
        <aside className="sw-ai-sidebar">
          <Conversations
            items={conversationItems}
            activeKey={active ? String(active.id) : undefined}
            onActiveChange={(key) => {
              if (streaming || syncing) return;
              void openSession(Number(key));
            }}
            creation={{
              label: '新建对话',
              disabled: streaming || syncing,
              onClick: () => void newChat(),
            }}
            menu={(conversation) => ({
              items: [{ key: 'delete', label: '删除', danger: true }],
              onClick: ({ key }) => {
                if (key !== 'delete') return;
                const summary = sessions.find(
                  (session) => String(session.id) === conversation.key,
                );
                if (summary) deleteSession(summary);
              },
            })}
            styles={{ root: { height: '100%' } }}
          />
        </aside>
        <main className="sw-ai-main">
          <div className="sw-ai-header">
            <div>
              <Typography.Title level={4}>
                {active?.title || 'AI 智能助手'}
              </Typography.Title>
              <Typography.Text type="secondary">
                梳理需求、分析数据、检索邮件与知识库
              </Typography.Text>
            </div>
            <Space>
              <Select
                size="small"
                value={selectedModel || undefined}
                onChange={setSelectedModel}
                disabled={streaming || syncing}
                placeholder="选择模型"
                options={models.map((model) => ({
                  value: `${model.provider}:${model.model}`,
                  label: `${model.label} · ${model.model}`,
                }))}
              />
              <Button
                size="small"
                disabled={streaming || syncing}
                onClick={() => setConnectorOpen(true)}
              >
                连接器
              </Button>
            </Space>
          </div>
          {error && <Alert type="error" message={error} showIcon />}
          <div className="sw-ai-messages" ref={messageBox}>
            {!active || !items.length ? (
              <div className="sw-ai-welcome">
                <Welcome
                  icon={<Avatar size={56} icon={<MessageOutlined />} />}
                  title="AI 智能助手"
                  description="梳理需求、分析数据、检索邮件与知识库。选择会话或从常用问题开始。"
                />
                <Prompts
                  title="试试这些问题"
                  items={welcomePrompts}
                  wrap
                  onItemClick={({ data }) =>
                    applyPrompt(String(data.description || data.label || ''))
                  }
                  styles={{ list: { width: 'min(640px, 100%)' } }}
                />
              </div>
            ) : (
              <Bubble.List
                items={bubbleItems}
                role={roleConfig}
                autoScroll
                styles={{ root: { maxWidth: 940 } }}
              />
            )}
          </div>
          <div className="sw-ai-composer">
            <Suggestion
              items={slashSuggestions}
              onSelect={(value) => {
                setDraft(value);
              }}
            >
              {({ onTrigger, onKeyDown }) => (
                <Sender
                  value={draft}
                  onChange={(value) => {
                    setDraft(value);
                    if (value === '/') onTrigger();
                    else if (!value.startsWith('/')) onTrigger(false);
                  }}
                  onKeyDown={onKeyDown}
                  loading={streaming || syncing}
                  disabled={!active || syncing}
                  onSubmit={(value) => void send(value)}
                  onCancel={stop}
                  placeholder={
                    active
                      ? syncing
                        ? '正在同步会话…'
                        : '输入问题，/ 唤起快捷指令；Enter 发送'
                      : '先新建或选择一个会话'
                  }
                  autoSize={{ minRows: 2, maxRows: 7 }}
                  style={{ width: '100%', maxWidth: 940 }}
                  header={
                    <Sender.Header
                      title="附件"
                      open={headerOpen}
                      onOpenChange={setHeaderOpen}
                      styles={{ content: { padding: 0 } }}
                    >
                      <Attachments
                        beforeUpload={() => false}
                        items={files}
                        onChange={({ fileList }) => setFiles(fileList)}
                        placeholder={(type) =>
                          type === 'drop'
                            ? { title: '拖拽文件到这里' }
                            : {
                                icon: <CloudUploadOutlined />,
                                title: '上传文件',
                                description: '文本类附件正文会随问题发给助手，文件保留在会话里',
                              }
                        }
                      />
                    </Sender.Header>
                  }
                  prefix={
                    <Badge dot={files.length > 0 && !headerOpen}>
                      <Button
                        type="text"
                        icon={<PaperClipOutlined />}
                        onClick={() => setHeaderOpen((open) => !open)}
                      />
                    </Badge>
                  }
                />
              )}
            </Suggestion>
          </div>
        </main>
      </div>
      <Modal
        title="AI 连接器"
        open={connectorOpen}
        onCancel={() => setConnectorOpen(false)}
        footer={null}
      >
        {connectors.map((connector) => (
          <div className="sw-ai-connector" key={connector.code}>
            <Space>
              <Typography.Text strong>{connector.name}</Typography.Text>
              <Tag color={connectorTag(connector.status)}>
                {connector.status === 'READY'
                  ? '已就绪'
                  : connector.status === 'NOT_CONFIGURED'
                    ? '未配置'
                    : connector.status}
              </Tag>
            </Space>
            <Typography.Text type="secondary">{connector.hint}</Typography.Text>
          </div>
        ))}
      </Modal>
      </XProvider>
    </div>
  );
}
