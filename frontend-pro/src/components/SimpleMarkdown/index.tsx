import { history } from "@umijs/max";
import type { MouseEvent, ReactNode } from "react";

type MentionUser = {
  id: number;
  realName?: string;
  username?: string;
};

const escapeHtml = (value: string) =>
  value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");

const mentionHref = (token: string, users?: MentionUser[]) => {
  const key = token.toLowerCase();
  const matched = users?.find((user) => {
    const name = (user.realName || "").toLowerCase();
    const username = (user.username || "").toLowerCase();
    return name === key || username === key || name.startsWith(key) || username.startsWith(key);
  });
  if (matched?.id) return `/todos?userId=${matched.id}`;
  return `/todos?user=${encodeURIComponent(token)}`;
};

const inlineMarkdown = (value: string, users?: MentionUser[]) => {
  const escaped = escapeHtml(value);
  return escaped
    .replace(/`([^`]+)`/g, "<code>$1</code>")
    .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
    .replace(/(^|[^*])\*([^*]+)\*/g, "$1<em>$2</em>")
    .replace(/@([\p{L}\p{N}._-]{1,40})/gu, (_full, token: string) => {
      const href = mentionHref(token, users);
      return `<a class="sw-md-mention" href="${href}">@${token}</a>`;
    });
};

export const SimpleMarkdown = ({
  value,
  className,
  empty,
  users,
}: {
  value?: string;
  className?: string;
  empty?: ReactNode;
  users?: MentionUser[];
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
          .map((line) => `<li>${inlineMarkdown(line.replace(/^[-*]\s+/, ""), users)}</li>`)
          .join("");
        return `<ul>${items}</ul>`;
      }
      return `<p>${lines.map((line) => inlineMarkdown(line, users)).join("<br/>")}</p>`;
    })
    .join("");
  const onClick = (event: MouseEvent<HTMLDivElement>) => {
    const target = event.target as HTMLElement | null;
    const link = target?.closest("a.sw-md-mention") as HTMLAnchorElement | null;
    if (!link) return;
    const href = link.getAttribute("href");
    if (!href?.startsWith("/")) return;
    event.preventDefault();
    history.push(href);
  };
  return (
    <div
      className={`sw-simple-md ${className || ""}`.trim()}
      onClick={onClick}
      dangerouslySetInnerHTML={{ __html: html }}
    />
  );
};
