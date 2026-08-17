const ALLOWED_TAGS = new Set([
  'A',
  'B',
  'BLOCKQUOTE',
  'BR',
  'DIV',
  'EM',
  'H1',
  'H2',
  'H3',
  'I',
  'IMG',
  'LI',
  'OL',
  'P',
  'SPAN',
  'STRONG',
  'U',
  'UL'
])

const BLOCKED_TAGS = new Set([
  'BUTTON',
  'EMBED',
  'FORM',
  'IFRAME',
  'INPUT',
  'LINK',
  'MATH',
  'META',
  'OBJECT',
  'SCRIPT',
  'STYLE',
  'SVG'
])

const ALLOWED_ATTRIBUTES: Record<string, Set<string>> = {
  A: new Set(['href', 'target', 'title']),
  IMG: new Set(['alt', 'height', 'src', 'title', 'width'])
}

const isSafeUrl = (value: string, image = false) => {
  const trimmed = value.trim()

  if (image && /^data:image\/(?:png|jpe?g|gif|webp);base64,/i.test(trimmed)) {
    return true
  }

  try {
    const parsed = new URL(trimmed, window.location.origin)
    return image
      ? ['http:', 'https:'].includes(parsed.protocol)
      : ['http:', 'https:', 'mailto:', 'tel:'].includes(parsed.protocol)
  } catch {
    return false
  }
}

const sanitizeElement = (element: Element) => {
  Array.from(element.children).forEach(sanitizeElement)

  if (BLOCKED_TAGS.has(element.tagName)) {
    element.remove()
    return
  }

  if (!ALLOWED_TAGS.has(element.tagName)) {
    element.replaceWith(...Array.from(element.childNodes))
    return
  }

  const allowedAttributes = ALLOWED_ATTRIBUTES[element.tagName] || new Set<string>()
  Array.from(element.attributes).forEach(attribute => {
    const name = attribute.name.toLowerCase()
    const value = attribute.value

    if (!allowedAttributes.has(name)) {
      element.removeAttribute(attribute.name)
      return
    }

    if (name === 'href' && !isSafeUrl(value)) {
      element.removeAttribute(attribute.name)
      return
    }

    if (name === 'src' && !isSafeUrl(value, true)) {
      element.removeAttribute(attribute.name)
      return
    }

    if (name === 'target' && value !== '_blank') {
      element.removeAttribute(attribute.name)
      return
    }

    if ((name === 'width' || name === 'height')
      && (!/^\d{1,4}$/.test(value) || Number(value) > 2048)) {
      element.removeAttribute(attribute.name)
    }
  })

  if (element.tagName === 'A' && element.getAttribute('target') === '_blank') {
    element.setAttribute('rel', 'noopener noreferrer')
  }
}

export const sanitizeHtml = (value: string) => {
  if (!value) return ''

  const document = new DOMParser().parseFromString(value, 'text/html')
  Array.from(document.body.children).forEach(sanitizeElement)
  return document.body.innerHTML
}
