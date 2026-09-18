import type { ReactNode } from "react";

const escapeHtml = (value: string) =>
  value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");

const inlineMarkdown = (value: string) => {
  const escaped = escapeHtml(value);
  return escaped
    .replace(/`([^`]+)`/g, "<code>$1</code>")
    .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
    .replace(/(^|[^*])\*([^*]+)\*/g, "$1<em>$2</em>")
    .replace(
      /@([\p{L}\p{N}._-]{1,40})/gu,
      '<span class="sw-md-mention">@$1</span>'
    );
};

export const SimpleMarkdown = ({
  value,
  className,
  empty,
}: {
  value?: string;
  className?: string;
  empty?: ReactNode;
}) => {
  const text = String(value || "").trim();
  if (!text) return <>{empty}</>;
  const html = text
    .split(/\n{2,}/)
    .map((block) => {
      const lines = block.split("\n");
      const isList = lines.every((line) => /^[-*]\s+/.test(line.trim()));
      if (isList) {
        const items = lines
          .map((line) => `<li>${inlineMarkdown(line.replace(/^[-*]\s+/, ""))}</li>`)
          .join("");
        return `<ul>${items}</ul>`;
      }
      return `<p>${lines.map((line) => inlineMarkdown(line)).join("<br/>")}</p>`;
    })
    .join("");
  return (
    <div
      className={`sw-simple-md ${className || ""}`.trim()}
      dangerouslySetInnerHTML={{ __html: html }}
    />
  );
};
