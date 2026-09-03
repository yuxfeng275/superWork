<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ChatDotRound,
  CircleCheck,
  CircleCloseFilled,
  Delete,
  Loading,
  Plus,
  Promotion,
  VideoPause
} from '@element-plus/icons-vue'
import { api } from '@/utils/api'
import type { AiAgentSession, AiAgentSessionSummary, AiAgentStreamEvent } from '@/types/ai-agent'

/** 会话列表 */
const sessions = ref<AiAgentSessionSummary[]>([])
const currentSessionId = ref<number | null>(null)
const activeSession = ref<AiAgentSession | null>(null)
const listLoading = ref(false)
const sessionLoading = ref(false)
const creating = ref(false)
const streaming = ref(false)
const syncing = ref(false)
const activeController = ref<AbortController | null>(null)
const draft = ref('')

/** 当前会话渲染条目：消息气泡 / 工具调用 / 错误提示 */
interface ChatItem {
  seq: number
  kind: 'user' | 'assistant' | 'tool' | 'error'
  text: string
  thinking: string
  streaming: boolean
  thinkingOpen: boolean
  runIndex?: number
  toolCallId?: string
  toolName?: string
  toolRunning?: boolean
  toolError?: boolean
  toolResult?: string
}

const items = ref<ChatItem[]>([])
let seqCounter = 0

const messagesEl = ref<HTMLElement | null>(null)
const selectedSession = computed(() => sessions.value.find(s => s.id === currentSessionId.value))

const canSend = computed(() =>
  currentSessionId.value != null
  && draft.value.trim().length > 0
  && !streaming.value
  && !syncing.value
  && !sessionLoading.value
)

const sessionTitle = computed(() => activeSession.value?.title || selectedSession.value?.title || '新对话')

const modelInfo = computed(() => {
  const source = activeSession.value ?? selectedSession.value
  if (!source) return ''
  return source.model ? `${source.provider || 'AI'} · ${source.model}` : (source.provider || 'AI')
})

const sessionStat = computed(() => {
  const summary = selectedSession.value
  const session = activeSession.value
  if (!summary && !session) return ''
  const count = session ? session.messages.length : (summary?.messageCount ?? 0)
  const updated = formatTime(session?.updatedAt || summary?.updatedAt || '')
  return `${count} 条消息${updated ? ` · ${updated}` : ''}`
})

function scrollToBottom() {
  void nextTick(() => {
    const el = messagesEl.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

function formatTime(value: string): string {
  if (!value) return ''
  const time = new Date(value).getTime()
  if (Number.isNaN(time)) return value
  const diff = Date.now() - time
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  if (diff < 7 * 86_400_000) return `${Math.floor(diff / 86_400_000)} 天前`
  const date = new Date(time)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

function sessionMeta(summary: AiAgentSessionSummary): string {
  const count = `${summary.messageCount ?? 0} 条消息`
  const time = formatTime(summary.updatedAt)
  return time ? `${count} · ${time}` : count
}

/** 抽取消息 content 中的可展示文本；content 可能是字符串或内容片段数组（不透明） */
function extractContent(content: unknown): { text: string; thinking: string } {
  if (typeof content === 'string') return { text: content, thinking: '' }
  if (!Array.isArray(content)) return { text: '', thinking: '' }
  let text = ''
  const thinking: string[] = []
  for (const part of content) {
    if (!part || typeof part !== 'object') continue
    const record = part as { type?: unknown; text?: unknown; thinking?: unknown }
    if (record.type === 'text' && typeof record.text === 'string') text += record.text
    else if (record.type === 'thinking' && typeof record.thinking === 'string' && record.thinking) {
      thinking.push(record.thinking)
    }
  }
  return { text, thinking: thinking.join('\n') }
}

function formatToolResult(result: unknown): string {
  if (result == null || result === '') return '无返回内容'
  const raw = typeof result === 'string' ? result : JSON.stringify(result)
  const text = raw ?? String(result)
  return text.length > 260 ? `${text.slice(0, 260)}…` : text
}

function errorText(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback
}

/** 由服务端消息重建渲染列表（只渲染 user 文本与 assistant 的 text/thinking） */
function rebuildFromSession(session: AiAgentSession) {
  seqCounter = 0
  const next: ChatItem[] = []
  for (const message of session.messages) {
    if (message.role === 'user') {
      const text = typeof message.content === 'string' ? message.content : ''
      if (text) {
        next.push({ seq: seqCounter++, kind: 'user', text, thinking: '', streaming: false, thinkingOpen: false })
      }
    } else if (message.role === 'assistant') {
      const { text, thinking } = extractContent(message.content)
      if (text || thinking) {
        next.push({ seq: seqCounter++, kind: 'assistant', text, thinking, streaming: false, thinkingOpen: false })
      }
    }
  }
  items.value = next
}

async function loadSessions() {
  listLoading.value = true
  try {
    sessions.value = await api.getAiAgentSessions()
  } catch (err) {
    ElMessage.error(errorText(err, '会话列表加载失败'))
  } finally {
    listLoading.value = false
  }
}

async function openSession(id: number) {
  sessionLoading.value = true
  try {
    const session = await api.getAiAgentSession(id)
    activeSession.value = session
    currentSessionId.value = id
    rebuildFromSession(session)
    scrollToBottom()
  } catch (err) {
    ElMessage.error(errorText(err, '会话加载失败'))
  } finally {
    sessionLoading.value = false
  }
}

function selectSession(summary: AiAgentSessionSummary) {
  if (streaming.value || syncing.value || sessionLoading.value) return
  if (summary.id === currentSessionId.value) return
  void openSession(summary.id)
}

/** 流结束后静默拉取最新会话，与服务端最终消息对齐 */
async function resyncSession() {
  const id = currentSessionId.value
  if (id == null) return
  syncing.value = true
  try {
    const session = await api.getAiAgentSession(id)
    activeSession.value = session
    rebuildFromSession(session)
    void loadSessions()
    scrollToBottom()
  } catch {
    ElMessage.error('会话同步失败，请稍后手动刷新')
  } finally {
    syncing.value = false
  }
}

async function newChat() {
  if (streaming.value || syncing.value || sessionLoading.value) return
  creating.value = true
  try {
    const session = await api.createAiAgentSession({})
    currentSessionId.value = session.id
    activeSession.value = session
    rebuildFromSession(session)
    scrollToBottom()
    void loadSessions()
  } catch (err) {
    ElMessage.error(errorText(err, '新建会话失败'))
  } finally {
    creating.value = false
  }
}

async function deleteSession(summary: AiAgentSessionSummary) {
  if (streaming.value || syncing.value) return
  try {
    await ElMessageBox.confirm(
      `确定要删除会话「${summary.title || '新对话'}」吗？删除后不可恢复。`,
      '删除会话',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await api.deleteAiAgentSession(summary.id)
    sessions.value = sessions.value.filter(s => s.id !== summary.id)
    if (currentSessionId.value === summary.id) {
      currentSessionId.value = null
      activeSession.value = null
      items.value = []
    }
    ElMessage.success('会话已删除')
  } catch (err) {
    ElMessage.error(errorText(err, '删除会话失败'))
  }
}

async function sendMessage() {
  const sessionId = currentSessionId.value
  const content = draft.value.trim()
  if (sessionId == null || !content || streaming.value || syncing.value || sessionLoading.value) return

  const floor = seqCounter
  items.value.push({ seq: seqCounter++, kind: 'user', text: content, thinking: '', streaming: false, thinkingOpen: false })
  draft.value = ''
  scrollToBottom()

  const controller = new AbortController()
  activeController.value = controller
  streaming.value = true

  let stopped = false
  let streamFailed = false
  let gotProgress = false
  let gotErrorEvent = false

  const reportError = (message: string) => {
    streamFailed = true
    items.value.push({ seq: seqCounter++, kind: 'error', text: message, thinking: '', streaming: false, thinkingOpen: false })
    ElMessage.error(message)
    scrollToBottom()
  }

  try {
    await api.streamAiAgentRun(sessionId, content, (event: AiAgentStreamEvent) => {
      switch (event.type) {
        case 'run_start':
          return
        case 'message_start':
          gotProgress = true
          items.value.push({
            seq: seqCounter++,
            kind: 'assistant',
            runIndex: event.index,
            text: '',
            thinking: '',
            streaming: true,
            thinkingOpen: true
          })
          scrollToBottom()
          return
        case 'message_delta': {
          gotProgress = true
          const candidates = items.value.filter(i => i.seq >= floor && i.kind === 'assistant' && i.runIndex === event.index)
          const item = candidates[candidates.length - 1]
          if (!item) return
          if (event.delta.type === 'thinking_delta') item.thinking += event.delta.text
          else item.text += event.delta.text
          scrollToBottom()
          return
        }
        case 'message_end': {
          gotProgress = true
          // message_end 不含 index，取当前仍处于流式状态的最后一条助手消息
          const candidates = items.value.filter(i => i.seq >= floor && i.kind === 'assistant' && i.streaming)
          const item = candidates[candidates.length - 1]
          if (item) item.streaming = false
          return
        }
        case 'tool_execution_start':
          gotProgress = true
          items.value.push({
            seq: seqCounter++,
            kind: 'tool',
            text: '',
            thinking: '',
            streaming: false,
            thinkingOpen: false,
            toolCallId: event.toolCallId,
            toolName: event.toolName,
            toolRunning: true
          })
          scrollToBottom()
          return
        case 'tool_execution_end': {
          gotProgress = true
          const chip = items.value.find(i => i.seq >= floor && i.kind === 'tool' && i.toolCallId === event.toolCallId)
          if (chip) {
            chip.toolRunning = false
            chip.toolError = event.isError === true
            chip.toolResult = formatToolResult(event.result)
          }
          return
        }
        case 'run_end':
          gotProgress = true
          return
        case 'error':
          gotErrorEvent = true
          reportError(event.message || 'AI 服务异常，请稍后重试')
          // error 为终止事件：主动断开连接，结束流读取
          controller.abort()
          return
      }
    }, controller.signal)
  } catch (err) {
    const aborted = err instanceof DOMException && err.name === 'AbortError'
    if (aborted) {
      if (!gotErrorEvent) stopped = true
    } else if (!streamFailed) {
      reportError(errorText(err, '请求失败，请重试'))
    }
  } finally {
    streaming.value = false
    activeController.value = null
  }

  // 用户中止或出错时保留当前渲染；正常结束且有内容则重新拉取对齐
  if (!stopped && !streamFailed && gotProgress) {
    await resyncSession()
  }
}

function stopStream() {
  activeController.value?.abort()
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    void sendMessage()
  }
}

onMounted(async () => {
  await loadSessions()
  if (currentSessionId.value == null && sessions.value.length > 0) {
    await openSession(sessions.value[0].id)
  }
})
</script>

<template>
  <div class="ai-page">
    <!-- 左侧：会话列表 -->
    <aside class="ai-side">
      <div class="ai-side-head">
        <div class="ai-side-title">
          <el-icon><ChatDotRound /></el-icon>
          <span>对话列表</span>
        </div>
        <el-button type="primary" size="small" :icon="Plus" :loading="creating" :disabled="streaming || syncing" @click="newChat">
          新建对话
        </el-button>
      </div>

      <el-scrollbar class="ai-sessions" v-loading="listLoading">
        <div class="session-row-wrap">
          <div
            v-for="summary in sessions"
            :key="summary.id"
            class="session-row"
            :class="{ active: summary.id === currentSessionId }"
            @click="selectSession(summary)"
          >
            <div class="session-info">
              <div class="session-title">{{ summary.title || '新对话' }}</div>
              <div class="session-meta">{{ sessionMeta(summary) }}</div>
            </div>
            <el-icon class="session-delete" title="删除会话" @click.stop="deleteSession(summary)"><Delete /></el-icon>
          </div>
          <el-empty v-if="!listLoading && !sessions.length" description="暂无会话，点击上方新建对话" :image-size="70" />
        </div>
      </el-scrollbar>
    </aside>

    <!-- 右侧：对话区 -->
    <main class="ai-main" v-loading="sessionLoading">
      <!-- 未选择会话 -->
      <div v-if="currentSessionId == null && !sessionLoading" class="ai-welcome">
        <div class="welcome-icon"><el-icon><ChatDotRound /></el-icon></div>
        <h2>AI 智能助手</h2>
        <p>可以帮你梳理需求、分析数据、解答业务问题。选择一个会话，或开启新的对话吧。</p>
        <el-button type="primary" size="large" :icon="Plus" @click="newChat">新建对话</el-button>
      </div>

      <template v-else-if="currentSessionId != null">
        <!-- 会话头部 -->
        <header class="ai-main-head">
          <div class="ai-main-info">
            <div class="ai-main-title">{{ sessionTitle }}</div>
            <div class="ai-main-meta">{{ sessionStat }}</div>
          </div>
          <el-tag v-if="modelInfo" type="info" effect="plain">{{ modelInfo }}</el-tag>
        </header>

        <!-- 消息区 -->
        <section ref="messagesEl" class="ai-messages">
          <el-empty v-if="!items.length" description="暂无消息，开始提问吧" :image-size="80" />
          <template v-else>
            <template v-for="item in items" :key="item.seq">
              <!-- 用户消息：右侧 -->
              <div v-if="item.kind === 'user'" class="msg-row user">
                <div class="msg-bubble user-bubble">{{ item.text }}</div>
              </div>

              <!-- 助手消息：左侧 -->
              <div v-else-if="item.kind === 'assistant'" class="msg-row assistant">
                <div class="assistant-avatar"><el-icon><ChatDotRound /></el-icon></div>
                <div class="msg-bubble assistant-bubble">
                  <details v-if="item.thinking" class="thinking" :open="item.thinkingOpen">
                    <summary class="thinking-title">
                      <el-icon v-if="item.streaming && !item.text" class="is-loading thinking-spin"><Loading /></el-icon>
                      {{ item.streaming && !item.text ? '思考中…' : '思考过程' }}
                    </summary>
                    <div class="thinking-body">{{ item.thinking }}</div>
                  </details>
                  <div v-if="item.text || !item.streaming" class="assistant-text">{{ item.text }}</div>
                  <div v-else-if="!item.thinking" class="assistant-typing"><span class="dot" />正在生成回复…</div>
                </div>
              </div>

              <!-- 工具调用状态 -->
              <div v-else-if="item.kind === 'tool'" class="tool-row">
                <template v-if="item.toolRunning">
                  <span class="tool-chip running">
                    <el-icon class="is-loading"><Loading /></el-icon>
                    <span>调用工具 {{ item.toolName }}…</span>
                  </span>
                </template>
                <el-tooltip v-else :content="item.toolResult || (item.toolError ? '调用失败' : '执行完成')" placement="top">
                  <span class="tool-chip" :class="item.toolError ? 'failed' : 'done'">
                    <el-icon><CircleCloseFilled v-if="item.toolError" /><CircleCheck v-else /></el-icon>
                    <span>{{ item.toolError ? `工具 ${item.toolName} 执行失败` : `工具 ${item.toolName} 执行完成` }}</span>
                  </span>
                </el-tooltip>
              </div>

              <!-- 流内错误 -->
              <div v-else-if="item.kind === 'error'" class="error-row">
                <el-icon><CircleCloseFilled /></el-icon>
                <span>{{ item.text }}</span>
              </div>
            </template>
          </template>
        </section>

        <!-- 输入区 -->
        <footer class="composer">
          <el-input
            v-model="draft"
            type="textarea"
            resize="none"
            :rows="2"
            :autosize="{ minRows: 2, maxRows: 8 }"
            placeholder="输入你的问题…"
            :disabled="currentSessionId == null || streaming || syncing"
            @keydown="onKeydown"
          />
          <div class="composer-bar">
            <span class="composer-hint">Enter 发送，Shift + Enter 换行</span>
            <div class="composer-actions">
              <el-button v-if="streaming" type="danger" plain :icon="VideoPause" @click="stopStream">停止生成</el-button>
              <el-button v-else type="primary" :icon="Promotion" :disabled="!canSend" @click="sendMessage">发送</el-button>
            </div>
          </div>
        </footer>
      </template>
    </main>
  </div>
</template>

<style scoped>
.ai-page {
  display: flex;
  height: calc(100dvh - 64px - 48px);
  min-width: 0;
  background: #fff;
  border: 1px solid var(--gray-200);
  border-radius: var(--radius-lg);
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.04);
  overflow: hidden;
  text-align: left;
}

/* ============ 左侧会话列表 ============ */
.ai-side {
  display: flex;
  flex-direction: column;
  width: 280px;
  flex: 0 0 280px;
  border-right: 1px solid var(--gray-200);
  background: var(--gray-50);
}

.ai-side-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 16px;
  border-bottom: 1px solid var(--gray-200);
}

.ai-side-title {
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
  color: var(--gray-800);
  font-size: 15px;
  font-weight: 600;
}

.ai-sessions {
  flex: 1;
  min-height: 0;
}

.session-row-wrap {
  padding: 10px;
}

.session-row {
  position: relative;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 10px;
  margin-bottom: 4px;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: background 0.15s ease;
}

.session-row:hover {
  background: var(--gray-100);
}

.session-row.active {
  background: var(--primary-light);
}

.session-info {
  flex: 1;
  min-width: 0;
}

.session-title {
  overflow: hidden;
  color: var(--gray-800);
  font-size: 13.5px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-row.active .session-title {
  color: var(--primary);
}

.session-meta {
  margin-top: 3px;
  overflow: hidden;
  color: var(--gray-500);
  font-size: 11.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-delete {
  flex: 0 0 auto;
  padding: 4px;
  border-radius: 6px;
  color: var(--gray-400);
  opacity: 0;
  cursor: pointer;
  transition: opacity 0.15s ease;
}

.session-delete:hover {
  color: var(--danger);
  background: #fee2e2;
}

.session-row:hover .session-delete,
.session-row.active .session-delete {
  opacity: 1;
}

/* ============ 右侧对话区 ============ */
.ai-main {
  position: relative;
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}

.ai-main-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 20px;
  border-bottom: 1px solid var(--gray-200);
}

.ai-main-info {
  min-width: 0;
}

.ai-main-title {
  overflow: hidden;
  color: var(--gray-900);
  font-size: 17px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-main-meta {
  margin-top: 3px;
  color: var(--gray-500);
  font-size: 12px;
}

/* ============ 消息区 ============ */
.ai-messages {
  flex: 1;
  min-height: 0;
  padding: 18px 20px 10px;
  overflow-y: auto;
  background: #f8fafc;
}

.msg-row {
  display: flex;
  margin-bottom: 14px;
}

.msg-row.user {
  justify-content: flex-end;
}

.assistant-avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  margin-right: 10px;
  border-radius: 50%;
  background: var(--primary-light);
  color: var(--primary);
}

.msg-bubble {
  max-width: 72%;
  border-radius: 12px;
  line-height: 1.65;
  word-break: break-word;
  white-space: pre-wrap;
}

.user-bubble {
  padding: 10px 14px;
  background: var(--primary);
  color: #fff;
  border-bottom-right-radius: 4px;
}

.assistant-bubble {
  min-width: 0;
  padding: 10px 14px;
  background: #fff;
  border: 1px solid var(--gray-200);
  border-bottom-left-radius: 4px;
  box-shadow: var(--shadow-sm);
}

.thinking {
  margin-bottom: 8px;
  padding: 6px 10px;
  border-left: 3px solid var(--gray-300);
  border-radius: 4px;
  background: var(--gray-50);
}

.thinking-title {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--gray-500);
  font-size: 12px;
  cursor: pointer;
  list-style: none;
  user-select: none;
}

.thinking-title::-webkit-details-marker {
  display: none;
}

.thinking-spin {
  color: var(--primary);
}

.thinking-body {
  margin-top: 6px;
  max-height: 220px;
  overflow-y: auto;
  color: var(--gray-600);
  font-size: 12.5px;
  white-space: pre-wrap;
}

.assistant-text {
  color: var(--gray-800);
  font-size: 14px;
}

.assistant-typing {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--gray-400);
  font-size: 13px;
}

.dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--primary);
  animation: blink 1.2s infinite ease-in-out;
}

@keyframes blink {
  0%, 100% { opacity: 0.2; transform: scale(0.8); }
  50% { opacity: 1; transform: scale(1); }
}

/* 工具调用 & 错误 */
.tool-row {
  display: flex;
  justify-content: flex-start;
  margin-bottom: 12px;
}

.tool-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 85%;
  padding: 5px 12px;
  border-radius: 999px;
  font-size: 12.5px;
  border: 1px solid var(--gray-200);
  background: #fff;
  color: var(--gray-600);
  cursor: default;
}

.tool-chip.running {
  color: var(--gray-500);
}

.tool-chip .el-icon {
  flex: 0 0 auto;
}

.tool-chip span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tool-chip.done {
  color: var(--success);
  border-color: #a7f3d0;
  background: #ecfdf5;
}

.tool-chip.failed {
  color: var(--danger);
  border-color: #fecaca;
  background: #fef2f2;
}

.error-row {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin-bottom: 12px;
  padding: 8px 12px;
  border-radius: 8px;
  color: var(--danger);
  background: #fef2f2;
  border: 1px solid #fecaca;
  font-size: 13px;
}

.error-row .el-icon {
  margin-top: 2px;
  flex: 0 0 auto;
}

/* ============ 输入区 ============ */
.composer {
  padding: 12px 16px 14px;
  border-top: 1px solid var(--gray-200);
  background: #fff;
}

.composer-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 8px;
}

.composer-hint {
  color: var(--gray-400);
  font-size: 12px;
}

.composer-actions {
  display: flex;
  gap: 8px;
}

/* ============ 欢迎空状态 ============ */
.ai-welcome {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  flex: 1;
  padding: 40px;
  text-align: center;
}

.welcome-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 72px;
  height: 72px;
  margin-bottom: 18px;
  border-radius: 20px;
  background: var(--primary-light);
  color: var(--primary);
  font-size: 34px;
}

.ai-welcome h2 {
  margin: 0 0 8px;
  color: var(--gray-900);
  font-size: 20px;
}

.ai-welcome p {
  max-width: 420px;
  margin: 0 0 20px;
  color: var(--gray-500);
  font-size: 13.5px;
  line-height: 1.7;
}

@media (max-width: 900px) {
  .ai-side {
    width: 220px;
    flex-basis: 220px;
  }
}

@media (max-width: 720px) {
  .ai-page {
    height: calc(100dvh - 64px - 24px);
  }

  .ai-side {
    display: none;
  }

  .msg-bubble {
    max-width: 86%;
  }
}
</style>
