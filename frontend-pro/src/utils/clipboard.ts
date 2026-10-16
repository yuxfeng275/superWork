/**
 * 复制文本到剪贴板：优先用 navigator.clipboard（HTTPS/localhost 安全上下文）；
 * 生产为 HTTP 页面时该接口不可用，回退到 textarea + document.execCommand('copy')。
 * 与周报详情抽屉的复制实现保持同一兜底策略。返回是否复制成功。
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
  try {
    const area = document.createElement('textarea');
    area.value = text;
    area.setAttribute('readonly', '');
    area.style.position = 'fixed';
    area.style.left = '-9999px';
    document.body.appendChild(area);
    area.select();
    const ok = document.execCommand('copy');
    document.body.removeChild(area);
    return ok;
  } catch {
    return false;
  }
};
