import test from "node:test";
import assert from "node:assert/strict";
import { clarifiedInputText } from "../inputText.ts";

test("accepts clarified text up to exactly 500 characters", () => {
  const result = clarifiedInputText("a".repeat(479), "b".repeat(13));
  assert.equal(result.length, 500);
});

test("rejects clarified text longer than 500 characters", () => {
  assert.throws(() => clarifiedInputText("a".repeat(480), "b".repeat(13)), RangeError);
});
