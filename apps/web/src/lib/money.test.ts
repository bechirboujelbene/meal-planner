import { describe, expect, it } from "vitest";
import { formatCents } from "./money";

describe("formatCents", () => {
  it("formats cents as euros in German", () => {
    expect(formatCents(4320, "de")).toBe("43,20 €");
  });

  it("formats cents as euros in English", () => {
    expect(formatCents(4320, "en")).toBe("€43.20");
  });
});
