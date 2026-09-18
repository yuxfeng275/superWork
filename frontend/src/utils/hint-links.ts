/** 后端提示文案的链接片段：Markdown 链接与裸链都可点，不经过 v-html。 */

export interface HintLinkSegment {
  text: string
  href?: string
}

/**
 * 续期/提示文案里的链接：Markdown 形式优先，裸链兜底（CLI help_message 两种都可能出现）。
 * 裸链在 CJK 字符 / 全角标点处收口，避免把紧跟其后的中文吞进链接目标。
 */
const HINT_LINK_PATTERN = /\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)|(https?:\/\/[^\s\u3000-\u303f\uff00-\uffef\u4e00-\u9fff]+)/g

/** 把提示文案切成文本/链接片段，供模板逐段渲染（有 href 即为可点外链）。 */
export function splitHintLinks(message: string): HintLinkSegment[] {
  const segments: HintLinkSegment[] = []
  let cursor = 0
  for (const match of message.matchAll(HINT_LINK_PATTERN)) {
    const start = match.index ?? 0
    const [raw, label, markdownHref, bareHref] = match
    // 裸链常把句末 ASCII 标点一起吞掉，回退到标点前
    const href = markdownHref ?? bareHref.replace(/[.,;:!?]+$/, '')
    if (start > cursor) segments.push({ text: message.slice(cursor, start) })
    segments.push({ text: label ?? href, href })
    cursor = start + (markdownHref ? raw.length : href.length)
  }
  if (cursor < message.length) segments.push({ text: message.slice(cursor) })
  return segments
}
