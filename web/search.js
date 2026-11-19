// Loading the index and searching it: the browser version of PaperIndex.kt.
// Everything runs locally; the description you type is never sent anywhere.

import { WordPieceTokenizer } from './tokenizer.js';

const DATA = './data/';
const CACHE = 'belgian-papers-data-v1';

/** Downloads a data file, keeping a copy in Cache Storage keyed by its checksum. */
async function fetchData(name, version, onProgress) {
  const key = new URL(`${DATA}${name}?v=${version}`, location.href).toString();
  let cache = null;
  try {
    cache = await caches.open(CACHE);
    const hit = await cache.match(key);
    if (hit) return await hit.arrayBuffer();
  } catch { cache = null; }

  const res = await fetch(`${DATA}${name}`, { cache: 'no-cache' });
  if (!res.ok) throw new Error(`Could not download ${name} (HTTP ${res.status})`);
  const total = Number(res.headers.get('content-length')) || 0;
  const reader = res.body.getReader();
  const chunks = [];
  let done = 0;
  for (;;) {
    const { done: end, value } = await reader.read();
    if (end) break;
    chunks.push(value);
    done += value.length;
    onProgress?.(done, total);
  }
  const buf = new Uint8Array(done);
  let pos = 0;
  for (const c of chunks) { buf.set(c, pos); pos += c.length; }

  if (cache) {
    try {
      // Drop older versions of the same file, then store this one.
      for (const req of await cache.keys()) {
        const u = new URL(req.url);
        if (u.pathname.endsWith(`/${name}`) && req.url !== key) await cache.delete(req);
      }
      await cache.put(key, new Response(buf));
    } catch { /* storage full or blocked: fine, just not cached */ }
  }
  return buf.buffer;
}

export async function loadManifest() {
  const res = await fetch(`${DATA}manifest.json`, { cache: 'no-cache' });
  if (!res.ok) throw new Error(`The paper index isn't published yet (HTTP ${res.status}).`);
  return res.json();
}

export class Embedder {
  static async create(onProgress, version = 'v1') {
    const ort = window.ort;
    ort.env.wasm.numThreads = 1;  // GitHub Pages is not cross-origin isolated
    const [model, vocab] = await Promise.all([
      fetchData('model.onnx', version, onProgress),
      fetchData('vocab.txt', version),
    ]);
    const session = await ort.InferenceSession.create(model, { executionProviders: ['wasm'] });
    const e = new Embedder();
    e.ort = ort;
    e.session = session;
    e.tokenizer = new WordPieceTokenizer(new TextDecoder().decode(vocab));
    return e;
  }

  /** Mean pooling over tokens, then L2-normalise: same as the app and pipeline. */
  async embed(text) {
    const ids = this.tokenizer.encode(text);
    const n = ids.length;
    const T = (arr) => new this.ort.Tensor('int64', BigInt64Array.from(arr, BigInt), [1, n]);
    const feeds = { input_ids: T(ids), attention_mask: T(ids.map(() => 1)) };
    if (this.session.inputNames.includes('token_type_ids')) feeds.token_type_ids = T(ids.map(() => 0));
    const out = await this.session.run(feeds);
    const hidden = out[this.session.outputNames[0]];
    const dim = hidden.dims[2];
    const data = hidden.data;
    const v = new Float32Array(dim);
    for (let t = 0; t < n; t++) for (let i = 0; i < dim; i++) v[i] += data[t * dim + i];
    let norm = 0;
    for (let i = 0; i < dim; i++) { v[i] /= n; norm += v[i] * v[i]; }
    const len = Math.max(Math.sqrt(norm), 1e-12);
    for (let i = 0; i < dim; i++) v[i] /= len;
    return v;
  }
}

/** One research field: raw JSON lines (parsed only when shown), vectors and cities. */
export class FieldShard {
  static async load(info, dim, onProgress) {
    const gzBytes = info.bytes - info.count * dim;
    const papersBuf = await fetchData(info.papers, info.sha256, (d) => onProgress?.(d, info.bytes));
    const vectorsBuf = await fetchData(info.vectors, info.sha256, (d) => onProgress?.(gzBytes + d, info.bytes));
    const stream = new Blob([papersBuf]).stream().pipeThrough(new DecompressionStream('gzip'));
    const text = await new Response(stream).text();
    const lines = text.split('\n');
    if (lines[lines.length - 1] === '') lines.pop();

    const s = new FieldShard();
    s.info = info;
    s.dim = dim;
    s.lines = lines;
    s.vectors = new Int8Array(vectorsBuf);
    s.cities = lines.map((line) => [...new Set(JSON.parse(line).l.map((a) => a[2]))]);
    if (s.vectors.length !== lines.length * dim) throw new Error(`${info.name}: index files don't match`);
    return s;
  }

  get size() { return this.lines.length; }

  paper(row) {
    const o = JSON.parse(this.lines[row]);
    return {
      id: o.id, title: o.t, year: o.y || 0, url: o.u, subfield: o.s || '',
      authors: o.a, authorCount: o.n ?? o.a.length,
      localAuthors: o.l.map(([name, institution, city]) => ({ name, institution, city })),
      abstract: o.ab || '',
    };
  }
}

/** Top `limit` papers over all shards, optionally only with an author in `city`. */
export function search(shards, query, city, limit) {
  const top = [];  // sorted best-first, at most `limit` long
  for (const shard of shards) {
    const { vectors, dim, cities } = shard;
    for (let row = 0; row < shard.size; row++) {
      if (city && !cities[row].includes(city)) continue;
      let dot = 0;
      const off = row * dim;
      for (let i = 0; i < dim; i++) dot += query[i] * vectors[off + i];
      const score = dot / 127;
      if (top.length === limit && score <= top[limit - 1].score) continue;
      let i = top.length;
      while (i > 0 && top[i - 1].score < score) i--;
      top.splice(i, 0, { shard, row, score });
      if (top.length > limit) top.pop();
    }
  }
  return top;
}
