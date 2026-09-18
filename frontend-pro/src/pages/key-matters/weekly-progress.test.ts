import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import dayjs from "dayjs";

const source = readFileSync(
  join(dirname(fileURLToPath(import.meta.url)), "index.tsx"),
  "utf8"
);

const mondayOf = (value?: dayjs.ConfigType) => {
  const date = dayjs(value);
  const weekday = date.day() || 7;
  return date.startOf("day").subtract(weekday - 1, "day");
};
const weekRangeLabel = (value?: dayjs.ConfigType) => {
  const start = mondayOf(value);
  const end = start.add(6, "day");
  return `${start.format("MM/DD")} - ${end.format("MM/DD")}`;
};

describe("key-matter weekly progress", () => {
  it("always sends ISO Monday as weekStartDate", () => {
    expect(mondayOf("2026-09-17").format("YYYY-MM-DD")).toBe("2026-09-14");
    expect(mondayOf("2026-09-14").format("YYYY-MM-DD")).toBe("2026-09-14");
    expect(mondayOf("2026-09-20").format("YYYY-MM-DD")).toBe("2026-09-14");
    expect(source).toMatch(
      /const mondayOf = \(value\?: dayjs\.ConfigType\) =>/
    );
    expect(source).not.toMatch(/startOf\(["']week["']\)\.add\(1, ["']day["']\)/);
    expect(source).toMatch(
      /const week = mondayOf\(values\.weekStartDate as dayjs\.ConfigType\)\.format\(/
    );
    expect(source).toMatch(
      /await superworkApi\.getKeyMatterMeeting\([\s\S]*?mondayOf\(\)\.format\(["']YYYY-MM-DD["']\)/
    );
  });

  it("titles the weekly modal with the current week range", () => {
    expect(weekRangeLabel("2026-09-17")).toBe("09/14 - 09/20");
    expect(source).toMatch(
      /title=\{`\$\{weekRangeLabel\(weeklyFormWeekStart\)\} 周进展`\}/
    );
  });

  it("uses a draggable progress slider instead of preset buttons", () => {
    const weeklyModal = source.slice(
      source.indexOf('className="sw-weekly-modal"')
    );
    expect(weeklyModal).toMatch(/className="sw-presentation-brief-slider"/);
    expect(weeklyModal).toMatch(
      /className="sw-presentation-brief-input"[\s\S]*?suffix="%"/
    );
    expect(weeklyModal).not.toMatch(/sw-presentation-brief-presets/);
    expect(weeklyModal).not.toMatch(/progressPresets\.map/);
  });

  it("uses the weekly-meeting presentation cards while keeping history", () => {
    expect(source).toMatch(/className="sw-weekly-main sw-presentation-stage"/);
    expect(source).toMatch(/className="sw-presentation-brief"/);
    expect(source).toMatch(/title="问题 \/ 风险"/);
    expect(source).toMatch(/title="需协调 \/ 决策"/);
    expect(source).toMatch(/title="下一步行动"/);
    expect(source).toMatch(
      /<aside className="sw-weekly-history" aria-label="历史周进展">/
    );
  });
});
