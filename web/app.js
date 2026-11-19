import { Embedder, FieldShard, loadManifest, search } from './search.js';

const PAGE = 30;
const $ = (id) => document.getElementById(id);

const state = {
  manifest: null,
  embedder: null,
  shards: new Map(),      // field id -> FieldShard
  selected: new Set(loadSelection()),
  busy: false,
  limit: PAGE,
  lastQuery: null,        // [text, vector]
};

function loadSelection() {
  try { return JSON.parse(localStorage.getItem('fields') || '[]'); } catch { return []; }
}
function saveSelection() {
  try { localStorage.setItem('fields', JSON.stringify([...state.selected])); } catch { /* private mode */ }
}

const fmt = new Intl.NumberFormat('en');
const mb = (bytes) => `${Math.max(1, Math.round(bytes / 1e6))} MB`;

// ---------- status / messages ----------

function setStatus(text, progress = null) {
  $('status').hidden = !text;
  $('status-text').textContent = text || '';
  $('progress').hidden = progress === null;
  $('spinner').hidden = progress !== null;
  if (progress !== null) $('progress-bar').style.width = `${Math.round(progress * 100)}%`;
}

function setError(text) {
  $('error').hidden = !text;
  $('error').textContent = text || '';
}

function setBusy(busy) {
  state.busy = busy;
  $('field-button').disabled = busy;
  $('city').disabled = busy || state.shards.size === 0;
  updateFindButton();
}

function updateFindButton() {
  $('find').disabled = state.busy || !state.embedder || state.shards.size === 0 || !$('query').value.trim();
}

function updateMessage() {
  const m = $('message');
  if ($('results').children.length) { m.textContent = ''; return; }
  if (state.selected.size === 0) {
    m.textContent = 'Pick your field(s) of work to get started. Each field is downloaded once and kept in this browser.';
  } else if (state.shards.size) {
    const n = [...state.shards.values()].reduce((s, f) => s + f.size, 0);
    m.textContent = `${fmt.format(n)} papers with Belgian authors since ${state.manifest.since.slice(0, 4)} · ` +
      `updated ${state.manifest.updated.slice(0, 10)}. Describe your project and press Find papers.`;
  } else {
    m.textContent = '';
  }
}

// ---------- field menu ----------

function renderFieldMenu() {
  const menu = $('field-menu');
  menu.replaceChildren();
  const fields = [...state.manifest.fields].sort((a, b) => a.name.localeCompare(b.name));
  for (const f of fields) {
    const label = document.createElement('label');
    label.className = 'menu-item';
    const box = document.createElement('input');
    box.type = 'checkbox';
    box.checked = state.selected.has(f.id);
    box.addEventListener('change', () => toggleField(f.id, box.checked));
    const text = document.createElement('span');
    text.innerHTML = '<strong></strong><small></small>';
    text.querySelector('strong').textContent = f.name;
    text.querySelector('small').textContent = `${fmt.format(f.count)} papers · ${mb(f.bytes)}`;
    label.append(box, text);
    menu.append(label);
  }
  const names = fields.filter((f) => state.selected.has(f.id)).map((f) => f.name);
  $('field-label').textContent = names.length === 0 ? 'Choose…' : names.length === 1 ? names[0] : `${names.length} fields`;
}

function openMenu(open) {
  $('field-menu').hidden = !open;
  $('field-button').setAttribute('aria-expanded', String(open));
}

$('field-button').addEventListener('click', (e) => {
  e.stopPropagation();
  openMenu($('field-menu').hidden);
});
document.addEventListener('click', (e) => {
  if (!$('field-filter').contains(e.target)) openMenu(false);
});
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') openMenu(false); });

async function toggleField(id, on) {
  if (on) state.selected.add(id); else state.selected.delete(id);
  saveSelection();
  renderFieldMenu();
  if (on) {
    openMenu(false);
    await loadField(id);
  } else {
    state.shards.delete(id);
  }
  updateCities();
  updateMessage();
  if ($('results').children.length) runSearch(false);
}

async function loadField(id) {
  const info = state.manifest.fields.find((f) => f.id === id);
  if (!info || state.shards.has(id)) return;
  setBusy(true);
  setError('');
  try {
    setStatus(`Downloading ${info.name} (${fmt.format(info.count)} papers, ${mb(info.bytes)})…`, 0);
    const shard = await FieldShard.load(info, state.manifest.dim, (done, total) =>
      setStatus(`Downloading ${info.name} (${fmt.format(info.count)} papers, ${mb(info.bytes)})…`,
        Math.min(1, done / total)));
    state.shards.set(id, shard);
  } catch (err) {
    state.selected.delete(id);
    saveSelection();
    renderFieldMenu();
    setError(`Couldn't load ${info.name}: ${err.message}`);
  } finally {
    setStatus('');
    setBusy(false);
  }
}

// ---------- city filter ----------

function updateCities() {
  const counts = new Map();
  for (const shard of state.shards.values()) {
    for (const cities of shard.cities) for (const c of cities) if (c !== 'Unknown') counts.set(c, (counts.get(c) || 0) + 1);
  }
  const select = $('city');
  const current = select.value;
  select.replaceChildren(new Option('All of Belgium', ''));
  [...counts.entries()].sort((a, b) => b[1] - a[1])
    .forEach(([c, n]) => select.append(new Option(`${c} (${fmt.format(n)})`, c)));
  select.value = counts.has(current) ? current : '';
  select.disabled = state.busy || state.shards.size === 0;
}

$('city').addEventListener('change', () => { if ($('results').children.length) runSearch(false); });

// ---------- search ----------

async function runSearch(more) {
  const text = $('query').value.trim();
  if (!text || !state.embedder || state.shards.size === 0) return;
  state.limit = more ? state.limit + PAGE : PAGE;
  const btn = $('load-more');
  if (more) { btn.disabled = true; btn.textContent = 'Loading…'; }
  else setStatus('Searching…');
  try {
    if (!state.lastQuery || state.lastQuery[0] !== text) {
      state.lastQuery = [text, await state.embedder.embed(text)];
    }
    // Let the browser paint the spinner before the (blocking) scoring loop.
    await new Promise((r) => setTimeout(r, 20));
    const hits = search([...state.shards.values()], state.lastQuery[1], $('city').value || null, state.limit);
    renderResults(hits);
    btn.hidden = hits.length < state.limit;
  } catch (err) {
    setError(`Search failed: ${err.message}`);
  } finally {
    setStatus('');
    btn.disabled = false;
    btn.textContent = 'Load more';
    updateMessage();
  }
}

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

function renderResults(hits) {
  const list = $('results');
  list.replaceChildren();
  for (const h of hits) {
    const p = h.shard.paper(h.row);
    const li = el('li', 'card');
    const meta = el('div', 'meta');
    meta.append(el('span', 'score', `${Math.round(h.score * 100)}% match`),
      el('span', 'subfield', p.subfield || h.shard.info.name),
      el('span', 'year', p.year ? String(p.year) : ''));
    const a = el('a', 'title', p.title);
    a.href = p.url;
    a.target = '_blank';
    a.rel = 'noopener';
    li.append(meta, a);
    const local = [];
    for (const au of p.localAuthors) if (!local.some((x) => x.name === au.name)) local.push(au);
    for (const au of local.slice(0, 4)) li.append(el('div', 'author', `${au.name} · ${au.institution}, ${au.city}`));
    const others = p.authorCount - Math.min(local.length, 4);
    if (others > 0) li.append(el('div', 'others', `+ ${others} co-author${others > 1 ? 's' : ''}`));
    if (p.abstract) li.append(el('p', 'abstract', p.abstract));
    list.append(li);
  }
}

$('search-form').addEventListener('submit', (e) => { e.preventDefault(); runSearch(false); });
$('query').addEventListener('input', updateFindButton);
$('query').addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); if (!$('find').disabled) runSearch(false); }
});
$('load-more').addEventListener('click', () => runSearch(true));

// ---------- start ----------

async function start() {
  setBusy(true);
  try {
    setStatus('Loading the list of fields…');
    state.manifest = await loadManifest();
    const known = new Set(state.manifest.fields.map((f) => f.id));
    state.selected = new Set([...state.selected].filter((id) => known.has(id)));
    renderFieldMenu();

    setStatus('Loading the language model (~23 MB, once)…', 0);
    state.embedder = await Embedder.create((done, total) =>
      setStatus('Loading the language model (~23 MB, once)…', total ? Math.min(1, done / total) : null),
      encodeURIComponent(state.manifest.model));
  } catch (err) {
    setError(err.message);
    setStatus('');
    setBusy(false);
    return;
  }
  setStatus('');
  setBusy(false);
  for (const id of state.selected) await loadField(id);
  updateCities();
  updateMessage();
  updateFindButton();
}

start();
