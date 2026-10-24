import test from "node:test";
import assert from "node:assert/strict";
import { getClientId } from "../clientId.ts";

test("uses an in-memory client ID when localStorage is blocked", () => {
  Object.defineProperty(globalThis, "localStorage", {
    configurable: true,
    value: {
      getItem() { throw new Error("storage disabled"); },
      setItem() { throw new Error("storage disabled"); },
    },
  });
  const first = getClientId();
  assert.match(first, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
  assert.equal(getClientId(), first);
});
