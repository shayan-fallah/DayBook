'use strict';

// ---------------------------------------------------------------- helpers

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

/** Build a DOM element. Children may be strings, nodes, arrays, or null. */
function el(tag, attrs = {}, ...kids) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k === 'class') node.className = v;
    else if (k === 'style') node.style.cssText = v;
    else if (k.startsWith('on')) node.addEventListener(k.slice(2), v);
    else if (k in node && k !== 'list') node[k] = v;
    else node.setAttribute(k, v === true ? '' : v);
  }
  for (const kid of kids.flat()) {
    if (kid == null || kid === false) continue;
    node.append(kid instanceof Node ? kid : document.createTextNode(String(kid)));
  }
  return node;
}

const SVG_NS = 'http://www.w3.org/2000/svg';
function svg(tag, attrs = {}, ...kids) {
  const node = document.createElementNS(SVG_NS, tag);
  for (const [k, v] of Object.entries(attrs)) if (v != null) node.setAttribute(k, v);
  for (const kid of kids.flat()) if (kid != null) node.append(kid instanceof Node ? kid : document.createTextNode(String(kid)));
  return node;
}

const fmt = (n) => (n == null || isNaN(n) ? '–' : Math.round(n).toLocaleString('en-US'));
const fmt1 = (n) => (n == null || isNaN(n) ? '–' : (Math.round(n * 10) / 10).toLocaleString('en-US'));
const pad = (n) => String(n).padStart(2, '0');
const isoOf = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const dateOf = (s) => { const [y, m, d] = s.split('-').map(Number); return new Date(y, m - 1, d); };
const todayISO = () => isoOf(new Date());
const addDays = (s, n) => { const d = dateOf(s); d.setDate(d.getDate() + n); return isoOf(d); };
const daysBetween = (a, b) => Math.round((dateOf(b) - dateOf(a)) / 86400000);
const shortDate = (s) => dateOf(s).toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
const longDate = (s) => dateOf(s).toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' });

/** Parse user-typed numbers, including Persian/Arabic digits, "," decimals and fractions like 1/2. */
function toNum(value) {
  let s = String(value ?? '').trim()
    .replace(/[۰-۹]/g, (d) => '۰۱۲۳۴۵۶۷۸۹'.indexOf(d))
    .replace(/[٠-٩]/g, (d) => '٠١٢٣٤٥٦٧٨٩'.indexOf(d))
    .replace(/[٫,]/g, '.')
    .replace(/\s+/g, '');
  if (s === '') return NaN;
  const frac = s.match(/^(\d+(?:\.\d+)?)\/(\d+(?:\.\d+)?)$/);
  if (frac) return Number(frac[1]) / Number(frac[2]);
  return Number(s);
}

function debounce(fn, ms) {
  let t;
  return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); };
}

let toastTimer;
function toast(msg, isError = false) {
  const t = $('#toast');
  // A modal dialog sits in the top layer; move the toast inside it so it stays visible.
  const open = $('dialog[open]');
  (open || document.body).append(t);
  t.textContent = msg;
  t.classList.toggle('error', isError);
  t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('show'), isError ? 4000 : 2200);
}

async function api(method, path, body) {
  const opts = { method, headers: { 'X-Requested-With': 'fetch' } };
  if (body !== undefined) {
    opts.headers['Content-Type'] = 'application/json';
    opts.body = JSON.stringify(body);
  }
  let res;
  try {
    res = await fetch(path, opts);
  } catch {
    throw new Error('Cannot reach the server. Check your connection.');
  }
  let data = null;
  try { data = await res.json(); } catch { /* empty body */ }
  if (res.status === 401 && !path.startsWith('/api/login')) {
    showAuth();
    throw new Error((data && data.error) || 'Please log in');
  }
  if (!res.ok) throw new Error((data && data.error) || `Request failed (${res.status})`);
  return data;
}

/** Run an async action and show any error in a toast (or a given element). */
async function run(fn, errorEl) {
  if (errorEl) errorEl.textContent = '';
  try {
    return await fn();
  } catch (err) {
    if (errorEl) errorEl.textContent = err.message;
    else toast(err.message, true);
    return undefined;
  }
}

const MEALS = [['breakfast', 'Breakfast'], ['lunch', 'Lunch'], ['dinner', 'Dinner'], ['snack', 'Snacks']];
const MEAL_NAME = Object.fromEntries(MEALS);
const MACROS = [['protein', 'Protein', 4], ['carbs', 'Carbs', 4], ['fat', 'Fat', 9]];
const GRAM = 'گرم';

function mealForNow() {
  const h = new Date().getHours() + new Date().getMinutes() / 60;
  if (h < 10.5) return 'breakfast';
  if (h < 15) return 'lunch';
  if (h < 18) return 'snack';
  if (h < 22.5) return 'dinner';
  return 'snack';
}

const state = {
  user: null,
  date: todayISO(),
  day: null,
  range: 30,
  stats: null,
  quick: null,
  tab: 'recent',
  add: null, // { mode: 'add'|'edit', entry, food }
};

// ---------------------------------------------------------------- auth

let authMode = 'login';
let authInfo = null;

async function boot() {
  let me;
  try {
    me = await api('GET', '/api/me');
  } catch (err) {
    document.body.textContent = err.message;
    return;
  }
  authInfo = me;
  if (!me.user) return showAuth();
  state.user = me.user;
  showApp();
}

function showAuth() {
  $$('dialog[open]').forEach((d) => d.close());
  $('#app').hidden = true;
  $('#auth').hidden = false;
  setAuthMode(authInfo && authInfo.first_user ? 'signup' : 'login');
}

function setAuthMode(mode) {
  authMode = mode;
  const first = authInfo && authInfo.first_user;
  $('#auth-title').textContent = mode === 'signup' ? (first ? 'Create your account' : 'Create an account') : 'Log in';
  $('#auth-hint').textContent = first ? 'This is the first account on this server.' : '';
  $('#auth-submit').textContent = mode === 'signup' ? 'Create account' : 'Log in';
  $('#auth-form').password.autocomplete = mode === 'signup' ? 'new-password' : 'current-password';
  const toggle = $('#auth-toggle');
  toggle.hidden = first || !(authInfo && authInfo.signup_allowed);
  toggle.textContent = mode === 'signup' ? 'I already have an account' : 'Create an account';
  $('#auth-error').textContent = '';
}

$('#auth-toggle').addEventListener('click', () => setAuthMode(authMode === 'login' ? 'signup' : 'login'));
$('#auth-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = e.target;
  const ok = await run(() => api('POST', authMode === 'signup' ? '/api/signup' : '/api/login',
    { username: f.username.value.trim(), password: f.password.value }), $('#auth-error'));
  if (!ok) return;
  f.password.value = '';
  authInfo = await api('GET', '/api/me');
  state.user = authInfo.user;
  showApp();
});

$('#logout').addEventListener('click', async () => {
  await run(() => api('POST', '/api/logout'));
  authInfo = await api('GET', '/api/me');
  showAuth();
});

function showApp() {
  $('#auth').hidden = true;
  $('#app').hidden = false;
  $('#me-name').textContent = state.user;
  route();
}

// ---------------------------------------------------------------- routing

const VIEWS = ['today', 'history', 'foods', 'settings'];

function route() {
  if ($('#app').hidden) return;
  const view = VIEWS.includes(location.hash.slice(1)) ? location.hash.slice(1) : 'today';
  for (const v of VIEWS) $(`#view-${v}`).hidden = v !== view;
  $$('.tabbar a').forEach((a) => a.classList.toggle('on', a.dataset.view === view));
  window.scrollTo(0, 0);
  if (view === 'today') loadDay();
  if (view === 'history') loadHistory();
  if (view === 'foods') loadFoods();
  if (view === 'settings') loadProfile();
}
window.addEventListener('hashchange', route);

// Coming back to the tab on a new day should show the new day.
let lastSeenToday = todayISO();
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState !== 'visible' || $('#app').hidden) return;
  const now = todayISO();
  if (now !== lastSeenToday) {
    if (state.date === lastSeenToday) state.date = now;
    lastSeenToday = now;
  }
  if (!$('#view-today').hidden && !$('dialog[open]')) loadDay();
});

// ---------------------------------------------------------------- today

async function loadDay() {
  const date = state.date;
  const day = await run(() => api('GET', `/api/day?date=${date}`));
  if (!day || date !== state.date) return;
  state.day = day;
  renderDay();
}

function setDate(d) {
  state.date = d;
  loadDay();
}

$('#prev-day').addEventListener('click', () => setDate(addDays(state.date, -1)));
$('#next-day').addEventListener('click', () => setDate(addDays(state.date, 1)));
$('#date-input').addEventListener('change', (e) => { if (e.target.value) setDate(e.target.value); });

function dateLabel(d) {
  const diff = daysBetween(todayISO(), d);
  const rel = { 0: 'Today', '-1': 'Yesterday', 1: 'Tomorrow' }[diff];
  return rel ? `${rel}, ${shortDate(d)}` : longDate(d);
}

function renderDay() {
  const d = state.day;
  const t = d.targets;
  const tot = d.totals;
  $('#date-label').textContent = dateLabel(d.date);
  $('#date-input').value = d.date;

  // Calorie summary
  const budget = t.kcal + (d.exercise || 0);
  const left = budget - tot.kcal;
  const over = left < 0;
  $('.hero').classList.toggle('over', over);
  $('#kcal-left').textContent = fmt(Math.abs(left));
  $('#kcal-left-label').textContent = over ? 'kcal over goal ⚠' : 'kcal left';
  const fill = $('#kcal-fill');
  fill.style.width = `${Math.min(100, budget ? (tot.kcal / budget) * 100 : 0)}%`;
  fill.classList.toggle('over', over);
  $('#s-goal').textContent = fmt(t.kcal);
  $('#s-food').textContent = fmt(tot.kcal);
  $('#s-ex').textContent = fmt(d.exercise || 0);

  $('#macros').replaceChildren(...MACROS.map(([k, label]) => {
    const pct = t[k] ? Math.min(100, (tot[k] / t[k]) * 100) : 0;
    return el('div', { class: 'macro' },
      el('div', { class: 'macro-label' }, el('i', { class: 'swatch', style: `background:var(--${k})` }), label),
      el('div', { class: 'macro-val' }, fmt(tot[k]), el('span', {}, ` / ${fmt(t[k])} g`)),
      el('div', { class: 'meter' }, el('div', { class: 'fill', style: `width:${pct}%;background:var(--${k})` })));
  }));

  const mini = (label, val, goal, unit, isLimit) => el('span', { class: isLimit && val > goal ? 'over' : '' },
    `${label} `, el('b', {}, unit === 'mg' ? fmt(val) : fmt1(val)), ` / ${isLimit ? '≤' : ''}${fmt(goal)} ${unit}`);
  $('#minis').replaceChildren(
    mini('Fiber', tot.fiber, t.fiber, 'g', false),
    mini('Sugar', tot.sugar, t.sugar, 'g', true),
    mini('Sodium', tot.sodium, t.sodium, 'mg', true));

  $('#setup-banner').hidden = !!t.suggested || t.manual;

  renderWater();
  const w = $('#weight-input');
  if (document.activeElement !== w) {
    w.value = d.weight ?? '';
    w.placeholder = d.last_weight ? String(d.last_weight) : '—';
  }
  const ex = $('#exercise-input');
  if (document.activeElement !== ex) ex.value = d.exercise || '';

  renderMeals();
}

const GLASS_SVG = '<svg viewBox="0 0 24 28" aria-hidden="true"><path d="M4 3h16l-2 22H6z" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"/><path class="water" d="M5.6 10h12.8l-1.4 14H7z" fill="currentColor"/></svg>';

function renderWater() {
  const d = state.day;
  const goal = d.targets.water;
  const n = d.water;
  $('#water-count').textContent = `${n} / ${goal} glasses · ${fmt1(n * 0.25)} L`;
  const count = Math.max(goal, n);
  const glasses = [];
  for (let i = 0; i < count; i++) {
    const b = el('button', {
      class: `glass${i < n ? ' full' : ''}`, type: 'button', 'aria-label': `${i + 1} glasses`,
      onclick: () => setWater(i + 1 === n ? i : i + 1),
    });
    b.innerHTML = GLASS_SVG;
    if (i >= n) b.querySelector('.water').setAttribute('fill', 'none');
    glasses.push(b);
  }
  $('#glasses').replaceChildren(...glasses);
}

async function setWater(n) {
  n = Math.max(0, Math.min(50, n));
  state.day.water = n;
  renderWater();
  await run(() => api('POST', '/api/days', { date: state.day.date, water: n }));
}
$('#water-plus').addEventListener('click', () => setWater(state.day.water + 1));
$('#water-minus').addEventListener('click', () => setWater(state.day.water - 1));

async function saveWeight() {
  const input = $('#weight-input');
  const raw = input.value.trim();
  const d = state.day.date;
  if (raw === '') {
    if (state.day.weight != null) {
      await run(() => api('DELETE', `/api/weights/${d}`));
      toast('Weight removed');
      loadDay();
    }
    return;
  }
  const kg = toNum(raw);
  if (!(kg >= 20 && kg <= 400)) { toast('Enter a weight between 20 and 400 kg', true); return; }
  if (kg === state.day.weight) return;
  const ok = await run(() => api('POST', '/api/weights', { date: d, kg }));
  if (ok) { toast(`Weight saved: ${fmt1(kg)} kg`); loadDay(); }
}

async function saveExercise() {
  const input = $('#exercise-input');
  const kcal = input.value.trim() === '' ? 0 : toNum(input.value);
  if (!(kcal >= 0 && kcal <= 10000)) { toast('Enter exercise calories between 0 and 10,000', true); return; }
  if (Math.round(kcal) === (state.day.exercise || 0)) return;
  const ok = await run(() => api('POST', '/api/days', { date: state.day.date, exercise: Math.round(kcal) }));
  if (ok) loadDay();
}

for (const [id, fn] of [['#weight-input', saveWeight], ['#exercise-input', saveExercise]]) {
  $(id).addEventListener('change', fn);
  $(id).addEventListener('keydown', (e) => { if (e.key === 'Enter') e.target.blur(); });
}

/** Unit names are Persian; isolate them so they don't scramble the English text around them. */
const bdi = (t) => el('bdi', {}, t);
const unitText = (name) => (name === GRAM ? 'g' : bdi(name));

function portionNodes(e) {
  if (e.food_id == null) return ['Quick add'];
  const out = [`${fmt1(e.qty)} `, unitText(e.unit)];
  if (e.unit !== GRAM && e.grams) out.push(` · ${fmt(e.grams)} g`);
  return out;
}

function renderMeals() {
  const entries = state.day.entries;
  const cards = MEALS.map(([key, label]) => {
    const list = entries.filter((e) => e.meal === key);
    const kcal = list.reduce((s, e) => s + e.kcal, 0);
    const head = el('div', { class: 'meal-head' },
      el('h3', {}, label),
      list.length ? el('span', { class: 'meal-kcal' }, `${fmt(kcal)} kcal`) : null,
      el('button', { class: 'primary', type: 'button', onclick: () => openAdd(key) }, '+ Add'));
    const body = list.length
      ? el('ul', { class: 'entries' }, list.map((e) => el('li', {},
        el('button', { class: 'entry', type: 'button', onclick: () => openEdit(e) },
          el('span', { class: 'entry-main' },
            el('span', { class: 'entry-name', dir: 'auto' }, e.name),
            el('span', { class: 'entry-sub', dir: 'ltr' },
              portionNodes(e), ` · P ${fmt(e.protein)} C ${fmt(e.carbs)} F ${fmt(e.fat)}`)),
          el('span', { class: 'entry-kcal' }, fmt(e.kcal))))))
      : el('div', { class: 'meal-empty' }, 'Nothing logged',
        el('button', { class: 'link', type: 'button', onclick: () => copyFrom(addDays(state.date, -1), key) },
          `Copy yesterday's ${label.toLowerCase()}`));
    return el('div', { class: 'card meal' }, head, body);
  });
  $('#meals').replaceChildren(...cards);
  $('#day-actions').replaceChildren(entries.length ? '' : el('button', {
    class: 'link', type: 'button', onclick: () => copyFrom(addDays(state.date, -1)),
  }, 'Copy everything from yesterday'));
}

async function copyFrom(from, meal) {
  const res = await run(() => api('POST', '/api/entries/copy', { from, to: state.date, meal }));
  if (!res) return;
  toast(res.copied ? `Copied ${res.copied} item${res.copied > 1 ? 's' : ''}` : 'Nothing was logged then');
  if (res.copied) loadDay();
}

// ---------------------------------------------------------------- add / edit dialog

const addDlg = $('#add-dlg');
const mealSelect = $('#add-meal');
mealSelect.replaceChildren(...MEALS.map(([k, l]) => el('option', { value: k }, l)));

function showPane(name) {
  $('#pick-pane').hidden = name !== 'pick';
  $('#detail-pane').hidden = name !== 'detail';
}

function openAdd(meal) {
  state.add = { mode: 'add' };
  mealSelect.value = meal || mealForNow();
  $('#add-title').textContent = 'Add food';
  $('#search').value = '';
  setTab(state.tab === 'quick' ? 'recent' : state.tab);
  showPane('pick');
  if (!addDlg.open) addDlg.showModal();
  loadQuick().then(() => { if (!$('#search').value.trim()) renderTab(); });
  // Don't pop the keyboard on phones; do focus on desktop.
  if (matchMedia('(pointer: fine)').matches) $('#search').focus();
}

function openEdit(entry) {
  state.add = { mode: 'edit', entry };
  mealSelect.value = entry.meal;
  $('#add-title').textContent = 'Edit';
  if (!addDlg.open) addDlg.showModal();
  if (entry.food_id == null) {
    showPane('pick');
    setTab('quick', entry);
  } else {
    openDetail(entry.food_id, entry);
  }
}

$('#add-close').addEventListener('click', () => addDlg.close());
addDlg.addEventListener('close', () => { state.add = null; });
addDlg.addEventListener('click', (e) => { if (e.target === addDlg) addDlg.close(); }); // backdrop tap
mealSelect.addEventListener('change', async () => {
  // While editing, changing the meal moves the entry right away.
  const a = state.add;
  if (a && a.mode === 'edit' && a.entry) {
    const ok = await run(() => api('PATCH', `/api/entries/${a.entry.id}`, { meal: mealSelect.value }));
    if (ok) { a.entry.meal = mealSelect.value; toast(`Moved to ${MEAL_NAME[mealSelect.value]}`); loadDay(); }
  }
});

async function loadQuick() {
  const q = await run(() => api('GET', '/api/foods/quick'));
  if (q) state.quick = q;
}

function setTab(tab, entry) {
  state.tab = tab;
  $$('#quick-tabs button').forEach((b) => b.classList.toggle('on', b.dataset.tab === tab && !$('#search').value.trim()));
  const quick = tab === 'quick';
  $('#quick-form').hidden = !quick;
  $('#results').hidden = quick;
  if (quick) fillQuickForm(entry);
  else renderTab();
}

$$('#quick-tabs button').forEach((b) => b.addEventListener('click', () => {
  $('#search').value = '';
  if (state.add && state.add.mode === 'edit' && b.dataset.tab !== 'quick') {
    state.add = { mode: 'add' }; // leave edit mode when browsing
    $('#add-title').textContent = 'Add food';
  }
  setTab(b.dataset.tab);
}));

function renderTab() {
  if (state.tab === 'quick' || $('#search').value.trim()) return;
  const q = state.quick;
  if (!q) { $('#results').replaceChildren(el('li', { class: 'list-empty' }, 'Loading…')); return; }
  const list = q[state.tab] || [];
  const empty = {
    recent: 'Foods you log will show up here for one-tap adding.',
    favorites: 'Tap ☆ on a food to add it to your favorites.',
    mine: 'Create your own foods on the Foods tab.',
  }[state.tab];
  renderResults(list, empty);
}

function foodSub(f) {
  const out = [`${fmt(f.kcal)} kcal / 100 g`];
  if (f.unit1 && f.unit1.name !== GRAM) out.push(' · 1 ', bdi(f.unit1.name), ` = ${fmt(f.unit1.grams)} g`);
  return out;
}

function renderResults(list, emptyText) {
  const ul = $('#results');
  if (!list.length) {
    ul.replaceChildren(el('li', { class: 'list-empty' }, emptyText));
    return;
  }
  ul.replaceChildren(...list.map((f) => el('li', {},
    el('button', { class: 'row-main', type: 'button', onclick: () => openDetail(f.id) },
      el('span', { class: 'row-name', dir: 'auto' }, f.fav ? '★ ' : '', f.name,
        f.custom ? el('span', { class: 'tag' }, 'mine') : null),
      el('span', { class: 'row-sub', dir: 'ltr' }, f.last
        ? [`${fmt1(f.last.qty)} `, unitText(f.last.unit), ` · ${fmt(f.last.kcal)} kcal`]
        : foodSub(f))),
    f.last ? el('button', {
      class: 'row-add', type: 'button', 'aria-label': `Add ${f.name} again`,
      onclick: (e) => quickReadd(f, e.currentTarget),
    }, '+ Add') : null)));
}

async function quickReadd(f, btn) {
  btn.disabled = true;
  const ok = await run(() => api('POST', '/api/entries', {
    date: state.date, meal: mealSelect.value, food_id: f.id, unit: f.last.unit, qty: f.last.qty,
  }));
  btn.disabled = false;
  if (ok) {
    toast(`Added to ${MEAL_NAME[mealSelect.value]}: ${f.name}`);
    loadDay();
  }
}

let searchSeq = 0;
const doSearch = debounce(async () => {
  const q = $('#search').value.trim();
  const seq = ++searchSeq;
  if (!q) { setTab(state.tab === 'quick' ? 'recent' : state.tab); return; }
  const res = await run(() => api('GET', `/api/foods/search?q=${encodeURIComponent(q)}`));
  if (!res || seq !== searchSeq || $('#search').value.trim() !== q) return;
  renderResults(res.results, 'No matches. Try fewer or different words, or use Quick calories.');
}, 180);

$('#search').addEventListener('input', () => {
  const has = !!$('#search').value.trim();
  if (has) {
    $('#quick-form').hidden = true;
    $('#results').hidden = false;
    $$('#quick-tabs button').forEach((b) => b.classList.remove('on'));
    if (state.tab === 'quick') state.tab = 'recent';
  }
  doSearch();
});
$('#search').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') { // open the top result
    e.preventDefault();
    const first = $('#results .row-main');
    if (first) first.click();
  }
});

// --- quick calories form

function fillQuickForm(entry) {
  const f = $('#quick-form');
  f.reset();
  $('#quick-error').textContent = '';
  if (entry) {
    f.name.value = entry.name;
    f.kcal.value = entry.kcal;
    for (const k of ['protein', 'carbs', 'fat']) f[k].value = entry[k] || '';
  }
  $('#quick-save').textContent = entry ? 'Save' : 'Add';
  $('#quick-delete').hidden = !entry;
}

$('#quick-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = e.target;
  const body = { name: f.name.value.trim() || 'Quick add' };
  for (const k of ['kcal', 'protein', 'carbs', 'fat']) {
    const v = f[k].value.trim() === '' ? 0 : toNum(f[k].value);
    if (!(v >= 0)) { $('#quick-error').textContent = `Check the ${k} value`; return; }
    body[k] = v;
  }
  const a = state.add;
  const editing = a && a.mode === 'edit' && a.entry && a.entry.food_id == null;
  const ok = await run(() => (editing
    ? api('PATCH', `/api/entries/${a.entry.id}`, { ...body, meal: mealSelect.value })
    : api('POST', '/api/entries', { ...body, date: state.date, meal: mealSelect.value })), $('#quick-error'));
  if (!ok) return;
  loadDay();
  if (editing) { addDlg.close(); toast('Saved'); return; }
  toast(`Added ${fmt(body.kcal || body.protein * 4 + body.carbs * 4 + body.fat * 9)} kcal`);
  fillQuickForm();
});

$('#quick-delete').addEventListener('click', () => deleteEntry());

async function deleteEntry() {
  const a = state.add;
  if (!a || !a.entry) return;
  const ok = await run(() => api('DELETE', `/api/entries/${a.entry.id}`));
  if (ok) { addDlg.close(); toast(`Deleted ${a.entry.name}`); loadDay(); }
}

// --- food detail

async function openDetail(foodId, entry) {
  const food = await run(() => api('GET', `/api/foods/${foodId}`));
  if (!food) return;
  if (!entry && state.add) state.add.mode = 'add';
  state.add = { ...(state.add || {}), food, entry: entry || null, mode: entry ? 'edit' : 'add' };
  $('#d-name').textContent = food.name;
  setFav(food.fav);
  $('#d-per100').textContent = `Per 100 g: ${fmt(food.kcal)} kcal · protein ${fmt1(food.protein)} g · carbs ${fmt1(food.carbs)} g · fat ${fmt1(food.fat)} g`;

  const units = food.units.slice();
  if (entry && !units.some((u) => u.name === entry.unit)) {
    units.unshift({ name: entry.unit, grams: entry.qty ? entry.grams / entry.qty : 1 });
  }
  state.add.units = units;
  $('#d-unit').replaceChildren(...units.map((u) => el('option', { value: u.name },
    u.name === GRAM ? 'g (grams)' : `\u2068${u.name}\u2069 · ${fmt1(u.grams)} g`)));

  let unit = units[0].name;
  let qty = null;
  const pref = entry || food.last;
  if (pref && units.some((u) => u.name === pref.unit)) { unit = pref.unit; qty = pref.qty; }
  $('#d-unit').value = unit;
  $('#d-qty').value = qty ?? (unit === GRAM ? 100 : 1);
  renderChips();
  updatePreview();

  $('#d-save').textContent = entry ? 'Save' : 'Add';
  $('#d-delete').hidden = !entry;
  $('#add-title').textContent = entry ? 'Edit' : 'Add food';
  $('#detail-back').hidden = !!entry;
  $('#detail-error').textContent = '';
  showPane('detail');
  if (matchMedia('(pointer: fine)').matches) { $('#d-qty').focus(); $('#d-qty').select(); }
}

function setFav(on) {
  const b = $('#d-fav');
  b.textContent = on ? '★' : '☆';
  b.setAttribute('aria-pressed', on ? 'true' : 'false');
  b.title = on ? 'Remove from favorites' : 'Add to favorites';
}

$('#d-fav').addEventListener('click', async () => {
  const food = state.add && state.add.food;
  if (!food) return;
  const res = await run(() => api(food.fav ? 'DELETE' : 'POST', `/api/favorites/${food.id}`));
  if (!res) return;
  food.fav = res.fav;
  setFav(food.fav);
  toast(food.fav ? 'Added to favorites' : 'Removed from favorites');
  loadQuick();
});

function currentUnit() {
  const name = $('#d-unit').value;
  return state.add.units.find((u) => u.name === name);
}

function renderChips() {
  const isGram = $('#d-unit').value === GRAM;
  const values = isGram ? [50, 100, 150, 200, 300] : [0.5, 1, 1.5, 2, 3];
  $('#d-chips').replaceChildren(...values.map((v) => el('button', {
    type: 'button', onclick: () => { $('#d-qty').value = v; updatePreview(); },
  }, isGram ? `${v} g` : v === 0.5 ? '½' : v === 1.5 ? '1½' : String(v))));
}

function updatePreview() {
  const a = state.add;
  const qty = toNum($('#d-qty').value);
  const unit = currentUnit();
  const valid = qty > 0 && unit;
  const grams = valid ? qty * unit.grams : 0;
  const f = grams / 100;
  const food = a.food;
  $('#p-kcal').textContent = valid ? fmt(food.kcal * f) : '–';
  $('#p-grams').textContent = valid && unit.name !== GRAM ? `· ${fmt1(grams)} g` : '';
  $('#p-macros').replaceChildren(
    ...MACROS.map(([k, label]) => el('span', {}, el('i', { class: 'swatch', style: `background:var(--${k})` }),
      `${label} `, el('b', {}, valid ? fmt1(food[k] * f) : '–'), ' g')),
    el('span', {}, 'Fiber ', el('b', {}, valid ? fmt1(food.fiber * f) : '–'), ' g'),
    el('span', {}, 'Sugar ', el('b', {}, valid ? fmt1(food.sugar * f) : '–'), ' g'));
  $('#d-save').disabled = !valid;
}

$('#d-qty').addEventListener('input', updatePreview);
$('#d-qty').addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); $('#d-save').click(); } });
let prevUnit = null;
$('#d-unit').addEventListener('focus', () => { prevUnit = currentUnit(); });
$('#d-unit').addEventListener('change', () => {
  // Keep the same amount when switching to or from grams.
  const now = currentUnit();
  const qty = toNum($('#d-qty').value);
  if (prevUnit && qty > 0) {
    if (now.name === GRAM) $('#d-qty').value = Math.round(qty * prevUnit.grams);
    else if (prevUnit.name === GRAM) $('#d-qty').value = 1;
  }
  prevUnit = now;
  renderChips();
  updatePreview();
});

$('#detail-back').addEventListener('click', () => {
  showPane('pick');
  if ($('#search').value.trim()) doSearch(); else renderTab();
  $('#search').focus();
});

$('#d-save').addEventListener('click', async () => {
  const a = state.add;
  const qty = toNum($('#d-qty').value);
  const unit = $('#d-unit').value;
  if (!(qty > 0)) { $('#detail-error').textContent = 'Enter an amount'; return; }
  const btn = $('#d-save');
  btn.disabled = true;
  const ok = await run(() => (a.mode === 'edit'
    ? api('PATCH', `/api/entries/${a.entry.id}`, { qty, unit, meal: mealSelect.value })
    : api('POST', '/api/entries', { date: state.date, meal: mealSelect.value, food_id: a.food.id, unit, qty })),
  $('#detail-error'));
  btn.disabled = false;
  if (!ok) return;
  loadDay();
  if (a.mode === 'edit') { addDlg.close(); toast('Saved'); return; }
  // Stay open so several foods can be added in a row.
  toast(`Added to ${MEAL_NAME[mealSelect.value]}: ${a.food.name}`);
  state.add = { mode: 'add' };
  $('#search').value = '';
  showPane('pick');
  await loadQuick();
  setTab(state.tab === 'quick' ? 'recent' : state.tab);
  if (matchMedia('(pointer: fine)').matches) $('#search').focus();
});

$('#d-delete').addEventListener('click', () => deleteEntry());

// ---------------------------------------------------------------- foods tab

async function loadFoods() {
  await loadQuick();
  const q = state.quick;
  if (!q) return;
  const row = (f, onclick) => el('li', {},
    el('button', { class: 'row-main', type: 'button', onclick },
      el('span', { class: 'row-name', dir: 'auto' }, f.name),
      el('span', { class: 'row-sub', dir: 'ltr' }, foodSub(f))));
  $('#my-foods').replaceChildren(...(q.mine.length
    ? q.mine.map((f) => row(f, () => openFoodForm(f.id)))
    : [el('li', { class: 'list-empty' }, 'No custom foods yet.')]));
  $('#fav-foods').replaceChildren(...(q.favorites.length
    ? q.favorites.map((f) => row(f, () => { openAdd(); openDetail(f.id); }))
    : [el('li', { class: 'list-empty' }, 'No favorites yet.')]));
}

const foodDlg = $('#food-dlg');
let editingFood = null;
$('#new-food').addEventListener('click', () => openFoodForm(null));
$('#food-close').addEventListener('click', () => foodDlg.close());
foodDlg.addEventListener('click', (e) => { if (e.target === foodDlg) foodDlg.close(); });

async function openFoodForm(id) {
  const f = $('#food-form');
  f.reset();
  $('#food-error').textContent = '';
  editingFood = null;
  if (id) {
    const food = await run(() => api('GET', `/api/foods/${id}`));
    if (!food) return;
    editingFood = food;
    const serving = food.units.find((u) => u.name !== GRAM) || { name: GRAM, grams: 100 };
    const k = serving.name === GRAM ? 1 : serving.grams / 100;
    f.name.value = food.name;
    f.serving_name.value = serving.name === GRAM ? '' : serving.name;
    f.serving_grams.value = serving.name === GRAM ? 100 : serving.grams;
    for (const n of ['kcal', 'protein', 'carbs', 'fat', 'fiber', 'sugar', 'sodium']) {
      const v = food[n] * k;
      f[n].value = v ? Math.round(v * 10) / 10 : '';
    }
  }
  $('#food-title').textContent = id ? 'Edit food' : 'New food';
  $('#food-delete').hidden = !id;
  foodDlg.showModal();
}

$('#food-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = e.target;
  const body = { name: f.name.value.trim(), serving_name: f.serving_name.value.trim() || 'serving' };
  for (const n of ['serving_grams', 'kcal', 'protein', 'carbs', 'fat', 'fiber', 'sugar', 'sodium']) {
    const v = f[n].value.trim() === '' ? 0 : toNum(f[n].value);
    if (!(v >= 0)) { $('#food-error').textContent = `Check the ${n.replace('_', ' ')} value`; return; }
    body[n] = v;
  }
  const ok = await run(() => (editingFood
    ? api('PUT', `/api/foods/${editingFood.id}`, body)
    : api('POST', '/api/foods', body)), $('#food-error'));
  if (!ok) return;
  foodDlg.close();
  toast(editingFood ? 'Food updated' : 'Food created');
  loadFoods();
});

$('#food-delete').addEventListener('click', async () => {
  if (!editingFood || !confirm(`Delete "${editingFood.name}"? Past log entries are kept.`)) return;
  const ok = await run(() => api('DELETE', `/api/foods/${editingFood.id}`));
  if (ok) { foodDlg.close(); toast('Food deleted'); loadFoods(); }
});

// ---------------------------------------------------------------- history

$$('#range button').forEach((b) => b.addEventListener('click', () => {
  state.range = Number(b.dataset.days);
  loadHistory();
}));

async function loadHistory() {
  $$('#range button').forEach((b) => b.classList.toggle('on', Number(b.dataset.days) === state.range));
  const s = await run(() => api('GET', `/api/stats?end=${todayISO()}&days=${state.range}`));
  if (!s) return;
  state.stats = s;
  renderHistory();
}

function tile(label, value, unit, sub) {
  return el('div', { class: 'tile' },
    el('div', { class: 'tile-label' }, label),
    el('div', { class: 'tile-value' }, value, unit ? el('small', {}, ` ${unit}`) : null),
    sub ? el('div', { class: 'tile-sub' }, sub) : null);
}

function renderHistory() {
  const s = state.stats;
  const n = s.days.length;
  $('#tiles').replaceChildren(
    tile('Average per day', fmt(s.avg.kcal), 'kcal', `Goal ${fmt(s.targets.kcal)} kcal`),
    tile('Days logged', `${s.logged_days}`, `/ ${n}`, s.logged_days ? `${Math.round((s.logged_days / n) * 100)}% of days` : 'Start logging today'),
    tile('On target', `${s.on_target}`, s.on_target === 1 ? 'day' : 'days', 'Within ±10% of goal'),
    tile('Streak', `${s.streak}`, s.streak === 1 ? 'day' : 'days', s.streak ? 'Logged in a row' : 'Log today to start one'));

  drawKcalChart($('#kcal-chart'), s);
  $('#kcal-table').replaceChildren(el('div', { class: 'table-scroll' }, el('table', {},
    el('thead', {}, el('tr', {}, ['Date', 'kcal', 'Goal', 'Protein', 'Carbs', 'Fat'].map((h) => el('th', {}, h)))),
    el('tbody', {}, s.days.filter((d) => d.kcal != null).reverse().map((d) => el('tr', {},
      el('td', {}, longDate(d.date)), el('td', {}, fmt(d.kcal)), el('td', {}, fmt(d.budget)),
      el('td', {}, fmt(d.protein)), el('td', {}, fmt(d.carbs)), el('td', {}, fmt(d.fat))))))));

  renderWeight(s);
  renderMacroSplit(s);
  renderMealSplit(s);

  const top = s.top_foods;
  const maxTop = top.length ? top[0].kcal : 1;
  $('#top-foods').replaceChildren(...(top.length ? top.map((f) => el('div', { class: 'hbar-row' },
    el('span', { class: 'name', dir: 'auto' }, f.name),
    el('span', { class: 'val' }, `${fmt(f.kcal)} kcal · ${f.times}×`),
    el('div', { class: 'track' }, el('div', { style: `width:${(f.kcal / maxTop) * 100}%` }))))
    : [el('p', { class: 'chart-empty' }, 'Nothing logged in this period.')]));
}

function renderMacroSplit(s) {
  const a = s.avg;
  const box = $('#macro-split');
  if (a.kcal == null) { box.replaceChildren(el('p', { class: 'chart-empty' }, 'Nothing logged in this period.')); return; }
  const kcals = MACROS.map(([k, , m]) => (a[k] || 0) * m);
  const total = kcals.reduce((x, y) => x + y, 0) || 1;
  const t = s.targets;
  box.replaceChildren(
    el('div', { class: 'stack', role: 'img', 'aria-label': 'Macro split' },
      MACROS.map(([k], i) => (kcals[i] > 0 ? el('div', { style: `flex:${kcals[i]};background:var(--${k})` }) : null))),
    el('div', { class: 'legend' }, MACROS.map(([k, label, m], i) => el('div', { class: 'legend-row' },
      el('i', { class: 'swatch', style: `background:var(--${k})` }),
      el('span', { class: 'name' }, label),
      el('span', { class: 'val' }, `${fmt(a[k])} g/day · ${Math.round((kcals[i] / total) * 100)}% (target ${Math.round(((t[k] * m) / t.kcal) * 100)}%)`)))));
}

function renderMealSplit(s) {
  const total = Object.values(s.meals).reduce((x, y) => x + y, 0);
  const box = $('#meal-split');
  if (!total) { box.replaceChildren(el('p', { class: 'chart-empty' }, 'Nothing logged in this period.')); return; }
  const logged = s.logged_days || 1;
  box.replaceChildren(...MEALS.map(([k, label]) => {
    const v = s.meals[k] || 0;
    return el('div', { class: 'hbar-row' },
      el('span', { class: 'name' }, label),
      el('span', { class: 'val' }, `${fmt(v / logged)} kcal/day · ${Math.round((v / total) * 100)}%`),
      el('div', { class: 'track' }, el('div', { style: `width:${(v / total) * 100}%` })));
  }));
}

function renderWeight(s) {
  const w = s.weights;
  const parts = [];
  if (s.current_weight) parts.push(`Now ${fmt1(s.current_weight)} kg`);
  if (w.length >= 2) {
    const diff = w[w.length - 1].kg - w[0].kg;
    parts.push(`${diff > 0 ? '+' : diff < 0 ? '−' : '±'}${fmt1(Math.abs(diff))} kg since ${shortDate(w[0].date)}`);
  }
  if (s.target_weight && s.current_weight) {
    const togo = s.target_weight - s.current_weight;
    if (Math.abs(togo) < 0.1) parts.push('Goal reached 🎉');
    else {
      let line = `${fmt1(Math.abs(togo))} kg to goal (${fmt1(s.target_weight)} kg)`;
      const rate = s.rate_kg_week;
      if (rate && Math.sign(rate) === Math.sign(togo)) line += `, about ${Math.ceil(Math.abs(togo / rate))} weeks at your pace`;
      parts.push(line);
    }
  }
  $('#weight-summary').textContent = parts.join(' · ') || 'Log your weight on the Today tab to see a trend.';
  drawWeightChart($('#weight-chart'), s);

  const all = w.slice().reverse();
  $('#weight-table').replaceChildren(all.length ? el('div', { class: 'table-scroll' }, el('table', {},
    el('thead', {}, el('tr', {}, el('th', {}, 'Date'), el('th', {}, 'kg'), el('th', {}, ''))),
    el('tbody', {}, all.map((r) => el('tr', {},
      el('td', {}, longDate(r.date)), el('td', {}, fmt1(r.kg)),
      el('td', {}, el('button', {
        type: 'button', class: 'danger',
        onclick: async () => {
          if (!confirm(`Delete weight for ${longDate(r.date)}?`)) return;
          if (await run(() => api('DELETE', `/api/weights/${r.date}`))) loadHistory();
        },
      }, 'Delete'))))))) : el('p', { class: 'muted' }, 'No weights in this period.'));
}

// ---------------------------------------------------------------- charts

const tip = $('#tooltip');
function showTip(evt, html) {
  tip.replaceChildren(...html);
  tip.hidden = false;
  const pt = evt.touches ? evt.touches[0] : evt;
  const r = tip.getBoundingClientRect();
  let x = pt.clientX + 14;
  let y = pt.clientY - r.height - 12;
  if (x + r.width > window.innerWidth - 8) x = pt.clientX - r.width - 14;
  if (x < 8) x = 8;
  if (y < 8) y = pt.clientY + 18;
  tip.style.left = `${x}px`;
  tip.style.top = `${y}px`;
}
const hideTip = () => { tip.hidden = true; };
window.addEventListener('scroll', hideTip, { passive: true });

function niceStep(max, target = 4) {
  const raw = max / target;
  const mag = 10 ** Math.floor(Math.log10(raw || 1));
  const n = raw / mag;
  return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 2.5 ? 2.5 : n <= 5 ? 5 : 10) * mag;
}
const compact = (v) => (v >= 1000 ? `${fmt1(v / 1000)}K` : fmt(v));

/** Column path with 4px rounded top, square at the baseline. */
function colPath(x, y, w, h) {
  const r = Math.min(4, w / 2, h);
  return `M${x},${y + h}V${y + r}Q${x},${y} ${x + r},${y}H${x + w - r}Q${x + w},${y} ${x + w},${y + r}V${y + h}Z`;
}

function xLabelEvery(n) {
  return n <= 7 ? 1 : n <= 31 ? 7 : n <= 92 ? 14 : 61;
}

function drawKcalChart(box, s) {
  const days = s.days;
  const W = Math.max(280, box.clientWidth || 320);
  const H = 200;
  const m = { l: 36, r: 8, t: 12, b: 24 };
  const iw = W - m.l - m.r;
  const ih = H - m.t - m.b;
  const maxV = Math.max(...days.map((d) => Math.max(d.kcal || 0, d.budget)), 500);
  const step = niceStep(maxV * 1.08);
  const top = Math.ceil((maxV * 1.08) / step) * step;
  const y = (v) => m.t + ih - (v / top) * ih;
  const slot = iw / days.length;
  const bw = Math.max(1, Math.min(24, slot * 0.7, slot - 2));

  const g = [];
  for (let v = 0; v <= top + 0.001; v += step) {
    g.push(svg('line', { class: v === 0 ? 'base' : 'grid', x1: m.l, x2: W - m.r, y1: y(v), y2: y(v) }));
    g.push(svg('text', { x: m.l - 6, y: y(v) + 4, 'text-anchor': 'end' }, compact(v)));
  }
  const every = xLabelEvery(days.length);
  days.forEach((d, i) => {
    const fromEnd = days.length - 1 - i;
    if (fromEnd % every === 0) {
      const label = days.length <= 7 ? dateOf(d.date).toLocaleDateString('en-US', { weekday: 'short' }) : shortDate(d.date);
      g.push(svg('text', { x: m.l + slot * i + slot / 2, y: H - 6, 'text-anchor': 'middle' }, label));
    }
  });
  const hoverCol = svg('rect', { class: 'hover-col', y: m.t, height: ih, width: slot, visibility: 'hidden' });
  g.push(hoverCol);
  days.forEach((d, i) => {
    if (!d.kcal) return;
    const h = Math.max(1, (d.kcal / top) * ih);
    g.push(svg('path', { class: 'bar', d: colPath(m.l + slot * i + (slot - bw) / 2, y(d.kcal), bw, h) }));
  });
  // Goal line (steps where exercise raises the budget).
  let path = '';
  days.forEach((d, i) => {
    const x0 = m.l + slot * i;
    path += `${i ? 'L' : 'M'}${x0},${y(d.budget)}H${x0 + slot}`;
  });
  g.push(svg('path', { class: 'goal', d: path }));
  const last = days[days.length - 1];
  g.push(svg('text', { class: 'label-strong', x: W - m.r, y: y(last.budget) - 5, 'text-anchor': 'end' }, `Goal ${fmt(last.budget)}`));

  const hit = svg('rect', { class: 'hit', x: m.l, y: m.t, width: iw, height: ih });
  const onMove = (evt) => {
    const pt = evt.touches ? evt.touches[0] : evt;
    const rect = hit.getBoundingClientRect();
    const i = Math.max(0, Math.min(days.length - 1, Math.floor(((pt.clientX - rect.left) / rect.width) * days.length)));
    const d = days[i];
    hoverCol.setAttribute('x', m.l + slot * i);
    hoverCol.setAttribute('visibility', 'visible');
    const lines = [el('b', {}, longDate(d.date)), el('br')];
    if (d.kcal == null) lines.push('Not logged');
    else {
      const diff = d.kcal - d.budget;
      lines.push(`${fmt(d.kcal)} kcal of ${fmt(d.budget)}`, el('br'),
        diff > 0 ? `${fmt(diff)} over` : `${fmt(-diff)} under`, el('br'),
        `P ${fmt(d.protein)} g · C ${fmt(d.carbs)} g · F ${fmt(d.fat)} g`);
    }
    showTip(evt, lines);
  };
  const onLeave = () => { hoverCol.setAttribute('visibility', 'hidden'); hideTip(); };
  hit.addEventListener('mousemove', onMove);
  hit.addEventListener('touchstart', onMove, { passive: true });
  hit.addEventListener('touchmove', onMove, { passive: true });
  hit.addEventListener('mouseleave', onLeave);
  hit.addEventListener('touchend', () => setTimeout(onLeave, 1500));
  g.push(hit);

  const root = svg('svg', { viewBox: `0 0 ${W} ${H}`, height: H, role: 'img', 'aria-label': 'Calories per day chart' }, g);
  box.replaceChildren(s.logged_days ? root : el('p', { class: 'chart-empty' }, 'Nothing logged in this period.'));
}

function drawWeightChart(box, s) {
  const pts = s.weights;
  if (!pts.length) { box.replaceChildren(); return; }
  const W = Math.max(280, box.clientWidth || 320);
  const H = 180;
  const m = { l: 40, r: 12, t: 14, b: 24 };
  const iw = W - m.l - m.r;
  const ih = H - m.t - m.b;
  const n = daysBetween(s.start, s.end) || 1;
  const x = (date) => m.l + (daysBetween(s.start, date) / n) * iw;
  const vals = pts.map((p) => p.kg);
  if (s.target_weight) vals.push(s.target_weight);
  let lo = Math.min(...vals);
  let hi = Math.max(...vals);
  const padV = Math.max(0.5, (hi - lo) * 0.15);
  lo -= padV; hi += padV;
  const step = niceStep(hi - lo, 4);
  lo = Math.floor(lo / step) * step;
  hi = Math.ceil(hi / step) * step;
  const y = (v) => m.t + ih - ((v - lo) / (hi - lo)) * ih;

  const g = [];
  for (let v = lo; v <= hi + 1e-9; v += step) {
    g.push(svg('line', { class: 'grid', x1: m.l, x2: W - m.r, y1: y(v), y2: y(v) }));
    g.push(svg('text', { x: m.l - 6, y: y(v) + 4, 'text-anchor': 'end' }, fmt1(v)));
  }
  const ticks = [s.start, addDays(s.start, Math.round(n / 2)), s.end];
  ticks.forEach((d, i) => g.push(svg('text', {
    x: x(d), y: H - 6, 'text-anchor': i === 0 ? 'start' : i === 2 ? 'end' : 'middle',
  }, shortDate(d))));
  if (s.target_weight) {
    g.push(svg('line', { class: 'target', x1: m.l, x2: W - m.r, y1: y(s.target_weight), y2: y(s.target_weight) }));
    g.push(svg('text', { class: 'label-strong', x: m.l + 4, y: y(s.target_weight) - 5 }, `Goal ${fmt1(s.target_weight)} kg`));
  }
  if (pts.length > 1) {
    const line = pts.map((p, i) => `${i ? 'L' : 'M'}${x(p.date)},${y(p.kg)}`).join('');
    g.push(svg('path', { class: 'line', d: line }));
  }
  const showDots = pts.length <= 60;
  pts.forEach((p, i) => {
    if (showDots || i === pts.length - 1) g.push(svg('circle', { class: 'dot', cx: x(p.date), cy: y(p.kg), r: 4 }));
  });
  const lastP = pts[pts.length - 1];
  g.push(svg('text', { class: 'label-strong', x: Math.min(x(lastP.date), W - m.r), y: y(lastP.kg) - 9, 'text-anchor': 'end' }, `${fmt1(lastP.kg)} kg`));

  const ring = svg('circle', { class: 'dot', r: 6, visibility: 'hidden' });
  g.push(ring);
  const hit = svg('rect', { class: 'hit', x: m.l - 8, y: m.t, width: iw + 16, height: ih });
  const onMove = (evt) => {
    const pt = evt.touches ? evt.touches[0] : evt;
    const rect = hit.getBoundingClientRect();
    const px = ((pt.clientX - rect.left) / rect.width) * (iw + 16) + m.l - 8;
    let best = pts[0];
    for (const p of pts) if (Math.abs(x(p.date) - px) < Math.abs(x(best.date) - px)) best = p;
    ring.setAttribute('cx', x(best.date));
    ring.setAttribute('cy', y(best.kg));
    ring.setAttribute('visibility', 'visible');
    showTip(evt, [el('b', {}, longDate(best.date)), el('br'), `${fmt1(best.kg)} kg`]);
  };
  const onLeave = () => { ring.setAttribute('visibility', 'hidden'); hideTip(); };
  hit.addEventListener('mousemove', onMove);
  hit.addEventListener('touchstart', onMove, { passive: true });
  hit.addEventListener('touchmove', onMove, { passive: true });
  hit.addEventListener('mouseleave', onLeave);
  hit.addEventListener('touchend', () => setTimeout(onLeave, 1500));
  g.push(hit);
  box.replaceChildren(svg('svg', { viewBox: `0 0 ${W} ${H}`, height: H, role: 'img', 'aria-label': 'Weight chart' }, g));
}

window.addEventListener('resize', debounce(() => {
  if (!$('#view-history').hidden && state.stats) {
    drawKcalChart($('#kcal-chart'), state.stats);
    drawWeightChart($('#weight-chart'), state.stats);
  }
}, 150));

// ---------------------------------------------------------------- settings

const ACTIVITY = { sedentary: 1.2, light: 1.375, moderate: 1.55, active: 1.725, very: 1.9 };

async function loadProfile() {
  const p = await run(() => api('GET', '/api/profile'));
  if (!p) return;
  const f = $('#profile-form');
  for (const r of $$('input[name=sex]', f)) r.checked = r.value === p.sex;
  f.age.value = p.age ?? '';
  f.height_cm.value = p.height_cm ?? '';
  f.weight.value = p.weight ?? '';
  f.activity.value = p.activity || 'light';
  f.rate_kg_week.value = String(p.rate_kg_week ?? 0);
  if (!f.rate_kg_week.value) f.rate_kg_week.value = '0';
  f.target_weight.value = p.target_weight ?? '';
  f.calorie_goal.value = p.calorie_goal ?? '';
  f.protein_pct.value = p.protein_pct;
  f.carbs_pct.value = p.carbs_pct;
  f.fat_pct.value = p.fat_pct;
  f.water_goal.value = p.water_goal;
  $('#profile-error').textContent = '';
  updateCalc();
}

/** Mirror of the server's formula (Mifflin-St Jeor) for a live preview. */
function updateCalc() {
  const f = $('#profile-form');
  const sex = ($('input[name=sex]:checked', f) || {}).value;
  const age = toNum(f.age.value);
  const h = toNum(f.height_cm.value);
  const w = toNum(f.weight.value);
  const box = $('#calc');
  let suggested = null;
  if (sex && age > 0 && h > 0 && w > 0) {
    const bmr = 10 * w + 6.25 * h - 5 * age + (sex === 'male' ? 5 : -161);
    const tdee = bmr * ACTIVITY[f.activity.value];
    const rate = Number(f.rate_kg_week.value);
    const floor = sex === 'male' ? 1500 : 1200;
    const raw = tdee + (rate * 7700) / 7;
    suggested = Math.round(Math.max(floor, raw) / 10) * 10;
    box.replaceChildren(
      'You burn about ', el('b', {}, `${fmt(tdee)} kcal`), ' a day (resting ', fmt(bmr), '). ',
      'Suggested goal: ', el('b', {}, `${fmt(suggested)} kcal/day`), '.',
      ...(raw < floor ? [el('div', { class: 'hint' }, `Raised to the ${floor} kcal safe minimum. Consider a slower goal.`)] : []));
  } else {
    box.textContent = 'Fill in sex, age, height and weight to get a suggested calorie goal.';
  }
  f.calorie_goal.placeholder = suggested ? `${suggested} (suggested)` : '2000';
  const sum = ['protein_pct', 'carbs_pct', 'fat_pct'].reduce((s, k) => s + (toNum(f[k].value) || 0), 0);
  const goal = toNum(f.calorie_goal.value) || suggested || 2000;
  const pctEl = $('#pct-sum');
  if (sum !== 100) {
    pctEl.textContent = `Adds up to ${sum}%. It needs to be 100%.`;
    pctEl.style.color = 'var(--critical)';
  } else {
    pctEl.style.color = '';
    pctEl.textContent = `= ${fmt((goal * toNum(f.protein_pct.value)) / 400)} g protein, `
      + `${fmt((goal * toNum(f.carbs_pct.value)) / 400)} g carbs, ${fmt((goal * toNum(f.fat_pct.value)) / 900)} g fat per day`;
  }
}
$('#profile-form').addEventListener('input', updateCalc);
$('#profile-form').addEventListener('change', updateCalc);

$('#profile-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = e.target;
  const n = (v) => (String(v).trim() === '' ? null : toNum(v));
  const body = {
    sex: ($('input[name=sex]:checked', f) || {}).value || null,
    age: n(f.age.value),
    height_cm: n(f.height_cm.value),
    weight: n(f.weight.value),
    activity: f.activity.value,
    rate_kg_week: Number(f.rate_kg_week.value),
    target_weight: n(f.target_weight.value),
    calorie_goal: n(f.calorie_goal.value),
    protein_pct: n(f.protein_pct.value),
    carbs_pct: n(f.carbs_pct.value),
    fat_pct: n(f.fat_pct.value),
    water_goal: n(f.water_goal.value),
    date: todayISO(),
  };
  for (const [k, v] of Object.entries(body)) {
    if (typeof v === 'number' && isNaN(v)) { $('#profile-error').textContent = `Check the ${k.replace(/_/g, ' ')} value`; return; }
  }
  const p = await run(() => api('PUT', '/api/profile', body), $('#profile-error'));
  if (p) toast(`Saved. Daily goal: ${fmt(p.targets.kcal)} kcal`);
});

$('#pw-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = e.target;
  const ok = await run(() => api('POST', '/api/password', { current: f.current.value, new: f.new.value }), $('#pw-error'));
  if (ok) { f.reset(); toast('Password changed'); }
});

boot();
