import { Input } from "antd";
import { useMemo, useState } from "react";

type MentionUser = {
  id: number;
  realName?: string;
  username?: string;
};

const tokenAtCaret = (value: string, caret: number) => {
  const before = value.slice(0, caret);
  const match = before.match(/(^|[\s，。；;、,])@([\p{L}\p{N}._-]{0,40})$/u);
  if (!match) return null;
  return {
    query: match[2] || "",
    start: before.length - match[2].length - 1,
  };
};

export const MentionsField = ({
  value,
  onChange,
  users,
  placeholder,
  rows = 3,
}: {
  value?: string;
  onChange?: (value: string) => void;
  users: MentionUser[];
  placeholder?: string;
  rows?: number;
}) => {
  const [caret, setCaret] = useState(0);
  const [open, setOpen] = useState(false);
  const current = tokenAtCaret(String(value || ""), caret);
  const query = (current?.query || "").toLowerCase();
  const options = useMemo(() => {
    if (!current) return [];
    return users
      .filter((user) => {
        const name = (user.realName || user.username || "").toLowerCase();
        const username = (user.username || "").toLowerCase();
        return !query || name.includes(query) || username.includes(query);
      })
      .slice(0, 8);
  }, [current, query, users]);

  const insertMention = (user: MentionUser) => {
    if (!current) return;
    const label = user.realName || user.username || String(user.id);
    const next = `${String(value || "").slice(0, current.start)}@${label} ${String(
      value || ""
    ).slice(caret)}`;
    onChange?.(next);
    setOpen(false);
  };

  return (
    <div className="sw-mentions-field">
      <Input.TextArea
        rows={rows}
        value={value}
        placeholder={placeholder}
        onChange={(event) => {
          const next = event.target.value;
          const nextCaret = event.target.selectionStart ?? next.length;
          setCaret(nextCaret);
          setOpen(Boolean(tokenAtCaret(next, nextCaret)));
          onChange?.(next);
        }}
        onKeyUp={(event) => {
          const target = event.currentTarget;
          setCaret(target.selectionStart ?? 0);
        }}
        onClick={(event) => {
          setCaret(event.currentTarget.selectionStart ?? 0);
        }}
      />
      {open && options.length > 0 && (
        <ul className="sw-mentions-menu" role="listbox">
          {options.map((user) => (
            <li key={user.id}>
              <button type="button" onMouseDown={() => insertMention(user)}>
                <strong>{user.realName || user.username}</strong>
                {user.username && user.realName ? (
                  <span>@{user.username}</span>
                ) : null}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
};
