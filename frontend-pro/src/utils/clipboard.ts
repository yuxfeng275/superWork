/**
 * 复制文本到剪贴板。
 *
 * 优先用 navigator.clipboard（HTTPS/localhost 安全上下文）；生产入口是 HTTP，
 * 该接口不可用，走 execCommand('copy') 兜底。
 *
 * 兜底的关键：execCommand('copy') 复制的是「当前选区」，在 Drawer / 离屏 textarea 场景下
 * 选区可能没落到我们的文本上，于是出现「提示复制成功，但粘贴出来不是要复制的内容」。
 * 这里在 copy 事件里用 clipboardData.setData 显式写入 text/plain，覆盖掉选区内容，
 * 保证剪贴板一定是传入的文本。返回是否复制成功。
 */
export const copyTextToClipboard = async (text: string): Promise<boolean> => {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      // 非安全上下文或权限被拒：走选区复制
    }
  }
  return execCommandCopy(text);
};

const execCommandCopy = (text: string): boolean => {
  const onCopy = (event: ClipboardEvent) => {
    if (!event.clipboardData) return;
    event.clipboardData.setData('text/plain', text);
    event.preventDefault();
  };

  const active = document.activeElement as HTMLElement | null;
  const selection = document.getSelection();
  const savedRanges: Range[] = [];
  if (selection) {
    for (let i = 0; i < selection.rangeCount; i += 1) {
      savedRanges.push(selection.getRangeAt(i));
    }
  }

  const area = document.createElement('textarea');
  area.value = text;
  area.setAttribute('readonly', '');
  area.setAttribute('aria-hidden', 'true');
  // 留在视口内（1px 透明），比 left:-9999px 更容易被 select() 选中
  area.style.position = 'fixed';
  area.style.top = '0';
  area.style.left = '0';
  area.style.width = '1px';
  area.style.height = '1px';
  area.style.padding = '0';
  area.style.border = '0';
  area.style.outline = 'none';
  area.style.boxShadow = 'none';
  area.style.background = 'transparent';
  area.style.opacity = '0';
  document.body.appendChild(area);

  let ok = false;
  try {
    area.focus({ preventScroll: true });
    area.select();
    area.setSelectionRange?.(0, area.value.length);
    // 监听 copy 事件，显式写入剪贴板内容（不依赖选区）
    document.addEventListener('copy', onCopy, true);
    ok = document.execCommand('copy');
  } catch {
    ok = false;
  } finally {
    document.removeEventListener('copy', onCopy, true);
    if (area.parentNode) area.parentNode.removeChild(area);
    if (selection) {
      selection.removeAllRanges();
      for (const range of savedRanges) selection.addRange(range);
    }
    active?.focus?.({ preventScroll: true });
  }
  return ok;
};
