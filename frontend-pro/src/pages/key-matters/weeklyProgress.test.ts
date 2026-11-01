import { describe, expect, it } from "vitest";
import { resolveWeeklyState } from "./weeklyProgress";

describe("resolveWeeklyState", () => {
  it("falls back to the existing state when status and progress are missing", () => {
    expect(resolveWeeklyState(undefined, undefined, "有风险", 60)).toEqual({
      status: "有风险",
      progress: 60,
    });
    expect(resolveWeeklyState(null, null, "推进中", 40)).toEqual({
      status: "推进中",
      progress: 40,
    });
  });

  it("forces 100% when the status is completed", () => {
    expect(resolveWeeklyState("已完成", 40, "推进中", 40)).toEqual({
      status: "已完成",
      progress: 100,
    });
  });

  it("clamps 100% down to 99% when the status is not completed", () => {
    expect(resolveWeeklyState("有风险", 100, "已完成", 100)).toEqual({
      status: "有风险",
      progress: 99,
    });
  });

  it("clamps out-of-range values and blanks", () => {
    expect(resolveWeeklyState("推进中", 150, "推进中", 40).progress).toBe(99);
    expect(resolveWeeklyState("推进中", -10, "推进中", 40).progress).toBe(0);
    expect(resolveWeeklyState("", "", "", 40)).toEqual({
      status: "推进中",
      progress: 40,
    });
  });
});
