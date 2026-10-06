'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, '../../main/assets/wifi/import.html'), 'utf8');
const script = html.match(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/i);
assert.ok(script, 'import.html must contain its upload script');

function element() {
  const listeners = new Map();
  const classes = new Set();
  return {
    children: [],
    style: {},
    className: '',
    textContent: '',
    classList: {
      add(name) { classes.add(name); },
      remove(name) { classes.delete(name); },
      contains(name) { return classes.has(name); },
    },
    appendChild(child) { this.children.push(child); },
    addEventListener(name, callback) { listeners.set(name, callback); },
    dispatch(name, event = {}) {
      assert.ok(listeners.has(name), `missing ${name} listener`);
      listeners.get(name)(event);
    },
    querySelector() { return null; },
  };
}

function harness() {
  const ids = new Map(['drop', 'fileInput', 'pick', 'recList', 'recWrap', 'dictList', 'dictWrap']
    .map(id => [id, element()]));
  const requests = [];
  const finishes = [];
  const document = {
    getElementById(id) { return ids.get(id); },
    createElement() { return element(); },
    addEventListener() {},
  };
  class XMLHttpRequest {
    constructor() { this.upload = {}; }
    open(method, url) { this.method = method; this.url = url; }
    setRequestHeader() {}
    send(file) { this.file = file; requests.push(this); }
    complete(status = 200, response = '{}') {
      this.status = status;
      this.responseText = response;
      this.onload();
    }
  }
  const fetch = (url, options) => {
    finishes.push({ url, options });
    return Promise.resolve({ json: () => Promise.resolve({ imported: [], incomplete: [] }) });
  };
  vm.runInNewContext(script[1], { document, XMLHttpRequest, fetch, Promise, encodeURIComponent });
  function select(names) {
    const input = ids.get('fileInput');
    input.files = names.map(name => ({ name, size: 8 }));
    input.dispatch('change');
  }
  function paths() { return requests.map(req => decodeURIComponent(req.url.split('name=')[1])); }
  function complete(name) {
    const req = requests.find(r => r.file.name === name);
    assert.ok(req, `${name} was not uploaded`);
    assert.equal(req.method, 'PUT');
    req.complete();
  }
  return { ids, requests, finishes, select, paths, complete };
}

// The production queue advances through Promise continuations after each XHR callback.
const advance = () => new Promise(resolve => setImmediate(resolve));

function assertFinishOnce(h) {
  assert.equal(h.finishes.length, 1);
  assert.equal(h.finishes[0].url, '/finish');
  assert.equal(h.finishes[0].options.method, 'POST');
}

test('MDX and MDD are accepted; every MDD completes before MDX starts', async () => {
  const h = harness();
  h.select(['alpha.mdx', 'alpha.mdd', 'alpha.1.MDD', 'other.mdd']);
  assert.deepEqual(h.paths().sort(), ['alpha.1.MDD', 'alpha.mdd', 'other.mdd']);
  assert.equal(h.ids.get('recList').children.length, 4);
  h.complete('alpha.mdd');
  await advance();
  assert.equal(h.requests.length, 3, 'MDX must wait for all MDD uploads');
  assert.equal(h.finishes.length, 0);
  h.complete('alpha.1.MDD');
  await advance();
  assert.equal(h.requests.length, 3);
  h.complete('other.mdd');
  await advance();
  assert.deepEqual(h.paths().sort(), ['alpha.1.MDD', 'alpha.mdd', 'alpha.mdx', 'other.mdd']);
  assert.equal(h.finishes.length, 0);
  h.complete('alpha.mdx');
  await advance();
  assertFinishOnce(h);
  assert.equal(h.ids.get('recList').children.length, 4);
});

test('StarDict dict body starts only after its metadata and index complete', async () => {
  const h = harness();
  h.select(['book.dict', 'book.ifo', 'book.idx']);
  assert.deepEqual(h.paths(), ['book.ifo', 'book.idx']);
  h.complete('book.ifo');
  await advance();
  assert.equal(h.requests.length, 2);
  h.complete('book.idx');
  await advance();
  assert.deepEqual(h.paths(), ['book.ifo', 'book.idx', 'book.dict']);
  assert.equal(h.finishes.length, 0);
  h.complete('book.dict');
  await advance();
  assertFinishOnce(h);
});

test('multiple dictionary bodies upload concurrently and finish once after all complete', async () => {
  const h = harness();
  h.select(['one.mdx', 'two.mdx', 'three.dict', 'four.dict.dz']);
  await advance();
  assert.deepEqual(h.paths(), ['one.mdx', 'two.mdx', 'three.dict']);
  assert.equal(h.finishes.length, 0);
  h.complete('two.mdx');
  await advance();
  assert.deepEqual(h.paths(), ['one.mdx', 'two.mdx', 'three.dict', 'four.dict.dz']);
  h.complete('one.mdx');
  h.complete('three.dict');
  await advance();
  assert.equal(h.finishes.length, 0);
  h.complete('four.dict.dz');
  await advance();
  assertFinishOnce(h);
});
