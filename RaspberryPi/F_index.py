# -*- coding: utf-8 -*-
"""
F_index.py — Calcula FMI y F_index a partir de lecturas MQTT y publica en InfluxDB.
- FMI = 10 - 0.25 * (T - H)     (T en °C, H en %)
- U   = velocidad viento (km/h); si U < 1, entonces U = 1
- F   = U / FMI

Alertas (replanteado):
  - Lógica atómica con Lock para evitar condiciones de carrera.
  - No envía si el nuevo timestamp es <= al último alertado.
  - No envía si han pasado < ALERT_COOLDOWN segundos entre timestamps de datos.
  - Registra el estado ANTES de intentar enviar (si falla el SMS, no reintenta hasta que el ts avance ≥ cooldown).
  - SMS incluye hora local de Colombia (America/Bogota).

Extras:
  - Deduplicación de escritura en Influx por timestamp (evita doble write del mismo ts).
"""

import os
import json
import time
import logging
import threading
from logging.handlers import RotatingFileHandler
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

import paho.mqtt.client as mqtt
from influxdb import InfluxDBClient

from sms_alert import send_sms_alert


# ===================== Configuración =====================

# MQTT
MQTT_BROKER = os.environ.get("MQTT_BROKER", "192.168.1.1")
MQTT_TOPIC  = os.environ.get("MQTT_TOPIC",  "data/sensor/#")

# InfluxDB
INFLUX_HOST     = os.environ.get("INFLUX_HOST", "localhost")
INFLUX_PORT     = int(os.environ.get("INFLUX_PORT", "8086"))
INFLUX_DB       = os.environ.get("INFLUX_DB", "weather_data")
INFLUX_MEAS     = os.environ.get("INFLUX_MEAS", "f_index")
INFLUX_TAG_ID   = os.environ.get("INFLUX_TAG_ID", "calcF")

# Umbral y cooldown (puedes ajustar F_THRESHOLD con variable de entorno)
F_THRESHOLD       = float(os.environ.get("F_THRESHOLD", "1.5"))
ALERT_COOLDOWN    = int(os.environ.get("ALERT_COOLDOWN", "60"))   # segundos

# SMS / Módem
ALERT_PHONE       = os.environ.get("ALERT_PHONE", "3507915148")   # pon aquí tu número real o usa env
MODEM_URL         = os.environ.get("MODEM_URL", "http://admin:admin@192.168.8.1/")

# Ventana de sincronización T/H con U (segundos). Si usas sincronía estricta, déjalo en 0.
SYNC_WINDOW_S     = int(os.environ.get("SYNC_WINDOW_S", "0"))

# Unidad por defecto esperada cuando el payload de viento no trae 'unit'
# (tu anemómetro publica m/s; si en el futuro cambias a km/h, exporta WIND_INPUT_UNIT=km/h)
DEFAULT_WIND_INPUT_UNIT = os.environ.get("WIND_INPUT_UNIT", "m/s").strip().lower()

# Logs
LOG_DIR = os.environ.get("LOG_DIR", "/home/pi/TG/logs")
os.makedirs(LOG_DIR, exist_ok=True)

logger = logging.getLogger("F-index")
logger.setLevel(logging.INFO)
handler = RotatingFileHandler(os.path.join(LOG_DIR, "F_index.log"),
                              maxBytes=1_000_000, backupCount=5)
formatter = logging.Formatter("%(asctime)s [%(levelname)s] %(message)s")
handler.setFormatter(formatter)
logger.addHandler(handler)

# ===================== Estado en memoria =====================

# Últimas lecturas por sensor
latest_dht  = {"t": None, "h": None, "ts": None}   # sensor_id = 1 (DHT22)
latest_wind = {"u": None, "ts": None}              # sensor_id = 2 (anemómetro) — u en km/h

# Estado de alertas: protegido con lock para evitar carreras entre callbacks MQTT
last_alert_epoch_data = None      # float epoch del último SMS enviado
last_alert_ts_iso     = None      # string ISO del último SMS (trazabilidad)
alert_lock = threading.Lock()

# Deduplicación de escritura a Influx por timestamp
last_write_ts_iso = None
write_lock = threading.Lock()


# ===================== Conexiones =====================

influx = InfluxDBClient(host=INFLUX_HOST, port=INFLUX_PORT, database=INFLUX_DB)
influx.create_database(INFLUX_DB)
logger.info("InfluxDB OK: %s", INFLUX_DB)

mqtt_client = mqtt.Client(client_id="F_index_worker")
mqtt_client.reconnect_delay_set(min_delay=1, max_delay=30)


# ===================== Utilidades =====================

def parse_iso8601(ts: str) -> datetime | None:
    """Parsea 'YYYY-MM-DDTHH:MM:SSZ' a datetime con tz UTC."""
    try:
        return datetime.strptime(ts, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except Exception:
        return None

def seconds_between(ts1: datetime, ts2: datetime) -> float:
    return abs((ts1 - ts2).total_seconds())

def write_f_to_influx(f_value: float, ts_iso: str):
    """Escribe F_index en InfluxDB (dedupe por ts_iso)."""
    global last_write_ts_iso
    with write_lock:
        if last_write_ts_iso == ts_iso:
            # Evita doble escritura del mismo timestamp
            logger.debug("Write dedup: ts repetido %s, se omite escritura duplicada", ts_iso)
            return
        body = [{
            "measurement": INFLUX_MEAS,
            "tags": {"sensor_id": INFLUX_TAG_ID},
            "time": ts_iso,
            "fields": {"F_index": float(f_value)}
        }]
        influx.write_points(body, time_precision='s')
        last_write_ts_iso = ts_iso
        logger.info("InfluxDB write OK: F_index=%.4f @ %s", f_value, ts_iso)

# ---------- Conversión de unidades de viento a km/h ----------
def to_kmh(u_raw, unit=None) -> float | None:
    """Convierte la velocidad de viento a km/h según 'unit' del payload (o DEFAULT_WIND_INPUT_UNIT)."""
    try:
        u = float(u_raw)
    except (TypeError, ValueError):
        return None
    unit_norm = (unit or DEFAULT_WIND_INPUT_UNIT).strip().lower().replace(" ", "")
    if unit_norm in ("km/h", "kmh", "kph"):
        return u
    if unit_norm in ("m/s", "ms", "mps"):
        return u * 3.6
    # Unidad desconocida: asumimos m/s por seguridad (tu anemómetro publica m/s)
    return u * 3.6


# ===================== Alertas (con lógica replanteada) =====================

def maybe_send_alert(f_value: float, ts_iso: str):
    """
    Cooldown 60s basado en timestamp de datos (no reloj del sistema).
    - Bloquea si ts_iso es igual al último alertado.
    - Bloquea si ts_epoch <= último epoch alertado (datos duplicados o fuera de orden).
    - Bloquea si han pasado < ALERT_COOLDOWN segundos entre timestamps.
    - Registra el estado ANTES de intentar enviar, para no reintentar si falla el SMS.
    """
    global last_alert_epoch_data, last_alert_ts_iso

    # Debe superar el umbral
    if f_value <= F_THRESHOLD:
        return

    dt = parse_iso8601(ts_iso)
    if not dt:
        logger.debug("Timestamp inválido en alerta: %s", ts_iso)
        return
    ts_epoch = dt.timestamp()

    with alert_lock:
        # 1) Mismo timestamp exacto -> bloquear
        if last_alert_ts_iso is not None and ts_iso == last_alert_ts_iso:
            logger.info("Alerta ignorada: mismo timestamp %s", ts_iso)
            return

        # 2) Si nunca se ha alertado, permitir directamente
        if last_alert_epoch_data is None:
            allow = True
        else:
            delta = ts_epoch - last_alert_epoch_data

            # 3) No monotónico o igual -> bloquear
            if delta <= 0:
                logger.info("Alerta ignorada: timestamp no monotónico (delta=%.1fs) last=%s new=%s",
                            delta, last_alert_ts_iso, ts_iso)
                allow = False
            # 4) Cooldown < ALERT_COOLDOWN -> bloquear
            elif delta < ALERT_COOLDOWN:
                logger.info("Alert blocked by cooldown (faltan %ss) last=%s new=%s",
                            int(ALERT_COOLDOWN - delta), last_alert_ts_iso, ts_iso)
                allow = False
            else:
                allow = True

        if not allow:
            return

        # Registrar estado ANTES de enviar (si el SMS falla, no reintenta hasta que el ts avance ≥ cooldown)
        last_alert_epoch_data = ts_epoch
        last_alert_ts_iso     = ts_iso

    # Enviar fuera del lock
    ts_local = dt.astimezone(ZoneInfo("America/Bogota")).strftime("%Y-%m-%d %H:%M:%S")
    msg = (
        "ALERTA F-index\n"
        f"Valor: {f_value:.2f}\n"
        f"Umbral: {F_THRESHOLD:.2f}\n"
        f"Hora (Colombia): {ts_local}"
    )
    ok = send_sms_alert(ALERT_PHONE, msg, MODEM_URL)
    if ok:
        logger.warning("SMS alert sent: %s", msg.replace("\n", " | "))
    else:
        logger.error("Failed to send SMS alert: %s", msg)


# ===================== Cálculo =====================

def compute_and_store_if_ready():
    """Sincroniza T/H y U, calcula FMI y F, escribe en Influx y evalúa alerta."""
    t = latest_dht["t"]
    h = latest_dht["h"]
    ts_dht = latest_dht["ts"]
    u = latest_wind["u"]          # <-- siempre en km/h
    ts_wind = latest_wind["ts"]

    if t is None or h is None or u is None or ts_dht is None or ts_wind is None:
        return

    # Exige que T/H y U estén dentro de la ventana de sincronización
    if seconds_between(ts_dht, ts_wind) > SYNC_WINDOW_S:
        return

    # Timestamp de referencia (el más reciente de ambos)
    ref_ts = ts_dht if ts_dht >= ts_wind else ts_wind
    ts_iso = ref_ts.strftime("%Y-%m-%dT%H:%M:%SZ")

    # Cálculo de FMI y F
    fmi = 10.0 - 0.25 * (t - h)
    if fmi <= 0:
        logger.warning("FMI <= 0, skipping write (t=%.3f, h=%.3f, u=%.3f, ts=%s)", t, h, u, ts_iso)
        return

    u_eff = u if u >= 1.0 else 1.0   # u en km/h
    f_val = u_eff / fmi

    try:
        write_f_to_influx(f_val, ts_iso)
        maybe_send_alert(f_val, ts_iso)
    except Exception as e:
        logger.error("Error writing F_index or sending alert: %s", e, exc_info=True)


# ===================== Callbacks MQTT =====================

def on_connect(client, userdata, flags, rc):
    logger.info("MQTT conectado (rc=%s)", rc)
    client.subscribe(MQTT_TOPIC, qos=1)
    logger.info("Suscrito a: %s (QoS=1)", MQTT_TOPIC)

def on_disconnect(client, userdata, rc):
    logger.warning("MQTT desconectado (rc=%s). Reintentará.", rc)

def on_message(client, userdata, msg):
    try:
        data = json.loads(msg.payload.decode('utf-8'))
    except Exception:
        logger.warning("Mensaje no-JSON en %s", msg.topic)
        return

    sensor_id = data.get("sensor_id")
    ts_str = data.get("timestamp")
    ts = parse_iso8601(ts_str) if isinstance(ts_str, str) else None
    if ts is None:
        logger.debug("Ignorando mensaje sin timestamp válido: %s", data)
        return

    try:
        if str(sensor_id) == "1":
            t = data.get("temperature")
            h = data.get("humidity")
            if isinstance(t, (int, float)) and isinstance(h, (int, float)):
                latest_dht["t"] = float(t)
                latest_dht["h"] = float(h)
                latest_dht["ts"] = ts
                compute_and_store_if_ready()

        elif str(sensor_id) == "2":
            # Acepta 'wind_speed_kmh' (km/h) o 'wind_speed' con 'unit' ('m/s' o 'km/h')
            u_kmh = None

            if isinstance(data.get("wind_speed_kmh"), (int, float)):
                u_kmh = float(data["wind_speed_kmh"])
            else:
                u_raw = data.get("wind_speed")
                unit = data.get("unit")  # "m/s" o "km/h" (tu worker usa "m/s")
                if isinstance(u_raw, (int, float)):
                    u_kmh = to_kmh(u_raw, unit)

            if isinstance(u_kmh, float):
                latest_wind["u"] = u_kmh   # guardar SIEMPRE en km/h
                latest_wind["ts"] = ts
                compute_and_store_if_ready()
            else:
                logger.debug("Dato de viento inválido o sin unidad interpretable: %s", data)

        else:
            logger.debug("Mensaje de sensor_id no manejado: %s", sensor_id)

    except Exception as e:
        logger.error("Error procesando mensaje: %s | data=%s", e, data, exc_info=True)


# ===================== Main =====================

def main():
    try:
        mqtt_client.on_connect = on_connect
        mqtt_client.on_disconnect = on_disconnect
        mqtt_client.on_message = on_message

        mqtt_client.connect(MQTT_BROKER, keepalive=60)
        mqtt_client.loop_start()
        logger.info("Loop MQTT iniciado. Umbral=%.2f, cooldown=%ds, phone=%s",
                    F_THRESHOLD, ALERT_COOLDOWN, ALERT_PHONE)

        while True:
            time.sleep(1)

    except KeyboardInterrupt:
        logger.info("Interrumpido por usuario.")
    except Exception as e:
        logger.critical("Fallo no controlado: %s", e, exc_info=True)
    finally:
        try:
            mqtt_client.loop_stop()
            mqtt_client.disconnect()
            logger.info("MQTT detenido.")
        except Exception:
            pass
        try:
            influx.close()
            logger.info("InfluxDB cerrada.")
        except Exception:
            pass


if __name__ == "__main__":
    main()
