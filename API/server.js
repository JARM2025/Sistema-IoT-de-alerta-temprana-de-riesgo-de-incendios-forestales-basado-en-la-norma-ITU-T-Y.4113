/**
 * Weather Station API (InfluxDB 1.x) — CommonJS
 * ------------------------------------------------
 * - Sirve landing estática en /
 * - CORS con allowlist configurable
 * - 'sensor_id' ahora es opcional si el measurement NO tiene esa tag (p.ej. f_index)
 *   y acepta sensor_id='*' para "no filtrar por sensor"
 *
 * Endpoints:
 *    GET  /api/health
 *    GET  /api/meta/measurements
 *    GET  /api/meta/tag-keys?measurement=...
 *    GET  /api/meta/tag-values?measurement=...&tagKey=...
 *    GET  /api/meta/field-keys?measurement=...
 *    GET  /api/latest?measurement=...&fields=...&sensor_id=...
 *    GET  /api/range?measurement=...&field=...&sensor_id=...&range=15m&interval=1m&agg=mean&order=ASC&tz=America/Bogota&fill=previous&precision=1
 */

require('dotenv').config();

const express = require('express');
const morgan  = require('morgan');
const Influx  = require('influx');
const path    = require('path');

// -----------------------------
// 1) Config desde .env (con defaults)
// -----------------------------
const PORT         = Number(process.env.PORT || 3100);
const INFLUX_HOST  = process.env.INFLUX_HOST  || 'localhost';
const INFLUX_PORT  = Number(process.env.INFLUX_PORT || 8086);
const INFLUX_DB    = process.env.INFLUX_DB    || 'weather_data';
const INFLUX_USER  = process.env.INFLUX_USERNAME || undefined;
const INFLUX_PASS  = process.env.INFLUX_PASSWORD || undefined;

// CORS allowlist (puedes sobreescribir con CORS_ALLOW_ORIGINS=a,b,c en .env)
const DEFAULT_ALLOWED_ORIGINS = [
  'https://weatherstation.site',
  'https://grafana.weatherstation.site',
  'https://api.weatherstation.site',
  // dev comunes
  'http://localhost:3000',
  'http://127.0.0.1:3000',
  'http://localhost:5173',
  'http://127.0.0.1:5173',
  'http://localhost:4200',
  'http://127.0.0.1:4200',
];
const allowedOrigins = new Set(
  (process.env.CORS_ALLOW_ORIGINS
    ? process.env.CORS_ALLOW_ORIGINS.split(',')
    : DEFAULT_ALLOWED_ORIGINS
  ).map(s => s.trim()).filter(Boolean)
);

// -----------------------------
// 2) Cliente InfluxDB
// -----------------------------
const influx = new Influx.InfluxDB({
  host: INFLUX_HOST,
  port: INFLUX_PORT,
  database: INFLUX_DB,
  username: INFLUX_USER,
  password: INFLUX_PASS,
});

// -----------------------------
// 3) App Express (middleware + estáticos)
// -----------------------------
const app = express();
app.set('trust proxy', true);
app.use(morgan('combined'));

// CORS simple con allowlist
app.use((req, res, next) => {
  const origin = req.headers.origin;
  if (origin && allowedOrigins.has(origin)) {
    res.setHeader('Access-Control-Allow-Origin', origin);
    res.setHeader('Vary', 'Origin');
  }
  res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');
  if (req.method === 'OPTIONS') return res.sendStatus(204);
  next();
});

// Sirve /public (landing en /)
app.use(express.static(path.join(__dirname, 'public')));
app.get('/', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

// -----------------------------
// 4) Helpers y utilidades
// -----------------------------
// Identificadores seguros (measurement, field, tag keys)
const isIdent = (s) => typeof s === 'string' && /^[A-Za-z0-9_]+$/.test(s);
// Duraciones válidas de InfluxQL (1m, 5m, 2h, 7d, 1w, 500ms, etc.)
const isDuration = (s) => typeof s === 'string' && /^\d+(ms|s|m|h|d|w)$/.test(s);
// Escapa comillas simples para usar en WHERE '...'
const esc = (v) => String(v).replace(/'/g, "\\'");

// Redondeo opcional
function maybeRound(v, precision) {
  if (precision == null) return v;
  const p = Number(precision);
  if (!Number.isFinite(p)) return v;
  return Number.parseFloat(Number(v).toFixed(p));
}

// Convierte filas de Influx (Date en 'time') a { ts, value }
function rowsToPoints(rows, valueColumn, precision) {
  return rows.map(r => ({
    ts: (r.time instanceof Date) ? r.time.toISOString() : new Date(r.time).toISOString(),
    value: maybeRound(r[valueColumn], precision),
  }));
}

// Añade tz('America/Bogota') si viene tz
function addTZ(sql, tz) {
  if (tz && typeof tz === 'string' && tz.trim()) {
    return `${sql} tz('${tz.replace(/'/g, "''")}')`;
  }
  return sql;
}

// Cache simple y helper para saber si un measurement tiene cierta tag (p.ej. sensor_id)
const tagHasCache = new Map();
async function measurementHasTag(measurement, tagKey) {
  const key = `${measurement}::${tagKey}`;
  if (tagHasCache.has(key)) return tagHasCache.get(key);
  const rows = await influx.query(`SHOW TAG KEYS FROM "${measurement}"`);
  const keys = rows.map(r => r.tagKey || r.key).filter(Boolean);
  const exists = keys.includes(tagKey);
  tagHasCache.set(key, exists);
  return exists;
}

// -----------------------------
// 5) Rutas Meta
// -----------------------------
app.get('/api/health', async (req, res) => {
  try {
    const raw = await influx.queryRaw('SHOW DATABASES');
    const names = (raw && raw.results && raw.results[0] && raw.results[0].series && raw.results[0].series[0])
      ? raw.results[0].series[0].values.map(v => v[0])
      : [];
    res.json({ ok: true, influx: Array.isArray(names) ? 'reachable' : 'unreachable', db: INFLUX_DB });
  } catch (err) {
    res.json({ ok: false, influx: 'unreachable', db: INFLUX_DB, error: String(err && err.message || err) });
  }
});

app.get('/api/meta/measurements', async (req, res) => {
  try {
    const rows = await influx.query('SHOW MEASUREMENTS');
    res.json(rows.map(r => r.name).filter(Boolean));
  } catch (err) {
    res.status(500).json({ error: String(err && err.message || err) });
  }
});

app.get('/api/meta/tag-keys', async (req, res) => {
  const measurement = req.query.measurement;
  if (!isIdent(measurement)) {
    return res.status(400).json({ error: "Parametro 'measurement' requerido (alfanumérico/_)." });
  }
  try {
    const rows = await influx.query(`SHOW TAG KEYS FROM "${measurement}"`);
    res.json(rows.map(r => r.tagKey || r.key).filter(Boolean));
  } catch (err) {
    res.status(500).json({ error: String(err && err.message || err) });
  }
});

app.get('/api/meta/tag-values', async (req, res) => {
  const { measurement, tagKey } = req.query;
  if (!isIdent(measurement)) return res.status(400).json({ error: "Parametro 'measurement' requerido (alfanumérico/_)." });
  if (!isIdent(tagKey))     return res.status(400).json({ error: "Parametro 'tagKey' requerido (alfanumérico/_)." });
  try {
    const sql = `SHOW TAG VALUES FROM "${measurement}" WITH KEY = "${tagKey}"`;
    const rows = await influx.query(sql);
    res.json(rows.map(r => r.value).filter(v => v != null));
  } catch (err) {
    res.status(500).json({ error: String(err && err.message || err) });
  }
});

app.get('/api/meta/field-keys', async (req, res) => {
  const measurement = req.query.measurement;
  if (!isIdent(measurement)) {
    return res.status(400).json({ error: "Parametro 'measurement' requerido (alfanumérico/_)." });
  }
  try {
    const rows = await influx.query(`SHOW FIELD KEYS FROM "${measurement}"`);
    res.json(rows.map(r => ({ key: r.fieldKey, type: r.fieldType })));
  } catch (err) {
    res.status(500).json({ error: String(err && err.message || err) });
  }
});

// -----------------------------
// 6) Últimos valores (multi-campo) — sensor_id opcional
// -----------------------------
app.get('/api/latest', async (req, res) => {
  const measurement = req.query.measurement;
  const fieldsCsv   = req.query.fields; // "temperature,humidity"
  const sensorId    = req.query.sensor_id;

  if (!isIdent(measurement)) {
    return res.status(400).json({ error: "Parametro 'measurement' requerido (alfanumérico/_)." });
  }
  if (!fieldsCsv || typeof fieldsCsv !== 'string') {
    return res.status(400).json({ error: "Parametro 'fields' requerido (lista separada por comas)." });
  }
  // Ver si el measurement realmente usa sensor_id
  let needsSensor = false;
  try { needsSensor = await measurementHasTag(measurement, 'sensor_id'); } catch {}

  if (needsSensor && !sensorId) {
    return res.status(400).json({ error: "Parametro 'sensor_id' requerido para este measurement." });
  }

  // Normaliza lista de campos
  const fields = fieldsCsv.split(',').map(s => s.trim()).filter(Boolean);
  if (!fields.length) return res.status(400).json({ error: "Parametro 'fields' vacío." });
  if (!fields.every(isIdent)) return res.status(400).json({ error: "Todos los 'fields' deben ser alfanumérico/_." });

  // WHERE opcional por sensor
  const whereSensor = (needsSensor && sensorId && sensorId !== '*')
    ? ` WHERE "sensor_id"='${esc(sensorId)}'`
    : '';

  try {
    const latest = {};
    for (const f of fields) {
      const sql = `SELECT LAST("${f}") AS value FROM "${measurement}"${whereSensor}`;
      const rows = await influx.query(sql);
      if (rows && rows.length) {
        const row = rows[0];
        latest[f] = {
          value: row.value,
          ts: (row.time instanceof Date) ? row.time.toISOString() : new Date(row.time).toISOString()
        };
      } else {
        latest[f] = null;
      }
    }
    res.json({ measurement, sensor_id: sensorId != null ? String(sensorId) : null, latest });
  } catch (err) {
    res.status(500).json({ error: String(err && err.message || err) });
  }
});

// -----------------------------
// 7) Series / Rango (agrupadas o crudas) — sensor_id opcional
// -----------------------------
app.get('/api/range', async (req, res) => {
  const {
    measurement,
    field,
    sensor_id: sensorId,
    range, from, to,
    interval, agg,
    order = 'DESC',
    limit = '5000',
    fill,
    tz,
    precision
  } = req.query;

  if (!isIdent(measurement)) return res.status(400).json({ error: "Parametro 'measurement' requerido (alfanumérico/_)." });
  if (!isIdent(field))       return res.status(400).json({ error: "Parametro 'field' requerido (alfanumérico/_)." });

  // ¿El measurement usa sensor_id?
  let needsSensor = false;
  try { needsSensor = await measurementHasTag(measurement, 'sensor_id'); } catch {}
  if (needsSensor && !sensorId) {
    return res.status(400).json({ error: "Parametro 'sensor_id' requerido para este measurement." });
  }

  const upOrder = String(order || 'DESC').toUpperCase();
  if (!['ASC', 'DESC'].includes(upOrder)) {
    return res.status(400).json({ error: "Parametro 'order' invalido (ASC|DESC)." });
  }

  const limNum = Number(limit);
  if (!Number.isInteger(limNum) || limNum <= 0 || limNum > 100000) {
    return res.status(400).json({ error: "Parametro 'limit' invalido (1..100000)." });
  }

  // WHERE de tiempo
  let timeWhere = '';
  if (from && to) {
    const fromD = new Date(from), toD = new Date(to);
    if (isNaN(fromD.getTime()) || isNaN(toD.getTime())) {
      return res.status(400).json({ error: "Parametros 'from' y/o 'to' no son fechas válidas." });
    }
    timeWhere = `time >= '${fromD.toISOString()}' AND time <= '${toD.toISOString()}'`;
  } else {
    const effectiveRange = range || '15m';
    if (!isDuration(effectiveRange)) {
      return res.status(400).json({ error: "Parametro 'range' invalido. Ej: 15m, 2h, 7d." });
    }
    timeWhere = `time > now() - ${effectiveRange}`;
  }

  // WHERE opcional por sensor (si aplica y si no es '*')
  const sensorClause = (needsSensor && sensorId && sensorId !== '*')
    ? ` AND "sensor_id"='${esc(sensorId)}'`
    : '';

  // Modo AGRUPADO (interval + agg) vs RAW (sin interval)
  const isGrouped = Boolean(interval);

  if (isGrouped) {
    if (!isDuration(interval)) {
      return res.status(400).json({ error: "Parametro 'interval' invalido. Ej: 1m, 5m, 1h." });
    }
    const aggFn = (agg || 'mean').toLowerCase();
    const allowedAgg = new Set(['mean', 'max', 'min', 'median', 'sum', 'count']);
    if (!allowedAgg.has(aggFn)) {
      return res.status(400).json({ error: "Parametro 'agg' invalido. Permitidos: mean,max,min,median,sum,count." });
    }

    const fillClause = (fill === 'previous') ? ' fill(previous)' :
                       (fill === 'none' || fill === undefined) ? '' : '';

    let sql =
      `SELECT ${aggFn}("${field}") AS value` +
      ` FROM "${measurement}"` +
      ` WHERE ${timeWhere}${sensorClause}` +
      ` GROUP BY time(${interval})${fillClause}` +
      ` ORDER BY time ${upOrder}` +
      ` LIMIT ${limNum}`;

    sql = addTZ(sql, tz);

    try {
      const rows = await influx.query(sql);
      const points = rowsToPoints(rows, 'value', precision);
      return res.json({
        measurement,
        field,
        sensor_id: sensorId != null ? String(sensorId) : null,
        params: {
          range: range || null,
          from: from || null,
          to: to || null,
          interval,
          agg: aggFn,
          order: upOrder,
          limit: limNum,
          fill: fill || 'none',
          tz: tz || null,
          precision: precision || null
        },
        points
      });
    } catch (err) {
      return res.status(400).json({ error: `A 400 Bad Request error occurred: ${JSON.stringify({ error: String(err && err.message || err) })}\n` });
    }
  } else {
    // RAW
    let sql =
      `SELECT "${field}" AS value` +
      ` FROM "${measurement}"` +
      ` WHERE ${timeWhere}${sensorClause}` +
      ` ORDER BY time ${upOrder}` +
      ` LIMIT ${limNum}`;

    sql = addTZ(sql, tz);

    try {
      const rows = await influx.query(sql);
      const points = rowsToPoints(rows, 'value', precision);
      return res.json({
        measurement,
        field,
        sensor_id: sensorId != null ? String(sensorId) : null,
        params: {
          range: range || null,
          from: from || null,
          to: to || null,
          interval: null,
          agg: null,
          order: upOrder,
          limit: limNum,
          fill: null,
          tz: tz || null,
          precision: precision || null
        },
        points
      });
    } catch (err) {
      return res.status(400).json({ error: `A 400 Bad Request error occurred: ${JSON.stringify({ error: String(err && err.message || err) })}\n` });
    }
  }
});

// -----------------------------
// Resumen: �ltimo dato de 4 medidas en los �ltimos X minutos (por defecto 5m)
// -----------------------------
app.get('/api/latest_summary', async (req, res) => {
  try {
    const window = (req.query.window || '5m'); // duraci�n InfluxQL: 5m, 10m, 1h...
    if (!isDuration(window)) {
      return res.status(400).json({ error: "Parametro 'window' invalido. Ej: 5m, 10m, 1h." });
    }

    const specs = [
      { measurement: 'f_index',     field: 'F_index',    sensor_id: 'calcF', key: 'f_index',    unit: ''   },
      { measurement: 'sensor_data', field: 'temperature', sensor_id: '1',    key: 'temperature', unit: '�C' },
      { measurement: 'sensor_data', field: 'humidity',    sensor_id: '1',    key: 'humidity',    unit: '%'  },
      { measurement: 'wind_data',   field: 'wind_speed',  sensor_id: '2',    key: 'wind_speed',  unit: 'm/s'},
    ];

    const data = {};
    let newest = null;

    for (const spec of specs) {
      // �Este measurement realmente tiene tag sensor_id?
      let needsSensor = false;
      try { needsSensor = await measurementHasTag(spec.measurement, 'sensor_id'); } catch {}

      const sensorClause = (needsSensor && spec.sensor_id && spec.sensor_id !== '*')
        ? ` AND "sensor_id"='${esc(spec.sensor_id)}'`
        : '';

      // Solo puntos dentro de la ventana (now() - window)
      const sql = `SELECT LAST("${spec.field}") AS value FROM "${spec.measurement}" WHERE time > now() - ${window}${sensorClause}`;
      const rows = await influx.query(sql);

      if (rows && rows.length) {
        const row = rows[0];
        const tsIso = (row.time instanceof Date) ? row.time.toISOString() : new Date(row.time).toISOString();
        data[spec.key] = {
          measurement: spec.measurement,
          field: spec.field,
          sensor_id: needsSensor ? spec.sensor_id : null,
          value: row.value,
          unit: spec.unit,
          timestamp_utc: tsIso,
        };
        if (!newest || tsIso > newest) newest = tsIso;
      } else {
        data[spec.key] = {
          measurement: spec.measurement,
          field: spec.field,
          sensor_id: needsSensor ? spec.sensor_id : null,
          value: null,
          unit: spec.unit,
          timestamp_utc: null,
        };
      }
    }

    res.json({
      window,
      timestamp_utc: newest,
      data
    });
  } catch (err) {
    res.status(500).json({ error: String(err && err.message || err) });
  }
});

// -----------------------------
// 8) Arranque del servidor
// -----------------------------
app.listen(PORT, () => {
  console.log(`API escuchando en http://localhost:${PORT}`);
  console.log(`Conectando a InfluxDB ${INFLUX_HOST}:${INFLUX_PORT}, DB=${INFLUX_DB}`);
});
