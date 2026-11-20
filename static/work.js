'use strict';
// The "Time" and "Plan" tabs: a timer and time log, projects, reports, a day planner and to-dos,
// plus their settings (Google Calendar, calendar feed). Uses the helpers from app.js.

const PROJECT_COLORS = ['#2a78d6', '#eb6834', '#1baf7a', '#8b6cf0', '#e0508f', '#e8a317', '#14a3b8', '#d03b3b'];
const NO_PROJECT_COLOR = '#9a9890';
const HOUR_PX = 48; // planner timeline scale
const JM_FA = ['فروردین', 'اردیبهشت', 'خرداد', 'تیر', 'مرداد', 'شهریور', 'مهر', 'آبان', 'آذر', 'دی', 'بهمن', 'اسفند'];
const WD_FA = ['یکشنبه', 'دوشنبه', 'سه‌شنبه', 'چهارشنبه', 'پنجشنبه', 'جمعه', 'شنبه'];
const WD_FA_SHORT = ['ی', 'د', 'س', 'چ', 'پ', 'ج', 'ش'];
const PRIORITY = ['', 'Low', 'Medium', 'High'];

const W = {
  prefs: { work_goal: 8, week_start: 'auto', calendar: 'gregorian' },
  projects: [], P: new Map(), unassigned: 0,
  tasks: [], T: new Map(),
  running: null, skew: 0,
  recent: [], recentDays: 14,
  timeTab: 'timer', planTab: 'schedule',
  report: { preset: 'week', anchor: todayISO() }, reportData: null,
  planDate: todayISO(), week: null, scrolledFor: null,
  todoFilter: 'all',
  google: null, calendars: null,
  telegram: null,
};

// ---------------------------------------------------------------- time helpers

const nowS = () => Math.floor(Date.now() / 1000) + W.skew;
const tsOf = (d) => Math.floor(d.getTime() / 1000);
const dayTs = (iso) => tsOf(dateOf(iso)); // local midnight
const isoOfTs = (ts) => isoOf(new Date(ts * 1000));
const hmOf = (d) => `${pad(d.getHours())}:${pad(d.getMinutes())}`;
const hm = (ts) => hmOf(new Date(ts * 1000));
const hmm = (min) => `${pad(Math.floor(min / 60))}:${pad(min % 60)}`;
const endOf = (e) => e.end_ts ?? nowS();
const overlap = (e, a, b) => Math.max(0, Math.min(endOf(e), b) - Math.max(e.start_ts, a));
const spanIn = (s, e, a, b) => Math.max(0, Math.min(e, b) - Math.max(s, a));
const hoursText2 = (sec) => `${fmt1(sec / 3600)} h`;
const selVal = (sel) => (sel.value === '' ? null : Number(sel.value));
const localDT = (ts) => `${isoOfTs(ts)}T${hm(ts)}`; // for <input type="datetime-local">
const tsOfLocalDT = (v) => (v ? tsOf(new Date(v)) : null);

function clock(sec) {
  const s = Math.max(0, Math.floor(sec));
  return `${Math.floor(s / 3600)}:${pad(Math.floor((s % 3600) / 60))}:${pad(s % 60)}`;
}

/** 5400 -> "1 h 30 min" */
function hrs(sec) {
  const m = Math.round(sec / 60);
  if (m < 60) return `${m} min`;
  return m % 60 ? `${Math.floor(m / 60)} h ${m % 60} min` : `${m / 60} h`;
}

function setSkew(serverNow) {
  if (serverNow) W.skew = serverNow - Math.floor(Date.now() / 1000);
}

// ---------------------------------------------------------------- calendars (Gregorian or Shamsi)

const isJalali = () => W.prefs.calendar === 'jalali';
const faDigits = (s) => String(s).replace(/\d/g, (d) => '۰۱۲۳۴۵۶۷۸۹'[d]);
const fa = (text) => el('bdi', { class: 'fa', dir: 'rtl' }, text);

function weekStartDay() {
  const w = W.prefs.week_start;
  if (w === 'sat') return 6;
  if (w === 'sun') return 0;
  if (w === 'mon') return 1;
  return isJalali() ? 6 : 1;
}

function startOfWeek(iso) {
  const d = dateOf(iso);
  return addDays(iso, -((d.getDay() - weekStartDay() + 7) % 7));
}

function monthStart(iso) {
  const d = dateOf(iso);
  if (isJalali()) {
    const j = Jalali.fromDate(d);
    return isoOf(Jalali.toDate(j.jy, j.jm, 1));
  }
  return isoOf(new Date(d.getFullYear(), d.getMonth(), 1));
}

function addMonths(iso, k) {
  const d = dateOf(iso);
  if (isJalali()) {
    const j = Jalali.fromDate(d);
    const i = j.jy * 12 + (j.jm - 1) + k;
    return isoOf(Jalali.toDate(Math.floor(i / 12), (i % 12) + 1, 1));
  }
  return isoOf(new Date(d.getFullYear(), d.getMonth() + k, 1));
}

function yearStart(iso) {
  const d = dateOf(iso);
  if (isJalali()) return isoOf(Jalali.toDate(Jalali.fromDate(d).jy, 1, 1));
  return isoOf(new Date(d.getFullYear(), 0, 1));
}
const addYears = (iso, k) => addMonths(yearStart(iso), 12 * k);

/** "Wed, Nov 18" — or in Shamsi mode "چهارشنبه ۲۷ آبان · Nov 18". */
function dayTitle(iso) {
  const d = dateOf(iso);
  if (!isJalali()) return [longDate(iso)];
  const j = Jalali.fromDate(d);
  return [fa(`${WD_FA[d.getDay()]} ${faDigits(j.jd)} ${JM_FA[j.jm - 1]}`), el('span', { class: 'muted' }, ` · ${shortDate(iso)}`)];
}

function relDay(iso) {
  const rel = { 0: 'Today', '-1': 'Yesterday', 1: 'Tomorrow' }[daysBetween(todayISO(), iso)];
  if (!rel) return dayTitle(iso);
  if (!isJalali()) return [`${rel}, ${shortDate(iso)}`];
  const j = Jalali.fromDate(dateOf(iso));
  return [`${rel} · `, fa(`${faDigits(j.jd)} ${JM_FA[j.jm - 1]}`)];
}

function shortDay(iso) {
  if (!isJalali()) return shortDate(iso);
  const j = Jalali.fromDate(dateOf(iso));
  return `${faDigits(j.jd)} ${JM_FA[j.jm - 1]}`;
}

function monthTitle(iso) {
  if (!isJalali()) return dateOf(iso).toLocaleDateString('en-US', { month: 'long', year: 'numeric' });
  const j = Jalali.fromDate(dateOf(iso));
  return `${JM_FA[j.jm - 1]} ${faDigits(j.jy)}`;
}

const dayNum = (iso) => (isJalali() ? faDigits(Jalali.fromDate(dateOf(iso)).jd) : String(dateOf(iso).getDate()));
const weekdayShort = (iso) => (isJalali() ? WD_FA_SHORT[dateOf(iso).getDay()]
  : dateOf(iso).toLocaleDateString('en-US', { weekday: 'short' }));

// ---------------------------------------------------------------- data

async function loadPrefs() {
  const p = await run(() => api('GET', '/api/prefs'));
  if (p) W.prefs = p;
}

async function loadProjects() {
  const r = await run(() => api('GET', '/api/projects'));
  if (!r) return;
  W.projects = r.projects;
  W.unassigned = r.unassigned;
  W.P = new Map(r.projects.map((p) => [p.id, p]));
}

async function loadTasks() {
  const r = await run(() => api('GET', '/api/tasks'));
  if (!r) return;
  W.tasks = r.tasks;
  W.T = new Map(r.tasks.map((t) => [t.id, t]));
}

async function loadTimer() {
  const r = await run(() => api('GET', '/api/time/timer'));
  if (!r) return;
  setSkew(r.now);
  W.running = r.running;
  updateTimerBar();
  renderTimerCard();
}

/** Called by app.js after logging in. */
async function workStart() {
  await Promise.all([loadPrefs(), loadProjects(), loadTasks(), loadTimer()]);
}

/** Called by app.js on log out. */
function workReset() {
  Object.assign(W, {
    projects: [], P: new Map(), tasks: [], T: new Map(), running: null, recent: [], week: null,
    reportData: null, google: null, calendars: null, planDate: todayISO(), scrolledFor: null,
  });
  updateTimerBar();
}

/** The app came back to the foreground. */
function workOnResume() {
  if ($('#app').hidden) return;
  loadTimer();
  if (state.view === 'time') showTime(W.timeTab);
  if (state.view === 'plan') showPlan(W.planTab);
}

const projOf = (id) => (id == null ? null : W.P.get(id));
const projColor = (id) => projOf(id)?.color || NO_PROJECT_COLOR;
const projName = (id) => (id == null ? 'No project' : projOf(id)?.name || 'Deleted project');

function projectOptions(selected, noneLabel = 'No project') {
  const opts = [el('option', { value: '' }, noneLabel)];
  for (const p of W.projects) {
    if (!p.archived || p.id === selected) opts.push(el('option', { value: String(p.id) }, p.name + (p.archived ? ' (archived)' : '')));
  }
  return opts;
}

/** Open tasks for a project picker: the project's tasks and the ones without a project. */
function taskOptions(projectId, selected) {
  const list = W.tasks.filter((t) => (!t.done || t.id === selected)
    && (projectId == null || t.project_id == null || t.project_id === projectId));
  return [el('option', { value: '' }, 'No task'), ...list.map((t) => el('option', { value: String(t.id) }, t.title))];
}

function fillPT(ps, ts, pid, tid) {
  ps.replaceChildren(...projectOptions(pid));
  ps.value = pid == null ? '' : String(pid);
  ts.replaceChildren(...taskOptions(pid, tid));
  ts.value = tid == null ? '' : String(tid);
}

/** Keep a project and task <select> in step: picking a task picks its project. */
function wirePT(ps, ts, onChange) {
  ps.addEventListener('change', () => {
    const pid = selVal(ps);
    const t = W.T.get(selVal(ts));
    const keep = t && (t.project_id == null || t.project_id === pid) ? t.id : null;
    ts.replaceChildren(...taskOptions(pid, keep));
    ts.value = keep == null ? '' : String(keep);
    if (onChange) onChange();
  });
  ts.addEventListener('change', () => {
    const t = W.T.get(selVal(ts));
    if (t && t.project_id != null && selVal(ps) !== t.project_id) {
      ps.value = String(t.project_id);
      ts.replaceChildren(...taskOptions(t.project_id, t.id));
      ts.value = String(t.id);
    }
    if (onChange) onChange();
  });
}

// Closing buttons and backdrop taps for the dialogs in this file.
for (const id of ['entry-dlg', 'project-dlg', 'task-dlg', 'block-dlg']) {
  const dlg = $(`#${id}`);
  dlg.addEventListener('click', (e) => { if (e.target === dlg) dlg.close(); });
  $('[data-close]', dlg).addEventListener('click', () => dlg.close());
}

// ---------------------------------------------------------------- live clocks

function tick() {
  const now = nowS();
  for (const n of document.querySelectorAll('[data-live]')) {
    n.textContent = clock(now - Number(n.dataset.live) + Number(n.dataset.base || 0));
  }
}
setInterval(tick, 1000);
setInterval(() => { if (state.view === 'plan' && W.planTab === 'schedule') placeNowLine(); }, 60000);

const liveAttrs = (start, base = 0) => ({ 'data-live': String(start), 'data-base': String(base) });

// ---------------------------------------------------------------- timer bar (shown on every other screen)

function updateTimerBar() {
  const r = W.running;
  const onTimer = state.view === 'time' && W.timeTab === 'timer';
  const show = !!r && !$('#app').hidden && !onTimer;
  $('#timer-bar').hidden = !show;
  document.body.classList.toggle('has-timer', show);
  if (!show) return;
  $('#timer-bar-dot').style.background = projColor(r.project_id);
  const t = W.T.get(r.task_id);
  $('#timer-bar-text').textContent = r.description || (t ? t.title : projName(r.project_id));
  const c = $('#timer-bar-clock');
  Object.assign(c.dataset, { live: String(r.start_ts), base: '0' });
  c.textContent = clock(nowS() - r.start_ts);
}
$('#timer-bar-stop').addEventListener('click', () => stopTimer());

// ---------------------------------------------------------------- Time tab

function showTime(sub) {
  W.timeTab = ['timer', 'projects', 'reports'].includes(sub) ? sub : 'timer';
  $$('#time-seg button').forEach((b) => b.classList.toggle('on', b.dataset.sub === W.timeTab));
  for (const k of ['timer', 'projects', 'reports']) $(`#time-${k}`).hidden = k !== W.timeTab;
  updateTimerBar();
  if (W.timeTab === 'timer') loadTimerView();
  if (W.timeTab === 'projects') loadProjectsView();
  if (W.timeTab === 'reports') loadReport();
}
$$('#time-seg button').forEach((b) => b.addEventListener('click', () => {
  location.hash = b.dataset.sub === 'timer' ? '#time' : `#time/${b.dataset.sub}`;
}));

// --- timer

const DRAFT_KEY = 'ct-timer-draft';
function loadDraft() {
  try { return JSON.parse(localStorage.getItem(DRAFT_KEY)) || {}; } catch { return {}; }
}
function saveDraft(d) {
  try { localStorage.setItem(DRAFT_KEY, JSON.stringify(d)); } catch { /* storage unavailable */ }
}

async function loadTimerView() {
  renderTimerCard();
  renderTimeLog();
  await loadRecent();
}

async function loadRecent() {
  const from = dayTs(addDays(todayISO(), -(W.recentDays - 1)));
  const to = dayTs(addDays(todayISO(), 1));
  const res = await run(() => api('GET', `/api/time/entries?from=${from}&to=${to}`));
  if (!res) return;
  setSkew(res.now);
  W.recent = res.entries;
  W.running = res.running;
  updateTimerBar();
  renderTimerCard();
  renderTimeLog();
}

function renderTimerCard() {
  if ($('#time-timer').hidden) return;
  const r = W.running;
  const cur = r || loadDraft();
  const desc = $('#t-desc');
  if (document.activeElement !== desc) desc.value = cur.description || '';
  if (![$('#t-proj'), $('#t-task')].includes(document.activeElement)) {
    fillPT($('#t-proj'), $('#t-task'), cur.project_id ?? null, cur.task_id ?? null);
  }
  const go = $('#t-go');
  go.textContent = r ? 'Stop' : 'Start';
  go.classList.toggle('stop', !!r);
  const c = $('#t-clock');
  c.classList.toggle('on', !!r);
  if (r) {
    Object.assign(c.dataset, { live: String(r.start_ts), base: '0' });
    c.textContent = clock(nowS() - r.start_ts);
  } else {
    delete c.dataset.live;
    c.textContent = '0:00:00';
  }
  renderTimerStats();
}

function renderTimerStats() {
  const today = todayISO();
  const t0 = dayTs(today);
  const t1 = dayTs(addDays(today, 1));
  const w0 = dayTs(startOfWeek(today));
  const r = W.running;
  const done = (a, b) => W.recent.filter((e) => e.end_ts != null).reduce((s, e) => s + overlap(e, a, b), 0);
  const dayDone = done(t0, t1);
  const weekDone = done(w0, t1);
  const dayAll = dayDone + (r ? overlap(r, t0, t1) : 0);
  const goal = W.prefs.work_goal * 3600;
  const cell = (label, secs, base, from) => el('div', { class: 'stat' }, el('span', {}, label),
    el('b', r ? liveAttrs(Math.max(r.start_ts, from), base) : {}, clock(secs)));
  $('#t-stats').replaceChildren(
    cell('Today', dayAll, dayDone, t0),
    cell('This week', weekDone + (r ? overlap(r, w0, t1) : 0), weekDone, w0),
    goal ? el('div', { class: 'stat' }, el('span', {}, 'Daily goal'),
      el('b', {}, `${fmt1(W.prefs.work_goal)} h`),
      el('div', { class: 'meter' }, el('div', { class: 'fill', style: `width:${Math.min(100, (dayAll / goal) * 100)}%` })))
      : null);
}

function renderTimeLog() {
  const box = $('#t-log');
  if (!W.recent.length) {
    box.replaceChildren(el('div', { class: 'meal-empty' }, 'Nothing logged in the last two weeks. Press Start, or add time by hand.'));
    return;
  }
  const groups = new Map();
  for (const e of W.recent) {
    const k = isoOfTs(e.start_ts);
    if (!groups.has(k)) groups.set(k, []);
    groups.get(k).push(e);
  }
  const out = [];
  for (const [day, list] of groups) {
    const done = list.filter((e) => e.end_ts != null).reduce((s, e) => s + e.end_ts - e.start_ts, 0);
    const live = list.find((e) => e.end_ts == null);
    out.push(el('div', { class: 'log-day' }, el('span', {}, relDay(day)),
      el('b', live ? liveAttrs(live.start_ts, done) : {}, clock(done + (live ? nowS() - live.start_ts : 0)))));
    out.push(el('ul', { class: 'entries' }, list.map((e) => el('li', {}, entryRow(e)))));
  }
  box.replaceChildren(...out);
}

function entryRow(e) {
  const running = e.end_ts == null;
  const t = W.T.get(e.task_id);
  const title = e.description || (t ? t.title : projName(e.project_id));
  const where = [projName(e.project_id)];
  if (t && e.description) where.push(` › ${t.title}`);
  return el('div', { class: 'time-row' },
    el('button', { class: 'entry', type: 'button', onclick: () => openEntry(e) },
      el('i', { class: 'dot', style: `background:${projColor(e.project_id)}` }),
      el('span', { class: 'entry-main' },
        el('span', { class: 'entry-name', dir: 'auto' }, title, running ? el('span', { class: 'live-tag' }, 'LIVE') : null),
        el('span', { class: 'entry-sub' }, el('bdi', {}, where.join('')),
          ` · ${hm(e.start_ts)}–${running ? 'now' : hm(e.end_ts)}`,
          !running && isoOfTs(e.end_ts - 1) !== isoOfTs(e.start_ts) ? ' (+1 day)' : '')),
      el('span', { class: 'entry-kcal num', ...(running ? liveAttrs(e.start_ts) : {}) }, clock(endOf(e) - e.start_ts))),
    running
      ? el('button', { class: 'icon small play stop', type: 'button', 'aria-label': 'Stop timer', onclick: () => stopTimer() }, '■')
      : el('button', {
        class: 'icon small play', type: 'button', title: 'Continue this', 'aria-label': `Continue ${title}`,
        onclick: () => startTimer({ description: e.description, project_id: e.project_id, task_id: e.task_id }),
      }, '▶'));
}

async function startTimer(preset) {
  const data = preset || {
    description: $('#t-desc').value.trim(), project_id: selVal($('#t-proj')), task_id: selVal($('#t-task')),
  };
  const res = await run(() => api('POST', '/api/time/timer/start', data));
  if (!res) return;
  setSkew(res.now);
  W.running = res.running;
  saveDraft({});
  const t = W.T.get(data.task_id);
  toast(`Timer started: ${data.description || (t ? t.title : projName(data.project_id))}`);
  afterTimeChange();
}

async function stopTimer() {
  const res = await run(() => api('POST', '/api/time/timer/stop'));
  if (!res) return;
  W.running = null;
  if (res.stopped) toast(`Logged ${hrs(res.stopped.end_ts - res.stopped.start_ts)}`);
  afterTimeChange();
}

function afterTimeChange() {
  updateTimerBar();
  renderTimerCard();
  if (state.view === 'time') showTime(W.timeTab);
  if (state.view === 'plan' && W.planTab === 'schedule') loadPlanDay();
  if (state.view === 'plan' && W.planTab === 'todo') loadTodoView();
}

/** While the timer runs, edits go straight to it; otherwise they're kept for the next start. */
async function syncTimerFields() {
  const data = { description: $('#t-desc').value.trim(), project_id: selVal($('#t-proj')), task_id: selVal($('#t-task')) };
  if (!W.running) { saveDraft(data); return; }
  const res = await run(() => api('PATCH', `/api/time/entries/${W.running.id}`, data));
  if (!res) return;
  W.running = res;
  updateTimerBar();
  loadRecent();
}

$('#t-go').addEventListener('click', () => (W.running ? stopTimer() : startTimer()));
$('#t-desc').addEventListener('change', syncTimerFields);
$('#t-desc').addEventListener('keydown', (e) => {
  if (e.key !== 'Enter') return;
  e.preventDefault();
  if (W.running) e.target.blur(); else startTimer();
});
wirePT($('#t-proj'), $('#t-task'), syncTimerFields);
$('#t-add').addEventListener('click', () => openEntry(null));
$('#t-more').addEventListener('click', () => { W.recentDays += 14; loadRecent(); });

// --- time entry dialog

const entryDlg = $('#entry-dlg');
const ef = $('#entry-form');
let editingEntry = null;
wirePT(ef.proj, ef.task);

function openEntry(e, defaults = {}) {
  editingEntry = e || null;
  ef.reset();
  $('#entry-error').textContent = '';
  const running = !!e && e.end_ts == null;
  let date = defaults.date || todayISO();
  let start = '09:00';
  let end = '10:00';
  if (e) {
    date = isoOfTs(e.start_ts);
    start = hm(e.start_ts);
    end = running ? '' : hm(e.end_ts);
  } else if (date === todayISO()) {
    const n = new Date();
    n.setMinutes(n.getMinutes() - (n.getMinutes() % 5), 0, 0);
    const s = new Date(n.getTime() - 3600e3);
    start = isoOf(s) === date ? hmOf(s) : '00:00';
    end = hmOf(n);
  }
  ef.desc.value = e ? e.description : '';
  fillPT(ef.proj, ef.task, e ? e.project_id : null, e ? e.task_id : null);
  ef.date.value = date;
  ef.start.value = start;
  ef.end.value = end;
  ef.end.disabled = running;
  $('#entry-title').textContent = e ? (running ? 'Edit running timer' : 'Edit time') : 'Add time';
  $('#entry-save').textContent = e ? 'Save' : 'Add';
  $('#entry-delete').hidden = !e;
  entryHint();
  entryDlg.showModal();
}

/** Start and end as Dates; an end at or before the start means the next day. */
function spanFromForm(f) {
  if (!f.date.value || !f.start.value) return null;
  const d = dateOf(f.date.value);
  const [sh, sm] = f.start.value.split(':').map(Number);
  const a = new Date(d.getFullYear(), d.getMonth(), d.getDate(), sh, sm);
  if (f.end.disabled) return [a, null];
  if (!f.end.value) return null;
  const [eh, em] = f.end.value.split(':').map(Number);
  let b = new Date(d.getFullYear(), d.getMonth(), d.getDate(), eh, em);
  if (b <= a) b = new Date(d.getFullYear(), d.getMonth(), d.getDate() + 1, eh, em);
  return [a, b];
}

function spanHint(span) {
  if (!span) return '';
  const [a, b] = span;
  const secs = ((b ? b.getTime() : Date.now()) - a.getTime()) / 1000;
  return `Duration: ${hrs(secs)}${b && isoOf(a) !== isoOf(b) ? ' · ends the next day' : ''}`;
}

function entryHint() { $('#entry-hint').textContent = spanHint(spanFromForm(ef)); }
ef.addEventListener('input', entryHint);

ef.addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const err = $('#entry-error');
  const span = spanFromForm(ef);
  if (!span) { err.textContent = 'Enter the date, start and end.'; return; }
  const [a, b] = span;
  const body = { description: ef.desc.value.trim(), project_id: selVal(ef.proj), task_id: selVal(ef.task), start_ts: tsOf(a) };
  if (b) body.end_ts = tsOf(b);
  const e = editingEntry;
  const res = await run(() => (e ? api('PATCH', `/api/time/entries/${e.id}`, body) : api('POST', '/api/time/entries', body)), err);
  if (!res) return;
  entryDlg.close();
  if (W.running && e && W.running.id === e.id) W.running = res;
  toast(e ? 'Saved' : `Added ${hrs(body.end_ts - body.start_ts)}`);
  afterTimeChange();
});

$('#entry-delete').addEventListener('click', async () => {
  const e = editingEntry;
  if (!e || !confirm('Delete this time entry?')) return;
  if (!await run(() => api('DELETE', `/api/time/entries/${e.id}`))) return;
  entryDlg.close();
  if (W.running && W.running.id === e.id) W.running = null;
  toast('Entry deleted');
  afterTimeChange();
});

// --- projects

async function loadProjectsView() {
  renderProjects();
  await Promise.all([loadProjects(), loadTasks()]);
  renderProjects();
}

function renderProjects() {
  if ($('#time-projects').hidden) return;
  const row = (p) => el('li', {}, el('div', { class: 'time-row' },
    el('button', { class: 'entry', type: 'button', onclick: () => openProject(p) },
      el('i', { class: 'dot', style: `background:${p.color}` }),
      el('span', { class: 'entry-main' },
        el('span', { class: 'entry-name', dir: 'auto' }, p.name),
        el('span', { class: 'entry-sub' }, `${hoursText2(p.total)} logged`,
          p.open_tasks ? ` · ${p.open_tasks} open to-do${p.open_tasks === 1 ? '' : 's'}` : ''))),
    p.archived ? null : el('button', {
      class: 'icon small play', type: 'button', title: 'Start timer', 'aria-label': `Start timer on ${p.name}`,
      onclick: () => startTimer({ description: '', project_id: p.id, task_id: null }),
    }, '▶')));
  const active = W.projects.filter((p) => !p.archived);
  const archived = W.projects.filter((p) => p.archived);
  $('#p-list').replaceChildren(active.length
    ? el('ul', { class: 'entries flush' }, active.map(row))
    : el('div', { class: 'meal-empty pad' }, 'No projects yet. Projects group your time, plans and to-dos.'));
  $('#p-archived-box').hidden = !archived.length;
  $('#p-archived').replaceChildren(el('ul', { class: 'entries flush' }, archived.map(row)));
}

const projectDlg = $('#project-dlg');
const pf = $('#project-form');
let editingProject = null;
let projectColor = PROJECT_COLORS[0];
$('#p-new').addEventListener('click', () => openProject(null));

function openProject(p) {
  editingProject = p || null;
  pf.reset();
  $('#project-error').textContent = '';
  pf.name.value = p ? p.name : '';
  pf.archived.checked = !!(p && p.archived);
  $('#project-archived-row').hidden = !p;
  const used = new Set(W.projects.filter((x) => !x.archived).map((x) => x.color));
  projectColor = p ? p.color : PROJECT_COLORS.find((c) => !used.has(c)) || PROJECT_COLORS[0];
  renderSwatches();
  $('#project-title').textContent = p ? 'Edit project' : 'New project';
  $('#project-delete').hidden = !p;
  const stats = [];
  if (p) {
    stats.push(`${hrs(p.total)} logged in total. `);
    stats.push(p.open_tasks ? el('button', {
      class: 'link', type: 'button',
      onclick: () => { projectDlg.close(); W.todoFilter = `p${p.id}`; location.hash = '#plan/todo'; },
    }, `See its ${p.open_tasks} open to-do${p.open_tasks === 1 ? '' : 's'}`) : 'No open to-dos.');
  }
  $('#project-stats').replaceChildren(...stats);
  projectDlg.showModal();
  if (!p) pf.name.focus();
}

function renderSwatches() {
  const custom = el('input', { type: 'color', value: projectColor, 'aria-label': 'Custom color' });
  custom.addEventListener('change', () => { projectColor = custom.value; renderSwatches(); });
  $('#project-colors').replaceChildren(
    ...PROJECT_COLORS.map((c) => el('button', {
      type: 'button', class: `swatch-btn${c === projectColor ? ' on' : ''}`, style: `background:${c}`,
      role: 'radio', 'aria-checked': String(c === projectColor), 'aria-label': `Color ${c}`,
      onclick: () => { projectColor = c; renderSwatches(); },
    })),
    el('label', { class: `swatch-btn custom${PROJECT_COLORS.includes(projectColor) ? '' : ' on'}`, title: 'Pick any color', style: PROJECT_COLORS.includes(projectColor) ? '' : `background:${projectColor}` }, custom, '+'));
}

pf.addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const p = editingProject;
  const body = { name: pf.name.value.trim(), color: projectColor };
  if (p) body.archived = pf.archived.checked;
  const res = await run(() => (p ? api('PATCH', `/api/projects/${p.id}`, body) : api('POST', '/api/projects', body)), $('#project-error'));
  if (!res) return;
  projectDlg.close();
  toast(p ? 'Project saved' : 'Project added');
  await loadProjects();
  renderProjects();
  updateTimerBar();
});

$('#project-delete').addEventListener('click', async () => {
  const p = editingProject;
  if (!p) return;
  const todos = p.open_tasks ? ` Its ${p.open_tasks} open to-do${p.open_tasks === 1 ? ' is' : 's are'} deleted too.` : '';
  if (!confirm(`Delete “${p.name}”?${todos} Logged time and plans stay, without a project. Archiving keeps everything.`)) return;
  if (!await run(() => api('DELETE', `/api/projects/${p.id}`))) return;
  projectDlg.close();
  toast('Project deleted');
  await Promise.all([loadProjects(), loadTasks()]);
  renderProjects();
});

// --- reports

$$('#r-preset button').forEach((b) => b.addEventListener('click', () => {
  W.report = { preset: b.dataset.p, anchor: todayISO() };
  loadReport();
}));
$('#r-prev').addEventListener('click', () => shiftReport(-1));
$('#r-next').addEventListener('click', () => shiftReport(1));

function reportRange() {
  const { preset, anchor } = W.report;
  if (preset === 'day') return [anchor, addDays(anchor, 1)];
  if (preset === 'week') { const a = startOfWeek(anchor); return [a, addDays(a, 7)]; }
  if (preset === 'month') { const a = monthStart(anchor); return [a, addMonths(a, 1)]; }
  const a = yearStart(anchor);
  return [a, addYears(a, 1)];
}

function shiftReport(k) {
  const { preset, anchor } = W.report;
  W.report.anchor = preset === 'day' ? addDays(anchor, k) : preset === 'week' ? addDays(anchor, 7 * k)
    : preset === 'month' ? addMonths(monthStart(anchor), k) : addYears(anchor, k);
  loadReport();
}

function reportLabel(a, b) {
  const { preset } = W.report;
  if (preset === 'day') return relDay(a);
  if (preset === 'week') {
    const label = `${shortDay(a)} – ${shortDay(addDays(b, -1))}`;
    return isJalali() ? [fa(label)] : [label];
  }
  if (preset === 'month') return isJalali() ? [fa(monthTitle(a))] : [monthTitle(a)];
  return isJalali() ? [fa(faDigits(Jalali.fromDate(dateOf(a)).jy))] : [String(dateOf(a).getFullYear())];
}

let reportSeq = 0;
async function loadReport() {
  const [a, b] = reportRange();
  $$('#r-preset button').forEach((x) => x.classList.toggle('on', x.dataset.p === W.report.preset));
  $('#r-label').replaceChildren(...reportLabel(a, b));
  const from = dayTs(a);
  const to = dayTs(b);
  const tz = -new Date().getTimezoneOffset();
  $('#r-csv').href = `/api/export?type=time&from=${from}&to=${to}&tz=${tz}`;
  const seq = ++reportSeq;
  const [ent, plan] = await Promise.all([
    run(() => api('GET', `/api/time/entries?from=${from}&to=${to}`)),
    run(() => api('GET', `/api/plan?from=${from}&to=${to}`)),
  ]);
  if (!ent || !plan || seq !== reportSeq) return;
  setSkew(ent.now);
  if (!W.P.size && W.projects.length === 0) await loadProjects();
  W.reportData = { a, b, from, to, entries: ent.entries, blocks: plan.blocks };
  renderReport();
}

function renderReport() {
  const R = W.reportData;
  if (!R || $('#time-reports').hidden) return;
  const byProj = new Map();
  const slot = (pid) => {
    const k = pid ?? 0;
    if (!byProj.has(k)) byProj.set(k, { pid, secs: 0, planned: 0, tasks: new Map() });
    return byProj.get(k);
  };
  let total = 0;
  const days = new Set();
  for (const e of R.entries) {
    const s = overlap(e, R.from, R.to);
    if (!s) continue;
    total += s;
    const p = slot(e.project_id);
    p.secs += s;
    const tk = e.task_id ?? 0;
    p.tasks.set(tk, (p.tasks.get(tk) || 0) + s);
    for (let d = isoOfTs(Math.max(e.start_ts, R.from)); dayTs(d) < Math.min(endOf(e), R.to); d = addDays(d, 1)) days.add(d);
  }
  let planned = 0;
  for (const bl of R.blocks) {
    const s = spanIn(bl.start_ts, bl.end_ts, R.from, R.to);
    planned += s;
    slot(bl.project_id).planned += s;
  }
  const elapsed = Math.max(1, Math.min(daysBetween(R.a, R.b), daysBetween(R.a, todayISO()) + 1));
  $('#r-tiles').replaceChildren(
    tile('Total', fmt1(total / 3600), 'h', `${R.entries.length} entr${R.entries.length === 1 ? 'y' : 'ies'}`),
    tile('Average', fmt1(days.size ? total / 3600 / days.size : 0), 'h / day', 'On days you worked'),
    tile('Days worked', `${days.size}`, `/ ${elapsed}`, W.prefs.work_goal ? `Goal ${fmt1(W.prefs.work_goal)} h a day` : ''),
    tile('Planned', fmt1(planned / 3600), 'h', planned ? `${Math.round((total / planned) * 100)}% of it logged` : 'Nothing planned'));

  const buckets = reportBuckets(R);
  for (const e of R.entries) {
    for (const bk of buckets) {
      const s = overlap(e, bk.start, bk.end);
      if (s) bk.parts.set(e.project_id ?? 0, (bk.parts.get(e.project_id ?? 0) || 0) + s);
    }
  }
  $('#r-chart-card').hidden = !total;
  $('#r-chart-sub').textContent = { day: 'Hours per hour of the day.', week: 'Hours per day.', month: 'Hours per day.', year: 'Hours per month.' }[W.report.preset];
  drawStackChart($('#r-chart'), buckets);

  const rows = [...byProj.values()].sort((x, y) => y.secs - x.secs || y.planned - x.planned);
  const max = Math.max(1, ...rows.map((r) => Math.max(r.secs, r.planned)));
  $('#r-projects').replaceChildren(...(rows.length ? rows.map((r) => {
    const color = projColor(r.pid);
    const val = [`${hrs(r.secs)}`, total ? ` · ${Math.round((r.secs / total) * 100)}%` : ''];
    if (r.planned) val.push(` · ${fmt1(r.planned / 3600)} h planned`);
    const tasks = [...r.tasks.entries()].sort((x, y) => y[1] - x[1]);
    const summary = el('summary', { class: 'hbar-row' },
      el('span', { class: 'name' }, el('i', { class: 'dot', style: `background:${color}` }), el('bdi', {}, projName(r.pid))),
      el('span', { class: 'val' }, val),
      el('div', { class: 'track' },
        r.planned ? el('div', { class: 'plan-mark', style: `left:${(r.planned / max) * 100}%` }) : null,
        el('div', { style: `width:${(r.secs / max) * 100}%;background:${color}` })));
    return el('details', { class: 'proj-report' }, summary,
      tasks.length ? el('ul', { class: 'task-split' }, tasks.map(([tid, s]) => el('li', {},
        el('span', { dir: 'auto' }, tid ? (W.T.get(tid)?.title || 'Finished or deleted task') : 'No task'),
        el('span', { class: 'num' }, hrs(s))))) : el('p', { class: 'hint' }, 'Only planned, nothing logged yet.'));
  }) : [el('p', { class: 'chart-empty' }, 'Nothing logged or planned in this period.')]));
}

function reportBuckets(R) {
  const out = [];
  const preset = W.report.preset;
  if (preset === 'day') {
    for (let h = 0; h < 24; h++) {
      const d = dateOf(R.a);
      const s = tsOf(new Date(d.getFullYear(), d.getMonth(), d.getDate(), h));
      out.push({ start: s, end: s + 3600, label: String(h), title: `${pad(h)}:00–${pad(h + 1)}:00`, parts: new Map() });
    }
  } else if (preset === 'year') {
    for (let i = 0; i < 12; i++) {
      const m = addMonths(R.a, i);
      out.push({ start: dayTs(m), end: dayTs(addMonths(R.a, i + 1)), label: monthTitle(m).split(' ')[0].slice(0, 3), title: monthTitle(m), parts: new Map() });
    }
  } else {
    for (let d = R.a; d < R.b; d = addDays(d, 1)) {
      out.push({
        start: dayTs(d), end: dayTs(addDays(d, 1)), label: preset === 'week' ? weekdayShort(d) : dayNum(d),
        title: isJalali() ? shortDay(d) : longDate(d), parts: new Map(),
      });
    }
  }
  return out;
}

/** Stacked columns of hours, one color per project. */
function drawStackChart(box, buckets) {
  const n = buckets.length;
  const totalOf = (b) => [...b.parts.values()].reduce((s, v) => s + v, 0);
  if (!buckets.some(totalOf)) { box.replaceChildren(); return; }
  const W_ = Math.max(280, box.clientWidth || 320);
  const H = 190;
  const m = { l: 36, r: 8, t: 12, b: 24 };
  const iw = W_ - m.l - m.r;
  const ih = H - m.t - m.b;
  const maxH = Math.max(0.25, ...buckets.map((b) => totalOf(b) / 3600));
  const step = niceStep(maxH * 1.08);
  const top = Math.ceil((maxH * 1.08) / step) * step;
  const y = (h) => m.t + ih - (h / top) * ih;
  const slot = iw / n;
  const bw = Math.max(1, Math.min(24, slot * 0.7, slot - 2));
  const g = [];
  for (let v = 0; v <= top + 1e-9; v += step) {
    g.push(svg('line', { class: v === 0 ? 'base' : 'grid', x1: m.l, x2: W_ - m.r, y1: y(v), y2: y(v) }));
    g.push(svg('text', { x: m.l - 6, y: y(v) + 4, 'text-anchor': 'end' }, `${fmt1(v)} h`));
  }
  const every = n <= 12 ? 1 : n <= 24 ? 3 : 5;
  buckets.forEach((b, i) => {
    if (i % every) return;
    g.push(svg('text', { x: m.l + slot * i + slot / 2, y: H - 6, 'text-anchor': 'middle' }, b.label));
  });
  const hoverCol = svg('rect', { class: 'hover-col', y: m.t, height: ih, width: slot, visibility: 'hidden' });
  g.push(hoverCol);
  buckets.forEach((b, i) => {
    const x0 = m.l + slot * i + (slot - bw) / 2;
    const parts = [...b.parts.entries()].filter(([, v]) => v > 0);
    let acc = 0;
    parts.forEach(([pid, v], j) => {
      const y1 = y(acc / 3600);
      acc += v;
      const y0 = y(acc / 3600);
      const h = Math.max(1, y1 - y0 - (j > 0 ? 2 : 0));
      // Set the color through the DOM: a style="" attribute would be blocked by the page's CSP.
      const bar = j === parts.length - 1
        ? svg('path', { class: 'bar', d: colPath(x0, y0, bw, h) })
        : svg('rect', { class: 'bar', x: x0, y: y0, width: bw, height: h });
      bar.style.fill = projColor(pid || null);
      g.push(bar);
    });
  });
  const hit = svg('rect', { class: 'hit', x: m.l, y: m.t, width: iw, height: ih });
  const onMove = (evt) => {
    const pt = evt.touches ? evt.touches[0] : evt;
    const rect = hit.getBoundingClientRect();
    const i = Math.max(0, Math.min(n - 1, Math.floor(((pt.clientX - rect.left) / rect.width) * n)));
    const b = buckets[i];
    hoverCol.setAttribute('x', m.l + slot * i);
    hoverCol.setAttribute('visibility', 'visible');
    const lines = [el('b', {}, b.title), el('br')];
    const parts = [...b.parts.entries()].sort((x, z) => z[1] - x[1]);
    if (!parts.length) lines.push('Nothing logged');
    for (const [pid, v] of parts) lines.push(`${projName(pid || null)}: ${hrs(v)}`, el('br'));
    showTip(evt, lines);
  };
  const onLeave = () => { hoverCol.setAttribute('visibility', 'hidden'); hideTip(); };
  hit.addEventListener('mousemove', onMove);
  hit.addEventListener('touchstart', onMove, { passive: true });
  hit.addEventListener('touchmove', onMove, { passive: true });
  hit.addEventListener('mouseleave', onLeave);
  hit.addEventListener('touchend', () => setTimeout(onLeave, 1500));
  g.push(hit);
  box.replaceChildren(svg('svg', { viewBox: `0 0 ${W_} ${H}`, height: H, role: 'img', 'aria-label': 'Hours chart' }, g));
}

window.addEventListener('resize', debounce(() => {
  if (state.view === 'time' && W.timeTab === 'reports' && W.reportData) renderReport();
}, 150));

// ---------------------------------------------------------------- Plan tab

function showPlan(sub) {
  W.planTab = sub === 'todo' ? 'todo' : 'schedule';
  $$('#plan-seg button').forEach((b) => b.classList.toggle('on', b.dataset.sub === W.planTab));
  $('#plan-schedule').hidden = W.planTab !== 'schedule';
  $('#plan-todo').hidden = W.planTab !== 'todo';
  if (W.planTab === 'schedule') loadPlanDay();
  else loadTodoView();
}
$$('#plan-seg button').forEach((b) => b.addEventListener('click', () => {
  location.hash = b.dataset.sub === 'todo' ? '#plan/todo' : '#plan';
}));

// --- schedule

function setPlanDate(iso) {
  W.planDate = iso;
  loadPlanDay();
}
$('#pl-prev').addEventListener('click', () => setPlanDate(addDays(W.planDate, -1)));
$('#pl-next').addEventListener('click', () => setPlanDate(addDays(W.planDate, 1)));
$('#pl-date').addEventListener('change', (e) => { if (e.target.value) setPlanDate(e.target.value); });
$('#pl-add').addEventListener('click', () => openBlock(null, {}));

let planSeq = 0;
async function loadPlanDay() {
  const w0 = startOfWeek(W.planDate);
  const from = dayTs(w0);
  const to = dayTs(addDays(w0, 7));
  if (!W.week || W.week.w0 !== w0) W.week = { w0, from, to, blocks: [], entries: [], google: [], gError: null, googleOn: false, loading: true };
  renderPlan();
  const seq = ++planSeq;
  const [plan, ent] = await Promise.all([
    run(() => api('GET', `/api/plan?from=${from}&to=${to}`)),
    run(() => api('GET', `/api/time/entries?from=${from}&to=${to}`)),
    W.tasks.length ? null : loadTasks(),
  ]);
  if (!plan || !ent || seq !== planSeq) return;
  setSkew(ent.now);
  Object.assign(W.week, { blocks: plan.blocks, entries: ent.entries, googleOn: plan.google, loading: false });
  W.running = ent.running;
  updateTimerBar();
  renderPlan();
  if (plan.google) loadGoogleEvents(seq, w0, from, to);
}

async function loadGoogleEvents(seq, w0, from, to) {
  let res;
  try {
    res = await api('GET', `/api/google/events?from=${from}&to=${to}`);
  } catch (err) {
    res = { events: [], error: err.message };
  }
  if (!W.week || W.week.w0 !== w0) return;
  W.week.google = res.events || [];
  W.week.gError = res.error || null;
  if (res.changed) { // moved or deleted in Google: get the blocks again
    const plan = await run(() => api('GET', `/api/plan?from=${from}&to=${to}`));
    if (plan && W.week && W.week.w0 === w0) W.week.blocks = plan.blocks;
  }
  if (seq === planSeq || res.changed) renderPlan();
}

function renderPlan() {
  if ($('#plan-schedule').hidden || !W.week) return;
  const day = W.planDate;
  const d0 = dayTs(day);
  const d1 = dayTs(addDays(day, 1));
  $('#pl-label').replaceChildren(...relDay(day));
  $('#pl-date').value = day;
  const wk = W.week;

  // Week strip with planned hours per day
  const strip = [];
  for (let i = 0; i < 7; i++) {
    const d = addDays(wk.w0, i);
    const a = dayTs(d);
    const b = dayTs(addDays(d, 1));
    const planned = wk.blocks.reduce((s, x) => s + spanIn(x.start_ts, x.end_ts, a, b), 0);
    const logged = wk.entries.reduce((s, x) => s + overlap(x, a, b), 0);
    strip.push(el('button', {
      type: 'button', class: `wk-day${d === day ? ' on' : ''}${d === todayISO() ? ' today' : ''}`,
      onclick: () => setPlanDate(d), 'aria-label': `${longDate(d)}: ${fmt1(planned / 3600)} h planned`,
    }, el('span', { class: 'wk-name' }, weekdayShort(d)), el('span', { class: 'wk-num' }, dayNum(d)),
    el('span', { class: 'wk-hours' }, planned ? `${fmt1(planned / 3600)}h` : logged ? '·' : '')));
  }
  $('#pl-week').replaceChildren(...strip);

  const blocks = wk.blocks.filter((x) => x.end_ts > d0 && x.start_ts < d1);
  const entries = wk.entries.filter((x) => endOf(x) > d0 && x.start_ts < d1);
  const planned = blocks.reduce((s, x) => s + spanIn(x.start_ts, x.end_ts, d0, d1), 0);
  const logged = entries.reduce((s, x) => s + overlap(x, d0, d1), 0);
  const sum = [el('span', {}, 'Planned ', el('b', {}, hrs(planned))), el('span', {}, 'Logged ', el('b', {}, hrs(logged)))];
  if (W.prefs.work_goal) sum.push(el('span', { class: 'muted' }, `Goal ${fmt1(W.prefs.work_goal)} h`));
  $('#pl-sum').replaceChildren(...sum);

  // All-day row: Google all-day events and to-dos due that day
  const allDay = [];
  for (const g of wk.google) {
    if (g.all_day && g.start_date <= day && day < g.end_date) {
      allDay.push(el('a', { class: 'chip-gcal', href: g.link || '#', target: '_blank', rel: 'noopener', style: `--c:${g.color}`, dir: 'auto' }, g.title));
    }
  }
  for (const t of W.tasks) {
    if (!t.done && t.due === day) {
      allDay.push(el('button', { type: 'button', class: 'chip-task', dir: 'auto', onclick: () => openTask(t), style: `--c:${projColor(t.project_id)}` }, `☐ ${t.title}`));
    }
  }
  if (wk.gError) allDay.push(el('span', { class: 'chip-err' }, `Google: ${wk.gError}`));
  $('#pl-allday').replaceChildren(...allDay);
  $('#pl-allday').hidden = !allDay.length;

  renderTimeline(day, d0, d1, blocks, entries);
}

function layoutLanes(items) {
  items.sort((a, b) => a.s - b.s || b.e - a.e);
  let cluster = [];
  let clusterEnd = -Infinity;
  const flush = () => {
    const lanes = [];
    for (const it of cluster) {
      let i = lanes.findIndex((end) => end <= it.s);
      if (i < 0) { i = lanes.length; lanes.push(0); }
      lanes[i] = it.e;
      it.lane = i;
    }
    for (const it of cluster) it.lanes = lanes.length;
    cluster = [];
  };
  for (const it of items) {
    if (it.s >= clusterEnd) { flush(); clusterEnd = -Infinity; }
    cluster.push(it);
    clusterEnd = Math.max(clusterEnd, it.e);
  }
  flush();
  return items;
}

const SYNC_MARK = { synced: '✓', pending: '↻', error: '!' };

function renderTimeline(day, d0, d1, blocks, entries) {
  const box = $('#pl-timeline');
  const px = (ts) => ((Math.max(d0, Math.min(d1, ts)) - d0) / 3600) * HOUR_PX;
  const kids = [];
  for (let h = 0; h < 24; h++) {
    kids.push(el('div', { class: 'tl-hour', style: `top:${h * HOUR_PX}px` }, el('span', {}, hmm(h * 60))));
  }
  const items = [];
  for (const b of blocks) items.push({ kind: 'block', ref: b, s: Math.max(b.start_ts, d0), e: Math.min(b.end_ts, d1) });
  for (const g of W.week.google) {
    if (!g.all_day && g.end_ts > d0 && g.start_ts < d1) items.push({ kind: 'gcal', ref: g, s: Math.max(g.start_ts, d0), e: Math.min(g.end_ts, d1) });
  }
  for (const it of layoutLanes(items)) {
    const top = px(it.s);
    const height = Math.max(18, px(it.e) - top - 2);
    const left = `calc(52px + (100% - 74px) * ${it.lane} / ${it.lanes})`;
    const width = `calc((100% - 74px) / ${it.lanes} - 3px)`;
    const short = height < 34;
    if (it.kind === 'block') {
      const b = it.ref;
      const t = W.T.get(b.task_id);
      const title = b.title || (t ? t.title : projName(b.project_id));
      kids.push(el('button', {
        type: 'button', class: `blk${short ? ' short' : ''}`, dir: 'auto',
        style: `top:${top}px;height:${height}px;left:${left};width:${width};--c:${projColor(b.project_id)}`,
        onclick: (ev) => { ev.stopPropagation(); openBlock(b); },
      }, el('span', { class: 'blk-title' }, title),
      el('span', { class: 'blk-time' }, `${hm(b.start_ts)}–${hm(b.end_ts)}`,
        b.gcal ? el('span', { class: `blk-sync ${b.gcal}`, title: b.gcal === 'error' ? `Google: ${b.gcal_error}` : `Google Calendar: ${b.gcal}` }, ` ${SYNC_MARK[b.gcal]}`) : null)));
    } else {
      const g = it.ref;
      kids.push(el('a', {
        class: `blk gcal${short ? ' short' : ''}`, href: g.link || '#', target: '_blank', rel: 'noopener', dir: 'auto',
        title: `${g.calendar}: ${g.title}`, style: `top:${top}px;height:${height}px;left:${left};width:${width};--c:${g.color}`,
        onclick: (ev) => ev.stopPropagation(),
      }, el('span', { class: 'blk-title' }, g.title), el('span', { class: 'blk-time' }, `${hm(g.start_ts)}–${hm(g.end_ts)} · ${g.calendar}`)));
    }
  }
  // What you actually logged, as thin bars on the right
  for (const e of entries) {
    const top = px(e.start_ts);
    const t = W.T.get(e.task_id);
    kids.push(el('div', {
      class: `actual${e.end_ts == null ? ' running' : ''}`,
      style: `top:${top}px;height:${Math.max(3, px(endOf(e)) - top)}px;background:${projColor(e.project_id)}`,
      title: `${e.description || (t ? t.title : projName(e.project_id))} · ${hm(e.start_ts)}–${e.end_ts ? hm(e.end_ts) : 'now'}`,
    }));
  }
  kids.push(el('div', { class: 'now-line', id: 'pl-now', hidden: true }));
  box.style.height = `${24 * HOUR_PX}px`;
  box.replaceChildren(...kids);
  placeNowLine();
  if (W.scrolledFor !== day) {
    W.scrolledFor = day;
    const first = blocks.length ? Math.min(...blocks.map((b) => px(b.start_ts))) : Infinity;
    const nowY = day === todayISO() ? px(nowS()) - HOUR_PX : Infinity;
    const target = Math.min(first - 12, nowY, 7 * HOUR_PX);
    $('#pl-scroll').scrollTop = Math.max(0, Number.isFinite(target) ? target : 7 * HOUR_PX);
  }
}

function placeNowLine() {
  const line = $('#pl-now');
  if (!line) return;
  const day = W.planDate;
  line.hidden = day !== todayISO();
  if (!line.hidden) line.style.top = `${((nowS() - dayTs(day)) / 3600) * HOUR_PX}px`;
}

$('#pl-timeline').addEventListener('click', (ev) => {
  if (ev.target.closest('.blk')) return;
  const rect = $('#pl-timeline').getBoundingClientRect();
  const min = Math.floor((((ev.clientY - rect.top) / HOUR_PX) * 60) / 30) * 30;
  openBlock(null, { startMin: Math.max(0, Math.min(23 * 60 + 30, min)) });
});

// --- block dialog

const blockDlg = $('#block-dlg');
const bf = $('#block-form');
let editingBlock = null;
wirePT(bf.proj, bf.task);

function nextFreeStart(date) {
  if (date !== todayISO()) return 9 * 60;
  const n = new Date();
  return Math.min(23 * 60, Math.ceil((n.getHours() * 60 + n.getMinutes() + 1) / 30) * 30);
}

function openBlock(b, defaults = {}) {
  editingBlock = b || null;
  bf.reset();
  $('#block-error').textContent = '';
  const date = b ? isoOfTs(b.start_ts) : (defaults.date || W.planDate);
  let start;
  let end;
  if (b) {
    start = hm(b.start_ts);
    end = hm(b.end_ts);
  } else {
    const s = defaults.startMin ?? nextFreeStart(date);
    start = hmm(s);
    end = hmm(Math.min(s + (defaults.minutes || 60), 24 * 60 - 1));
  }
  bf.title.value = b ? b.title : (defaults.title || '');
  fillPT(bf.proj, bf.task, b ? b.project_id : (defaults.project_id ?? null), b ? b.task_id : (defaults.task_id ?? null));
  bf.date.value = date;
  bf.start.value = start;
  bf.end.value = end;
  bf.notes.value = b ? b.notes : '';
  $('#block-repeat-row').hidden = !!b;
  $('#block-count-row').hidden = true;
  $('#block-title').textContent = b ? 'Planned time' : 'Plan time';
  $('#block-delete').hidden = !b;
  $('#block-delete').textContent = 'Delete';
  $('#block-start').hidden = !b;
  $('#block-save').textContent = b ? 'Save' : 'Add';
  blockHint();
  blockDlg.showModal();
}

function blockHint() {
  const parts = [spanHint(spanFromForm(bf))];
  const b = editingBlock;
  if (b && b.gcal) {
    parts.push({ synced: 'In Google Calendar.', pending: 'Sending to Google Calendar…', error: `Google Calendar: ${b.gcal_error}` }[b.gcal]);
  }
  if (b && b.series) parts.push('Part of a repeating plan.');
  $('#block-hint').textContent = parts.filter(Boolean).join(' · ');
}
bf.addEventListener('input', blockHint);
bf.repeat.addEventListener('change', () => { $('#block-count-row').hidden = !bf.repeat.value; });

/** Start times of the repeats after the first one. Work days skip Friday with Shamsi dates, else the weekend. */
function repeatStarts(first, freq, count) {
  const out = [];
  const isWorkDay = (d) => (isJalali() ? d.getDay() !== 5 : d.getDay() !== 0 && d.getDay() !== 6);
  const d = new Date(first);
  while (out.length < count - 1) {
    d.setDate(d.getDate() + (freq === 'weekly' ? 7 : 1));
    if (freq === 'weekdays' && !isWorkDay(d)) continue;
    out.push(tsOf(d));
  }
  return out;
}

bf.addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const err = $('#block-error');
  const span = spanFromForm(bf);
  if (!span) { err.textContent = 'Enter the date, start and end.'; return; }
  const [a, b] = span;
  const body = {
    title: bf.title.value.trim(), project_id: selVal(bf.proj), task_id: selVal(bf.task), notes: bf.notes.value.trim(),
    start_ts: tsOf(a), end_ts: tsOf(b),
  };
  const blk = editingBlock;
  if (!blk && bf.repeat.value) {
    const count = Math.round(toNum(bf.count.value));
    if (!(count >= 2 && count <= 60)) { err.textContent = 'Repeat between 2 and 60 times.'; return; }
    body.repeat_starts = repeatStarts(a, bf.repeat.value, count);
  }
  const res = await run(() => (blk ? api('PATCH', `/api/plan/${blk.id}`, body) : api('POST', '/api/plan', body)), err);
  if (!res) return;
  blockDlg.close();
  toast(blk ? 'Saved' : res.ids.length > 1 ? `Planned ${res.ids.length} times` : 'Planned');
  if (!blk) W.planDate = isoOf(a);
  if (state.view === 'plan') loadPlanDay();
});

$('#block-start').addEventListener('click', () => {
  const b = editingBlock;
  if (!b) return;
  blockDlg.close();
  startTimer({ description: b.title, project_id: b.project_id, task_id: b.task_id });
});

$('#block-delete').addEventListener('click', async (ev) => {
  const b = editingBlock;
  if (!b) return;
  const btn = ev.currentTarget;
  let following = false;
  if (b.series && btn.dataset.ask !== '1') {
    // Ask once: just this one, or this and the later repeats?
    btn.dataset.ask = '1';
    btn.textContent = 'Only this one';
    const all = el('button', { type: 'button', class: 'danger', id: 'block-delete-all' }, 'This and later ones');
    all.addEventListener('click', () => deleteBlock(b, true));
    btn.after(all);
    return;
  }
  delete btn.dataset.ask;
  deleteBlock(b, following);
});

async function deleteBlock(b, following) {
  const res = await run(() => api('DELETE', `/api/plan/${b.id}${following ? '?following=1' : ''}`));
  $('#block-delete-all')?.remove();
  delete $('#block-delete').dataset.ask;
  if (!res) return;
  blockDlg.close();
  toast(res.deleted > 1 ? `Deleted ${res.deleted} planned blocks` : 'Deleted');
  loadPlanDay();
}
blockDlg.addEventListener('close', () => {
  $('#block-delete-all')?.remove();
  delete $('#block-delete').dataset.ask;
});

// --- to-dos

async function loadTodoView() {
  renderTodos();
  await Promise.all([loadTasks(), loadProjects()]);
  renderTodos();
}

function filterPid() {
  return W.todoFilter.startsWith('p') ? Number(W.todoFilter.slice(1)) : null;
}

function dueText(due) {
  const diff = daysBetween(todayISO(), due);
  if (diff === 0) return 'Today';
  if (diff === 1) return 'Tomorrow';
  if (diff === -1) return 'Yesterday';
  if (diff < 0) return `${-diff} days late`;
  if (diff < 7) return isJalali() ? WD_FA[dateOf(due).getDay()] : dateOf(due).toLocaleDateString('en-US', { weekday: 'long' });
  return shortDay(due);
}

function renderTodos() {
  if ($('#plan-todo').hidden) return;
  const fsel = $('#todo-filter');
  const pid = filterPid();
  fsel.replaceChildren(el('option', { value: 'all' }, 'All to-dos'), el('option', { value: 'none' }, 'Without a project'),
    ...W.projects.filter((p) => !p.archived || p.id === pid).map((p) => el('option', { value: `p${p.id}` }, p.name)));
  fsel.value = W.todoFilter;
  if (fsel.value !== W.todoFilter) { W.todoFilter = 'all'; fsel.value = 'all'; }
  const af = $('#todo-form');
  if (document.activeElement !== af.project) {
    af.project.replaceChildren(...projectOptions(filterPid()));
    af.project.value = filterPid() == null ? '' : String(filterPid());
  }
  const list = W.tasks.filter((t) => W.todoFilter === 'all' || (W.todoFilter === 'none' ? t.project_id == null : t.project_id === filterPid()));
  const today = todayISO();
  const open = list.filter((t) => !t.done);
  const groups = [
    ['Overdue', open.filter((t) => t.due && t.due < today), 'late'],
    ['Today', open.filter((t) => t.due === today)],
    ['Upcoming', open.filter((t) => t.due && t.due > today)],
    ['Someday', open.filter((t) => !t.due).sort((a, b) => b.priority - a.priority || a.id - b.id)],
    ['Done recently', list.filter((t) => t.done).sort((a, b) => b.done_at - a.done_at), 'done'],
  ];
  const out = groups.filter(([, items]) => items.length).map(([name, items, cls]) => el('div', { class: `card meal todo-group ${cls || ''}` },
    el('div', { class: 'meal-head' }, el('h3', {}, name), el('span', { class: 'meal-kcal' }, String(items.length))),
    el('ul', { class: 'entries' }, items.map(taskRow))));
  $('#todo-groups').replaceChildren(...(out.length ? out
    : [el('p', { class: 'chart-empty' }, list.length ? 'All done.' : 'Nothing to do yet. Add your first to-do above.')]));
}

const CAL_ICON = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 5h16v15H4zM4 9h16M8 3v4M16 3v4"/></svg>';

function taskRow(t) {
  const p = projOf(t.project_id);
  const sub = [];
  if (p) sub.push(el('span', { class: 'pname' }, el('i', { class: 'dot', style: `background:${p.color}` }), el('bdi', {}, p.name)));
  if (t.due) sub.push(el('span', { class: !t.done && t.due < todayISO() ? 'late' : '' }, dueText(t.due)));
  if (t.priority) sub.push(el('span', { class: `prio p${t.priority}` }, `${PRIORITY[t.priority]} priority`));
  if (t.remind_at && !t.done && t.remind_at > nowS()) sub.push(el('span', { class: 'remind' }, `⏰ ${remindText(t.remind_at)}`));
  if (t.total) sub.push(el('span', {}, `${hrs(t.total)} logged`));
  if (t.notes) sub.push(el('span', { title: t.notes }, '✎ notes'));
  const plan = el('button', { class: 'icon small', type: 'button', title: 'Plan time for it', 'aria-label': `Plan time for ${t.title}`, onclick: () => scheduleTask(t) });
  plan.innerHTML = CAL_ICON;
  return el('li', { class: `todo${t.done ? ' done' : ''}` },
    el('button', {
      class: 'tick', type: 'button', 'aria-pressed': String(t.done),
      'aria-label': t.done ? `Mark “${t.title}” as not done` : `Mark “${t.title}” as done`, onclick: () => toggleTask(t),
    }),
    el('button', { class: 'todo-main', type: 'button', onclick: () => openTask(t) },
      el('span', { class: 'todo-title', dir: 'auto' }, t.title),
      sub.length ? el('span', { class: 'todo-sub' }, sub.flatMap((x, i) => (i ? [' · ', x] : [x]))) : null),
    t.done ? null : plan,
    t.done ? null : el('button', {
      class: 'icon small play', type: 'button', title: 'Start timer', 'aria-label': `Start timer on ${t.title}`,
      onclick: () => startTimer({ description: '', project_id: t.project_id, task_id: t.id }),
    }, '▶'));
}

function remindText(ts) {
  const iso = isoOfTs(ts);
  const diff = daysBetween(todayISO(), iso);
  if (diff === 0) return hm(ts);
  if (diff === 1) return `Tomorrow ${hm(ts)}`;
  return `${shortDay(iso)} ${hm(ts)}`;
}

async function toggleTask(t) {
  t.done = !t.done;
  t.done_at = t.done ? nowS() : null;
  renderTodos();
  const res = await run(() => api('PATCH', `/api/tasks/${t.id}`, { done: t.done }));
  if (!res) { t.done = !t.done; renderTodos(); return; }
  Object.assign(t, res);
  if (t.done) toast(`Done: ${t.title}`);
}

function scheduleTask(t) {
  const today = todayISO();
  W.planDate = t.due && t.due >= today ? t.due : today;
  location.hash = '#plan';
  openBlock(null, { project_id: t.project_id, task_id: t.id, date: W.planDate });
}

$('#todo-filter').addEventListener('change', (e) => { W.todoFilter = e.target.value; renderTodos(); });

$('#todo-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = e.target;
  const body = { title: f.title.value.trim(), due: f.due.value || null, project_id: selVal(f.project) };
  if (!body.title) return;
  const res = await run(() => api('POST', '/api/tasks', body));
  if (!res) return;
  f.title.value = '';
  W.tasks.push(res);
  W.T.set(res.id, res);
  renderTodos();
  f.title.focus();
});

// --- to-do dialog

const taskDlg = $('#task-dlg');
const tf = $('#task-form');
let editingTask = null;

function openTask(t) {
  editingTask = t || null;
  tf.reset();
  $('#task-error').textContent = '';
  tf.title.value = t ? t.title : '';
  tf.proj.replaceChildren(...projectOptions(t ? t.project_id : filterPid()));
  tf.proj.value = t && t.project_id != null ? String(t.project_id) : '';
  tf.due.value = t && t.due ? t.due : '';
  tf.priority.value = String(t ? t.priority : 0);
  tf.remind.value = t && t.remind_at ? localDT(t.remind_at) : '';
  updateRemindHint();
  if (!W.telegram) loadTelegram().then(updateRemindHint);
  tf.notes.value = t ? t.notes : '';
  tf.done.checked = !!(t && t.done);
  $('#task-title').textContent = t ? 'To-do' : 'New to-do';
  $('#task-delete').hidden = !t;
  $('#task-schedule').hidden = !t || t.done;
  $('#task-stats').textContent = t && t.total ? `${hrs(t.total)} logged on it.` : '';
  taskDlg.showModal();
}

tf.addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const t = editingTask;
  const body = {
    title: tf.title.value.trim(), project_id: selVal(tf.proj), due: tf.due.value || null,
    priority: Number(tf.priority.value), notes: tf.notes.value, done: tf.done.checked,
    remind_at: tsOfLocalDT(tf.remind.value),
  };
  const res = await run(() => (t ? api('PATCH', `/api/tasks/${t.id}`, body) : api('POST', '/api/tasks', body)), $('#task-error'));
  if (!res) return;
  taskDlg.close();
  toast('Saved');
  await loadTasks();
  if (state.view === 'plan') showPlan(W.planTab);
});

/** Reminders only arrive once Telegram is linked; say so when one is set. */
function updateRemindHint() {
  const tg = W.telegram;
  $('#task-remind-hint').hidden = !tf.remind.value || !tg || tg.linked;
}
tf.remind.addEventListener('input', updateRemindHint);

$('#task-delete').addEventListener('click', async () => {
  const t = editingTask;
  if (!t || !confirm(`Delete “${t.title}”? Time logged on it stays.`)) return;
  if (!await run(() => api('DELETE', `/api/tasks/${t.id}`))) return;
  taskDlg.close();
  toast('Deleted');
  await loadTasks();
  if (state.view === 'plan') showPlan(W.planTab);
});

$('#task-schedule').addEventListener('click', () => {
  const t = editingTask;
  if (!t) return;
  taskDlg.close();
  scheduleTask(t);
});

// ---------------------------------------------------------------- settings: time, Google Calendar, feed

async function loadWorkSettings() {
  fillPrefsForm();
  $('#export-time').href = `/api/export?type=time&tz=${-new Date().getTimezoneOffset()}`;
  loadPrefs().then(fillPrefsForm);
  renderTelegram();
  loadTelegram();
  renderGoogle();
  loadGoogle();
  loadIcs();
}

function fillPrefsForm() {
  const f = $('#prefs-form');
  if (f.contains(document.activeElement)) return;
  f.work_goal.value = W.prefs.work_goal;
  f.week_start.value = W.prefs.week_start;
  f.calendar.value = W.prefs.calendar;
}

$('#prefs-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = e.target;
  const goal = f.work_goal.value.trim() === '' ? 0 : toNum(f.work_goal.value);
  if (!(goal >= 0 && goal <= 24)) { $('#prefs-error').textContent = 'The work goal is in hours, 0 to 24.'; return; }
  const p = await run(() => api('PUT', '/api/prefs', { work_goal: goal, week_start: f.week_start.value, calendar: f.calendar.value }), $('#prefs-error'));
  if (!p) return;
  W.prefs = p;
  W.week = null;
  toast('Saved');
});

async function loadGoogle() {
  const g = await run(() => api('GET', '/api/google'));
  if (!g) return;
  W.google = g;
  renderGoogle();
  if (g.connected && !g.needs_reauth && !W.calendars) loadCalendars();
}

async function loadCalendars() {
  try {
    W.calendars = (await api('GET', '/api/google/calendars')).calendars;
  } catch (err) {
    W.calendars = null;
    toast(err.message, true);
  }
  renderGoogle();
}

function renderGoogle() {
  const box = $('#google-body');
  const g = W.google;
  if (!g) { box.replaceChildren(el('p', { class: 'muted' }, 'Loading…')); return; }
  if (!g.available) {
    box.replaceChildren(
      el('p', { class: 'sub' }, 'See your planned blocks in Google Calendar, and your Google events in the planner. '
        + 'To turn this on, the server needs a Google sign-in key (GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET). The README explains how to make one.'),
      el('p', { class: 'hint' }, 'Address to register with Google: ', el('code', {}, g.redirect_uri)),
      el('p', { class: 'hint' }, 'Until then, the calendar feed below works with any calendar app.'));
    return;
  }
  if (!g.connected) {
    box.replaceChildren(
      el('p', { class: 'sub' }, 'Send your planned blocks to Google Calendar and see your Google events next to them in the planner.'),
      el('button', { class: 'primary wide', type: 'button', onclick: connectGoogle }, 'Connect Google Calendar'));
    return;
  }
  const kids = [el('p', { class: 'sub' }, 'Connected as ', el('b', {}, g.email || 'your Google account'), '.')];
  if (g.needs_reauth) {
    kids.push(el('p', { class: 'banner warn' }, 'Google stopped accepting this connection (it expired or was removed). Connect again to keep syncing.'),
      el('button', { class: 'primary wide', type: 'button', onclick: connectGoogle }, 'Connect again'));
  } else {
    const sel = el('select', { 'aria-label': 'Calendar for planned blocks' });
    const cals = (W.calendars || []).filter((c) => c.writable);
    if (!cals.some((c) => c.id === g.calendar_id)) cals.unshift({ id: g.calendar_id, name: g.calendar_id });
    sel.replaceChildren(...cals.map((c) => el('option', { value: c.id }, c.primary ? `${c.name} (main)` : c.name)));
    sel.value = g.calendar_id;
    sel.addEventListener('change', async () => {
      const res = await run(() => api('PUT', '/api/google', { calendar_id: sel.value }));
      if (res) { W.google = res; toast('Planned blocks will move to that calendar'); renderGoogle(); } else sel.value = g.calendar_id;
    });
    const show = el('input', { type: 'checkbox', checked: g.show_events });
    show.addEventListener('change', async () => {
      const res = await run(() => api('PUT', '/api/google', { show_events: show.checked }));
      if (res) { W.google = res; if (W.week) W.week.google = []; } else show.checked = !show.checked;
    });
    kids.push(el('label', {}, 'Put planned blocks in', sel),
      el('label', { class: 'check' }, show, ' Show my Google events in the planner'));
    const status = [];
    if (g.last_sync) status.push(`Last synced ${new Date(g.last_sync * 1000).toLocaleString('en-GB', { dateStyle: 'medium', timeStyle: 'short' })}.`);
    if (g.pending) status.push(`${g.pending} change${g.pending === 1 ? '' : 's'} waiting to be sent.`);
    if (status.length) kids.push(el('p', { class: 'hint' }, status.join(' ')));
    if (g.last_error) kids.push(el('p', { class: 'error' }, `Last problem: ${g.last_error}`));
  }
  kids.push(el('div', { class: 'btn-row' },
    g.needs_reauth ? null : el('button', { type: 'button', onclick: syncGoogle }, 'Sync now'),
    el('button', { type: 'button', class: 'danger', onclick: disconnectGoogle }, 'Disconnect')));
  box.replaceChildren(...kids);
}

async function connectGoogle() {
  const r = await run(() => api('POST', '/api/google/connect'));
  if (r) location.href = r.url;
}

async function syncGoogle() {
  const r = await run(() => api('POST', '/api/google/sync'));
  if (!r) { loadGoogle(); return; }
  W.google = r;
  toast(r.pushed ? `Sent ${r.pushed} change${r.pushed === 1 ? '' : 's'} to Google` : 'Everything is up to date');
  renderGoogle();
}

async function disconnectGoogle() {
  if (!confirm('Disconnect Google Calendar? Events already in Google stay there.')) return;
  if (!await run(() => api('DELETE', '/api/google'))) return;
  W.calendars = null;
  toast('Disconnected');
  loadGoogle();
}

async function loadIcs() {
  const r = await run(() => api('GET', '/api/ics'));
  if (r) $('#ics-url').value = r.url;
}

$('#ics-copy').addEventListener('click', async () => {
  const input = $('#ics-url');
  try {
    await navigator.clipboard.writeText(input.value);
    toast('Link copied');
  } catch {
    input.select();
    toast('Select the link and copy it');
  }
});

$('#ics-reset').addEventListener('click', async () => {
  if (!confirm('Make a new link? Calendars subscribed to the old one stop updating.')) return;
  const r = await run(() => api('POST', '/api/ics/reset'));
  if (r) { $('#ics-url').value = r.url; toast('New link made'); }
});

// ---------------------------------------------------------------- settings: Telegram

const TG_LEADS = [[-1, 'Off'], [0, 'When it starts'], [5, '5 minutes before'], [10, '10 minutes before'],
  [15, '15 minutes before'], [30, '30 minutes before']];
const TG_TIMER = [[0, 'Off'], [2, '2 hours'], [3, '3 hours'], [4, '4 hours'], [6, '6 hours'], [8, '8 hours']];
let tgPollTimer = null;

function browserTz() {
  try { return Intl.DateTimeFormat().resolvedOptions().timeZone || undefined; } catch { return undefined; }
}

async function loadTelegram() {
  const t = await run(() => api('GET', '/api/telegram'));
  if (!t) return;
  const changed = JSON.stringify(t) !== JSON.stringify(W.telegram);
  W.telegram = t;
  if (changed) renderTelegram();
}

/** While we wait for the user to press Start in Telegram, check every few seconds. */
function pollTelegramLink() {
  clearTimeout(tgPollTimer);
  const t = W.telegram;
  if (!t || !t.configured || t.linked || state.view !== 'settings') return;
  tgPollTimer = setTimeout(async () => {
    if (!document.hidden) await loadTelegram();
    if (W.telegram && W.telegram.linked) toast('Telegram connected');
    pollTelegramLink();
  }, 3000);
}

function selectOf(list, value, label) {
  const sel = el('select', { 'aria-label': label }, list.map(([v, text]) => el('option', { value: String(v) }, text)));
  sel.value = String(value);
  return sel;
}

/** The bot token and the SOCKS5 proxy. */
function tgBotForm(t) {
  const p = t.proxy || {};
  const token = el('input', {
    name: 'token', autocomplete: 'off', spellcheck: false, dir: 'ltr',
    placeholder: t.configured ? `Saved (${t.token_hint}). Paste a new one to change it.` : '123456789:AAH…',
  });
  const host = el('input', { name: 'host', autocomplete: 'off', spellcheck: false, dir: 'ltr', value: p.host || '', placeholder: '127.0.0.1' });
  const port = el('input', { name: 'port', inputmode: 'numeric', autocomplete: 'off', value: p.port ? String(p.port) : '', placeholder: '1080' });
  const user = el('input', { name: 'user', autocomplete: 'off', spellcheck: false, dir: 'ltr', value: p.username || '' });
  const pass = el('input', { name: 'pass', type: 'password', autocomplete: 'new-password', placeholder: p.has_password ? 'Saved' : '' });
  const err = el('p', { class: 'error', role: 'alert' });
  const save = el('button', { class: 'primary wide' }, 'Save and check');
  const proxy = el('details', { class: 'tg-more', open: !!t.proxy || !t.configured },
    el('summary', {}, 'Proxy (SOCKS5)'),
    el('div', { class: 'form' },
      el('p', { class: 'hint' }, 'If this server can’t reach Telegram directly (for example where it’s blocked), it can go through a SOCKS5 proxy. '
        + 'Leave the address empty to connect directly. You can also paste a whole socks5:// link into Address.'),
      el('div', { class: 'grid2' }, el('label', {}, 'Address', host), el('label', {}, 'Port', port)),
      el('div', { class: 'grid2' }, el('label', {}, 'Username (optional)', user), el('label', {}, 'Password (optional)', pass))));
  const form = el('form', { class: 'form', novalidate: true },
    el('label', {}, 'Bot token', token), proxy, err, save);
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const body = { tz: browserTz() };
    if (token.value.trim()) body.token = token.value.trim();
    if (host.value.trim()) {
      body.proxy = { host: host.value.trim(), port: port.value.trim() || null, username: user.value.trim() };
      // An empty password box keeps the saved password.
      if (pass.value || !p.has_password) body.proxy.password = pass.value;
    } else {
      body.proxy = null;
    }
    save.disabled = true;
    save.textContent = 'Checking…';
    const res = await run(() => api('PUT', '/api/telegram/bot', body), err);
    save.disabled = false;
    save.textContent = 'Save and check';
    if (!res) return;
    W.telegram = res;
    toast(res.linked ? 'Saved' : `Connected to @${res.bot}`);
    renderTelegram();
  });
  return form;
}

/** What to send: morning summary, heads-up, timer nudge, ringing again. */
function tgSendForm(t) {
  const s = t.settings;
  const brief = el('input', { type: 'checkbox', checked: s.brief });
  const briefTime = el('input', { type: 'time', value: s.brief_time, 'aria-label': 'Time of the morning summary' });
  const lead = selectOf(TG_LEADS, s.lead, 'Heads-up before planned time');
  const timer = selectOf(TG_TIMER, s.timer_hours, 'Timer nudge');
  const again = el('input', { type: 'checkbox', checked: s.ring_again });
  const err = el('p', { class: 'error', role: 'alert' });
  const test = el('button', { type: 'button' }, 'Send a test message');
  test.addEventListener('click', async () => {
    test.disabled = true;
    const res = await run(() => api('POST', '/api/telegram/test'), err);
    test.disabled = false;
    if (res) { W.telegram = res; toast('Sent. Check Telegram.'); }
  });
  const form = el('form', { class: 'form', novalidate: true },
    el('div', { class: 'check-time' }, el('label', { class: 'check' }, brief, ' Morning summary at'), briefTime),
    el('p', { class: 'hint' }, 'Today’s plan and the to-dos that are due. Skipped on days with nothing on.'),
    el('label', {}, 'Heads-up before planned time', lead),
    el('label', {}, 'Nudge me when a timer is still running after', timer),
    el('p', { class: 'hint' }, 'Not between 23:00 and 07:00.'),
    el('label', { class: 'check' }, again, ' Ring again after 5 minutes if I don’t answer an alarm'),
    err,
    el('div', { class: 'btn-row' }, el('button', { class: 'primary' }, 'Save'), test));
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const res = await run(() => api('PUT', '/api/telegram', {
      brief: brief.checked, brief_time: briefTime.value || '08:00', lead: Number(lead.value),
      timer_hours: Number(timer.value), ring_again: again.checked, tz: browserTz(),
    }), err);
    if (res) { W.telegram = res; toast('Saved'); }
  });
  return form;
}

function renderTelegram() {
  const box = $('#tg-body');
  const t = W.telegram;
  if (!t) { box.replaceChildren(el('p', { class: 'muted' }, 'Loading…')); return; }
  const kids = [];
  if (!t.configured) {
    kids.push(
      el('p', { class: 'sub' }, 'Get an alarm for each to-do you give a reminder time, a heads-up before planned time, and a short morning summary. '
        + 'You can also add to-dos by messaging the bot.'),
      el('ol', { class: 'steps' },
        el('li', {}, 'In Telegram, open ', el('b', {}, '@BotFather'), ', send ', el('code', {}, '/newbot'), ' and follow the steps.'),
        el('li', {}, 'Copy the token it gives you and paste it here.')),
      tgBotForm(t));
  } else if (!t.linked) {
    kids.push(
      el('p', { class: 'sub' }, 'Bot ', el('b', {}, `@${t.bot}`), ' is ready. Open it in Telegram and press ', el('b', {}, 'Start'),
        ', so it knows where to send your reminders.'),
      el('a', { class: 'btn primary wide', href: t.link_url, target: '_blank', rel: 'noopener' }, `Open @${t.bot} in Telegram`),
      el('p', { class: 'hint' }, 'Or send the bot this message: ', el('code', {}, `/start ${t.link_code}`), '. This page updates by itself.'));
  } else {
    kids.push(el('p', { class: 'sub' }, 'Sending to ', el('b', {}, t.chat || 'your chat'), ' through ', el('b', {}, `@${t.bot}`), '.'),
      tgSendForm(t),
      el('p', { class: 'hint' }, 'Give a to-do a “Remind me” time to get an alarm for it. In the chat, send any text to add a to-do, or use ',
        el('code', {}, '/today'), ', ', el('code', {}, '/todo'), ', ', el('code', {}, '/remind 18:30 Call mom'), ', ',
        el('code', {}, '/timer'), ' and ', el('code', {}, '/stop'), '.'));
  }
  if (t.configured) {
    if (t.last_error) {
      const when = new Date(t.last_error_at * 1000).toLocaleString('en-GB', { dateStyle: 'medium', timeStyle: 'short' });
      kids.push(el('p', { class: 'error' }, `Last problem (${when}): ${t.last_error}`));
    }
    kids.push(el('details', { class: 'tg-more' }, el('summary', {}, 'Bot and proxy'), tgBotForm(t)),
      el('div', { class: 'btn-row' }, el('button', { type: 'button', class: 'danger', onclick: disconnectTelegram }, 'Disconnect Telegram')));
  }
  box.replaceChildren(...kids);
  pollTelegramLink();
}

async function disconnectTelegram() {
  if (!confirm('Disconnect Telegram? The bot stops sending you reminders. You can connect again later.')) return;
  if (!await run(() => api('DELETE', '/api/telegram'))) return;
  toast('Disconnected');
  W.telegram = null;
  loadTelegram();
}
