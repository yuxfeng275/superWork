/**
 * 会话消息的内容分片解析：把「给用户看的正文」与「模型思考」分开。
 *
 * 为什么单独抽出来：历史消息在页面重载后要重新渲染，如果沿用流式阶段的合并逻辑，
 * 会把 thinking 分片当成正文显示。E2E 上看到的「英文推理过程夹杂在回答里」就是这个原因。
 */

type Part = { type?: unknown; text?: unknown; thinking?: unknown };

const asPart = (value: unknown): Part | null =>
  typeof value === 'object' && value !== null ? (value as Part) : null;

/** 正文：只收 type=text（或无 type 但带 text）的分片，thinking / toolCall 一律不收。 */
export function extractMessageText(content: unknown): string {
  if (typeof content === 'string') return content;
  if (!Array.isArray(content)) return '';
  return content
    .map((item) => {
      const part = asPart(item);
      if (!part) return '';
      if (part.type !== undefined && part.type !== 'text') return '';
      return typeof part.text === 'string' ? part.text : '';
    })
    .join('');
}

/** 思考：只收 type=thinking（或无 type 但带 thinking）的分片，空串表示没有。 */
export function extractMessageThinking(content: unknown): string {
  if (!Array.isArray(content)) return '';
  return content
    .map((item) => {
      const part = asPart(item);
      if (!part) return '';
      if (part.type !== undefined && part.type !== 'thinking') return '';
      return typeof part.thinking === 'string' ? part.thinking : '';
    })
    .join('');
}
