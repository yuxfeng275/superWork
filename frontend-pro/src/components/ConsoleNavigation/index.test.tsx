import { fireEvent, render, screen } from "@testing-library/react";
import type { AnchorHTMLAttributes } from "react";
import React from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AppstoreOutlined, HomeOutlined } from "@ant-design/icons";
import ConsoleNavigation, { firstPagePath, primaryMenuIcon } from "./index";

const location = vi.hoisted(() => ({ pathname: "/workbench" }));

vi.mock("@umijs/max", () => ({
  useLocation: () => location,
  Link: ({
    to,
    ...props
  }: AnchorHTMLAttributes<HTMLAnchorElement> & { to: string }) => (
    <a href={to} {...props} />
  ),
}));

const items = [
  {
    key: "home",
    name: "首页",
    path: "/workbench",
    icon: <HomeOutlined />,
  },
  {
    key: "work",
    name: "工作台",
    icon: <HomeOutlined />,
    children: [
      { name: "需求管理", path: "/requirements" },
      { name: "任务管理", path: "/tasks" },
    ],
  },
  {
    key: "system",
    name: "系统管理",
    path: "/system",
    children: [
      {
        key: "access",
        name: "权限管理",
        children: [
          { name: "用户管理", path: "/system/users" },
          { name: "角色管理", path: "/system/roles" },
        ],
      },
    ],
  },
  { key: "ai", name: "AI 助手", path: "/ai-assistant" },
];

describe("console navigation", () => {
  beforeEach(() => {
    location.pathname = "/workbench";
  });

  it("keeps home as a house and workbench as an app grid even if the server sent HomeFilled", () => {
    const iconType = (node: React.ReactNode) =>
      React.isValidElement(node) ? node.type : undefined;
    expect(iconType(primaryMenuIcon(items[0]))).toBe(HomeOutlined);
    expect(iconType(primaryMenuIcon(items[1]))).toBe(AppstoreOutlined);
    const { container } = render(<ConsoleNavigation items={items} />);
    const icons = container.querySelectorAll(".sw-nav-primary-icon");
    expect(icons[0]?.querySelector(".anticon-home")).toBeTruthy();
    expect(icons[1]?.querySelector(".anticon-appstore")).toBeTruthy();
  });

  it("keeps the home rail without a secondary panel", () => {
    const { container } = render(<ConsoleNavigation items={items} />);
    expect(screen.getByRole("link", { name: "首页" })).toHaveAttribute(
      "href",
      "/workbench"
    );
    expect(screen.getByRole("link", { name: "首页" })).toHaveAttribute(
      "aria-current",
      "page"
    );
    expect(screen.queryByRole("region")).not.toBeInTheDocument();
    expect(container.firstChild).toHaveClass("sw-nav-no-panel");
  });

  it("links a primary group directly to its first second-level page", () => {
    render(<ConsoleNavigation items={items} />);
    expect(screen.getByRole("link", { name: "工作台" })).toHaveAttribute(
      "href",
      "/requirements"
    );
  });

  it("prefers the first third-level page over a group parent path", () => {
    render(<ConsoleNavigation items={items} />);
    expect(screen.getByRole("link", { name: "系统管理" })).toHaveAttribute(
      "href",
      "/system/users"
    );
  });

  it("links a standalone primary item to its own page", () => {
    location.pathname = "/ai-assistant";
    render(<ConsoleNavigation items={items} />);
    expect(screen.getByRole("link", { name: "AI 助手" })).toHaveAttribute(
      "href",
      "/ai-assistant"
    );
    expect(screen.getByRole("link", { name: "AI 助手" })).toHaveAttribute(
      "aria-current",
      "page"
    );
    expect(screen.queryByRole("region")).not.toBeInTheDocument();
  });

  it("shows the correct section immediately on a nested detail URL", () => {
    location.pathname = "/requirements/42";
    render(<ConsoleNavigation items={items} requirementTotal={120} />);
    expect(
      screen.getByRole("region", { name: "工作台子菜单" })
    ).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /需求管理/ })).toHaveAttribute(
      "aria-current",
      "page"
    );
    expect(screen.getByText("99+")).toBeInTheDocument();
  });

  it("removes the panel when browser navigation returns home", () => {
    location.pathname = "/tasks";
    const { rerender } = render(<ConsoleNavigation items={items} />);
    expect(screen.getByRole("region")).toBeInTheDocument();
    location.pathname = "/workbench";
    rerender(<ConsoleNavigation items={items} />);
    expect(screen.queryByRole("region")).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "首页" })).toHaveAttribute(
      "aria-current",
      "page"
    );
    expect(document.body).not.toHaveClass("sw-nav-rail-only");
  });

  it("does not assume the first authorized menu is home", () => {
    location.pathname = "/system/roles";
    render(<ConsoleNavigation items={items.slice(1)} />);
    expect(
      screen.queryByRole("link", { name: "首页" })
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("region", { name: "系统管理子菜单" })
    ).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "角色管理" })).toHaveAttribute(
      "aria-current",
      "page"
    );
  });

  it("can collapse and expand a third-level group using its button", () => {
    location.pathname = "/system/users";
    render(<ConsoleNavigation items={items} />);
    const toggle = screen.getByRole("button", { name: "权限管理" });
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(
      screen.queryByRole("link", { name: "用户管理" })
    ).not.toBeInTheDocument();
    fireEvent.click(toggle);
    expect(screen.getByRole("link", { name: "用户管理" })).toBeInTheDocument();
  });

  it("does not confuse adjacent route prefixes", () => {
    location.pathname = "/requirements-standalone/42";
    render(<ConsoleNavigation items={items} />);
    expect(screen.queryByRole("region")).not.toBeInTheDocument();
  });

  it("skips entries without a destination and handles an empty menu", () => {
    expect(
      firstPagePath({ children: [{ name: "空分组" }, { path: "/tasks" }] })
    ).toBe("/tasks");
    expect(firstPagePath({ name: "空分组" })).toBeUndefined();
    render(<ConsoleNavigation items={[]} />);
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });
});
