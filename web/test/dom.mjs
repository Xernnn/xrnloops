// Enough of the DOM for app.js to build a view under node and for a test to find, type into and
// press what it built. Import it before calling into the app; app.mjs's stand-ins stay for the rest.
import "./app.mjs";

export const made = [];

export class FakeNode extends EventTarget {
  constructor(tag) {
    super();
    this.tagName = tag;
    this.children = [];
    this.dataset = {};
    this.style = { setProperty() {} };
    this.text = "";
    made.push(this);
  }
  get value() { return this.text; }
  set value(v) { this.text = String(v); }
  setAttribute(k, v) { this[k] = v; }
  append(...children) { this.children.push(...children); }
  remove() {}
  focus() {}
  select() {}
  querySelector() { return null; }
}

globalThis.Node = FakeNode;
globalThis.SVGElement = class {};
document.createElement = (tag) => new FakeNode(tag);
document.createElementNS = (_, tag) => new FakeNode(tag);
document.body = new FakeNode("body");
document.removeEventListener = () => {};

/** The elements built since the last call, as `find(tag, test)` looks them up. */
export function fresh() {
  made.length = 0;
  return (tag, test = () => true) => made.find((n) => n.tagName === tag && test(n));
}
