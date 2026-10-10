import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  getTodos: vi.fn(),
  completeTodo: vi.fn(),
  push: vi.fn(),
  search: "",
  username: "lisi",
}));

vi.mock("@umijs/max", () => ({
  history: { push: mocks.push },
  useLocation: () => ({ search: mocks.search }),
  useModel: () => ({
    initialState: { currentUser: { id: 8, username: mocks.username } },
  }),
}));

vi.mock("@/services/superwork/api", () => ({
  superworkApi: {
    getTodos: mocks.getTodos,
    completeTodo: mocks.completeTodo,
  },
}));

import TodosPage from "./index";

describe("TodosPage smoke", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.search = "";
    mocks.username = "lisi";
    mocks.getTodos.mockResolvedValue([
      {
        id: 9,
        assigneeId: 8,
        title: "姜涛 在「皇家项目」中提到了你",
        excerpt: "请 @李四 确认上线窗口",
        link: "/key-matters?matterId=11",
        status: "OPEN",
        createdAt: "2026-09-18T02:00:00",
      },
    ]);
    mocks.completeTodo.mockResolvedValue({ id: 9, status: "DONE" });
  });

  it("lists mention todos and can complete them", async () => {
    render(<TodosPage />);
    expect(await screen.findByText("姜涛 在「皇家项目」中提到了你")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /完成/ }));
    await waitFor(() => expect(mocks.completeTodo).toHaveBeenCalledWith(9));
  });

  it("loads another teammate todos from query", async () => {
    mocks.search = "?userId=7";
    render(<TodosPage />);
    await waitFor(() =>
      expect(mocks.getTodos).toHaveBeenCalledWith({
        userId: 7,
        user: undefined,
      })
    );
  });

  it("shows every assignee when the viewer is admin", async () => {
    mocks.username = "admin";
    mocks.getTodos.mockResolvedValue([
      {
        id: 12,
        assigneeId: 3,
        mentionToken: "王五",
        title: "姜涛 在「会员通」中提到了你",
        excerpt: "请 @王五 核对账单",
        status: "OPEN",
        createdAt: "2026-09-18T02:00:00",
      },
    ]);
    render(<TodosPage />);
    expect(await screen.findByText("全部待办")).toBeTruthy();
    expect(screen.getByText("王五")).toBeTruthy();
    expect(screen.queryByRole("button", { name: /完成/ })).toBeNull();
  });
});
