// The sign-in form (app.js, loginView). The page is public: an account named in it is one anyone
// can read, and guess passwords for.
import test from "node:test";
import assert from "node:assert/strict";
import { openApp, store } from "./app.mjs";
import { fresh } from "./dom.mjs";

const OWNER = "xrnloops.pendingOwner";

test.beforeEach(() => store.clear());

test("the sign-in form names no account in a browser that never signed in", async () => {
  const { app } = await openApp([], []);
  store.delete(OWNER);
  const find = fresh();
  app.loginView();
  assert.equal(find("input", (n) => n.name === "user").value, "");
  assert.equal(find("input", (n) => n.name === "user").autofocus, "");
});

test("a browser that signed in before has its account filled in", async () => {
  const { app } = await openApp([], []);
  store.set(OWNER, JSON.stringify("someone"));
  const find = fresh();
  app.loginView();
  assert.equal(find("input", (n) => n.name === "user").value, "someone");
  assert.equal(find("input", (n) => n.name === "password").autofocus, "");
});
