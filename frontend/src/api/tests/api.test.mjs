import test from "node:test";
import assert from "node:assert/strict";
import { extractInput, retryInputWithClarification } from "../inputs.ts";

function installFetch(t, onRequest) {
  const original = globalThis.fetch;
  globalThis.fetch = async (url, init) => {
    onRequest(String(url), init);
    // 요청 계층이 본문을 text()로 읽으므로 실제 Response를 돌려준다
    return new Response(JSON.stringify({ id: "input-1" }), { status: 200, headers: { "Content-Type": "application/json" } });
  };
  t.after(() => { globalThis.fetch = original; });
}

test("API wrapper attaches the client header and encodes path IDs", async (t) => {
  Object.defineProperty(globalThis, "localStorage", { configurable: true, value: { getItem: () => null, setItem: () => {} } });
  let captured;
  installFetch(t, (url, init) => { captured = { url, init }; });
  await extractInput("decision / 1", { context: "reason", text: "조건" });
  assert.equal(captured.url, "/api/v1/decisions/decision%20%2F%201/inputs");
  assert.equal(captured.init.method, "POST");
  assert.match(captured.init.headers.get("X-Client-Id"), /^[0-9a-f-]{36}$/i);
  assert.deepEqual(JSON.parse(captured.init.body), { context: "reason", text: "조건" });
});

test("clarification over 500 chars fails before making either API call", async (t) => {
  let calls = 0;
  installFetch(t, () => { calls += 1; });
  await assert.rejects(
    retryInputWithClarification("d1", "i1", { context: "reason", text: "a".repeat(480) }, "b".repeat(13)),
    RangeError,
  );
  assert.equal(calls, 0);
});

test("decision, question, and analysis wrappers use the documented routes", async (t) => {
  const calls = [];
  installFetch(t, (url, init) => { calls.push({ url, method: init.method ?? "GET", body: init.body }); });
  const { listDecisions } = await import("../decisions.ts");
  const { submitAnswer } = await import("../questions.ts");
  const { simulateWeights } = await import("../analysis.ts");

  await listDecisions({ status: "ANALYZED", cursor: "a b", limit: 10 });
  await submitAnswer("d1", { questionId: "q1", response: { choice: "A" } });
  await simulateWeights("d1", { weights: [{ criterionId: "c1", weight: 1 }] });

  assert.equal(calls[0].url, "/api/v1/decisions?status=ANALYZED&cursor=a+b&limit=10");
  assert.equal(calls[0].method, "GET");
  assert.equal(calls[1].url, "/api/v1/decisions/d1/answers");
  assert.equal(calls[1].method, "POST");
  assert.deepEqual(JSON.parse(calls[1].body), { questionId: "q1", response: { choice: "A" } });
  assert.equal(calls[2].url, "/api/v1/decisions/d1/simulations");
  assert.equal(calls[2].method, "POST");
});
