import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  getTodos: vi.fn(),
  completeTodo: vi.fn(),
  push: vi.fn(),
}));

vi.mock("@umijs/max", () => ({
  history: { push: mocks.push },
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
    mocks.getTodos.mockResolvedValue([
      {
        id: 9,
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
});
