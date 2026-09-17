import { UpOutlined } from '@ant-design/icons';
import type { MenuDataItem } from '@ant-design/pro-components';
import { Link, useLocation } from '@umijs/max';
import { Badge } from 'antd';
import React, { useId, useState } from 'react';

export const pathMatches = (path: string | undefined, pathname: string) =>
  Boolean(
    path &&
      (pathname === path || (path !== '/' && pathname.startsWith(`${path}/`))),
  );

export const itemHasActivePath = (
  item: MenuDataItem,
  pathname: string,
): boolean =>
  pathMatches(item.path, pathname) ||
  Boolean(item.children?.some((child) => itemHasActivePath(child, pathname)));

export const firstPagePath = (item: MenuDataItem): string | undefined => {
  for (const child of item.children || []) {
    const path = firstPagePath(child);
    if (path) return path;
  }
  return item.path;
};

type MenuEntryProps = {
  item: MenuDataItem;
  pathname: string;
  depth?: number;
  requirementTotal?: number;
};

const MenuEntry = ({
  item,
  pathname,
  depth = 0,
  requirementTotal,
}: MenuEntryProps) => {
  const [expanded, setExpanded] = useState(true);
  const groupId = useId();
  if (item.children?.length) {
    return (
      <div className={`sw-nav-group sw-nav-group-level-${depth + 2}`}>
        <button
          className="sw-nav-group-button"
          type="button"
          aria-expanded={expanded}
          aria-controls={groupId}
          onClick={() => setExpanded(!expanded)}
        >
          <span className="sw-nav-item-label">{item.name}</span>
          <UpOutlined
            className={expanded ? '' : 'is-collapsed'}
            aria-hidden="true"
          />
        </button>
        <div id={groupId} className="sw-nav-children" hidden={!expanded}>
          {item.children.map((child) => (
            <MenuEntry
              key={String(child.key || child.path || child.name)}
              item={child}
              pathname={pathname}
              depth={depth + 1}
              requirementTotal={requirementTotal}
            />
          ))}
        </div>
      </div>
    );
  }
  if (!item.path) return null;
  const active = pathMatches(item.path, pathname);
  return (
    <Link
      to={item.path}
      className={`sw-nav-item sw-nav-level-${depth + 2}${active ? ' is-active' : ''}`}
      aria-current={active ? 'page' : undefined}
    >
      <span className="sw-nav-item-label">{item.name}</span>
      {item.path === '/requirements' && Boolean(requirementTotal) && (
        <Badge count={requirementTotal} size="small" overflowCount={99} />
      )}
    </Link>
  );
};

export default function ConsoleNavigation({
  items,
  requirementTotal,
}: {
  items: MenuDataItem[];
  requirementTotal?: number;
}) {
  const { pathname } = useLocation();
  const activeSection = items.find((item) => itemHasActivePath(item, pathname));
  const showPanel = Boolean(activeSection?.children?.length);
  return (
    <div className={`sw-nav${showPanel ? '' : ' sw-nav-no-panel'}`}>
      <nav className="sw-nav-primary" aria-label="业务域">
        {items.map((item) => {
          const path = firstPagePath(item);
          if (!path) return null;
          const active = item === activeSection;
          return (
            <Link
              key={String(item.key || item.path || item.name)}
              to={path}
              className={`sw-nav-primary-item${active ? ' is-selected' : ''}`}
              title={String(item.name || '')}
              aria-current={
                active && !item.children?.length ? 'page' : undefined
              }
            >
              <span className="sw-nav-primary-icon" aria-hidden="true">
                {item.icon}
              </span>
              <span className="sw-nav-primary-label">{item.name}</span>
            </Link>
          );
        })}
      </nav>
      {showPanel && (
        <section
          className="sw-nav-panel"
          aria-label={`${activeSection?.name}子菜单`}
        >
          <header className="sw-nav-panel-header">{activeSection?.name}</header>
          <nav
            className="sw-nav-panel-list"
            aria-label={`${activeSection?.name}页面`}
          >
            {activeSection?.children?.map((item) => (
              <MenuEntry
                key={`${pathname}:${item.key || item.path || item.name}`}
                item={item}
                pathname={pathname}
                requirementTotal={requirementTotal}
              />
            ))}
          </nav>
        </section>
      )}
    </div>
  );
}
