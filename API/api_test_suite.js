// api_test_suite.js
// Simple regression test suite for your InfluxQL API.
// Usage:
//   node api_test_suite.js
//   API_BASE=http://localhost:3100 MEAS=f_index FIELD=f_index SENSOR_ID=1 node api_test_suite.js

const BASE = process.env.API_BASE || 'http://localhost:3100';
const MEAS = process.env.MEAS || 'sensor_data';
const SENSOR_ID = process.env.SENSOR_ID || '1';
const FIELD = process.env.FIELD || 'temperature';
const FIELD2 = process.env.FIELD2 || 'humidity';
const TZ = process.env.TZ || 'America/Bogota';

const results = [];
const nowISO = () => new Date().toISOString();
const qs = (obj) => new URLSearchParams(obj).toString();

function record(name, ok, info = '') {
  results.push({ name, ok, info });
  const mark = ok ? '[OK]' : '[X]';
  const extra = info ? ' - ' + info : '';
  console.log(mark + ' ' + name + extra);
}

async function get(path, params, expect = 200) {
  const url = BASE + path + (params ? ('?' + qs(params)) : '');
  const res = await fetch(url);
  const txt = await res.text();
  const ok = res.status === expect;
  if (!ok) {
    const info = 'HTTP ' + res.status + ' ' + res.statusText + ' :: ' + url + '\n' + txt;
    throw new Error(info);
  }
  try { return JSON.parse(txt); } catch { return txt; }
}

function assert(cond, msg) {
  if (!cond) throw new Error(msg);
}

function isoAscending(points) {
  for (let i = 1; i < points.length; i++) {
    if (new Date(points[i].ts) < new Date(points[i - 1].ts)) return false;
  }
  return true;
}

function isoDescending(points) {
  for (let i = 1; i < points.length; i++) {
    if (new Date(points[i].ts) > new Date(points[i - 1].ts)) return false;
  }
  return true;
}

(async () => {
  console.log('');
  console.log('== API TEST SUITE == ' + nowISO());
  console.log('Base: ' + BASE);
  console.log('MEAS: ' + MEAS);
  console.log('Field: ' + FIELD);
  console.log('Sensor: ' + SENSOR_ID);
  console.log('TZ: ' + TZ);
  console.log('');

  // 1) health
  try {
    const j = await get('/api/health');
    assert(j.ok === true && typeof j.db === 'string', 'health payload invalido');
    record('GET /api/health', true);
  } catch (e) { record('GET /api/health', false, e.message); }

  // 2) meta/measurements
  try {
    const arr = await get('/api/meta/measurements');
    assert(Array.isArray(arr) && arr.length > 0, 'sin measurements');
    record('GET /api/meta/measurements', true, '(' + arr.slice(0, 5).join(', ') + ')');
  } catch (e) { record('GET /api/meta/measurements', false, e.message); }

  // 3) meta/tag-keys
  try {
    const arr = await get('/api/meta/tag-keys', { measurement: MEAS });
    assert(Array.isArray(arr) && arr.includes('sensor_id'), 'no incluye sensor_id');
    record('GET /api/meta/tag-keys', true);
  } catch (e) { record('GET /api/meta/tag-keys', false, e.message); }

  // 4) meta/field-keys
  try {
    const arr = await get('/api/meta/field-keys', { measurement: MEAS });
    assert(Array.isArray(arr) && arr.length > 0, 'sin field keys');
    record('GET /api/meta/field-keys', true, '(' + arr.map(x => x.key).slice(0, 5).join(', ') + ')');
  } catch (e) { record('GET /api/meta/field-keys', false, e.message); }

  // 5) latest temperature + humidity
  try {
    const j = await get('/api/latest', {
      measurement: MEAS, fields: FIELD + ',' + FIELD2, sensor_id: SENSOR_ID
    });
    assert(j.latest && j.latest[FIELD], 'falta ultimo valor FIELD');
    assert(typeof j.latest[FIELD].value === 'number', 'ultimo valor no numerico');
    record('GET /api/latest (dos campos)', true, FIELD + '=' + j.latest[FIELD].value);
  } catch (e) { record('GET /api/latest (dos campos)', false, e.message); }

  // 6) range: 15m, 1m, mean, DESC
  try {
    const j = await get('/api/range', {
      measurement: MEAS, field: FIELD, sensor_id: SENSOR_ID,
      range: '15m', interval: '1m', agg: 'mean', order: 'DESC', tz: TZ
    });
    assert(Array.isArray(j.points) && j.points.length > 0, 'sin puntos');
    assert(isoDescending(j.points), 'orden no es DESC');
    assert(typeof j.points[0].value === 'number', 'valor no numerico');
    record('GET /api/range (15m,1m,mean,DESC)', true, j.points.length + ' pts');
  } catch (e) { record('GET /api/range (15m,1m,mean,DESC)', false, e.message); }

  // 7) range: 15m, 1m, mean, ASC
  try {
    const j = await get('/api/range', {
      measurement: MEAS, field: FIELD, sensor_id: SENSOR_ID,
      range: '15m', interval: '1m', agg: 'mean', order: 'ASC', tz: TZ
    });
    assert(Array.isArray(j.points) && j.points.length > 0, 'sin puntos');
    assert(isoAscending(j.points), 'orden no es ASC');
    record('GET /api/range (15m,1m,mean,ASC)', true);
  } catch (e) { record('GET /api/range (15m,1m,mean,ASC)', false, e.message); }

  // 8) range: fill=previous
  try {
    const j = await get('/api/range', {
      measurement: MEAS, field: FIELD, sensor_id: SENSOR_ID,
      range: '15m', interval: '1m', agg: 'mean', fill: 'previous', order: 'ASC', tz: TZ
    });
    assert(Array.isArray(j.points), 'sin puntos');
    record('GET /api/range (fill=previous)', true);
  } catch (e) { record('GET /api/range (fill=previous)', false, e.message); }

  // 9) range: precision=1
  try {
    const j = await get('/api/range', {
      measurement: MEAS, field: FIELD, sensor_id: SENSOR_ID,
      range: '15m', interval: '1m', agg: 'mean', precision: '1', order: 'ASC', tz: TZ
    });
    assert(Array.isArray(j.points) && j.points.length > 0, 'sin puntos');
    assert(typeof j.points[0].value === 'number', 'valor no numerico');
    record('GET /api/range (precision=1)', true);
  } catch (e) { record('GET /api/range (precision=1)', false, e.message); }

  // 10) range: from/to exact window
  try {
    const to = new Date();
    const from = new Date(to.getTime() - 10 * 60 * 1000); // 10 minutes
    const j = await get('/api/range', {
      measurement: MEAS, field: FIELD, sensor_id: SENSOR_ID,
      from: from.toISOString(), to: to.toISOString(),
      interval: '1m', agg: 'mean', order: 'ASC', tz: TZ
    });
    assert(Array.isArray(j.points), 'sin puntos');
    record('GET /api/range (from/to, 10m, 1m, mean)', true, j.points.length + ' pts');
  } catch (e) { record('GET /api/range (from/to, 10m, 1m, mean)', false, e.message); }

  // 11) range: raw points (no interval), limit
  try {
    const j = await get('/api/range', {
      measurement: MEAS, field: FIELD, sensor_id: SENSOR_ID,
      range: '1h', limit: '50', order: 'DESC', tz: TZ
    });
    assert(Array.isArray(j.points), 'sin puntos');
    record('GET /api/range RAW (1h, limit=50)', true, j.points.length + ' pts');
  } catch (e) { record('GET /api/range RAW (1h, limit=50)', false, e.message); }

  // 12) expected error: invalid interval
  try {
    await get('/api/range', {
      measurement: MEAS, field: FIELD, sensor_id: SENSOR_ID,
      range: '15m', interval: '1x', agg: 'mean'
    }, 400);
    record('GET /api/range ERROR (interval invalido -> 400)', true);
  } catch (e) { record('GET /api/range ERROR (interval invalido -> 400)', false, e.message); }

  // 13) expected error: missing sensor_id
  try {
    await get('/api/latest', {
      measurement: MEAS, fields: FIELD + ',' + FIELD2
    }, 400);
    record('GET /api/latest ERROR (missing sensor_id -> 400)', true);
  } catch (e) { record('GET /api/latest ERROR (missing sensor_id -> 400)', false, e.message); }

  // Summary
  const passed = results.filter(r => r.ok).length;
  const failed = results.length - passed;
  console.log('');
  console.log('== SUMMARY: ' + passed + '/' + results.length + ' passed, ' + failed + ' failed ==');
  console.log('');
  if (failed > 0) process.exitCode = 1;
})().catch(err => {
  console.error('Fallo general en test suite:', err);
  process.exit(1);
});
