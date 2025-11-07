# -*- coding: utf-8 -*-
import os
import json
import time
import threading
import logging
from logging.handlers import RotatingFileHandler
from datetime import datetime, timedelta, timezone

import paho.mqtt.client as mqtt
from influxdb import InfluxDBClient
from apscheduler.schedulers.background import BackgroundScheduler

# -------------------- Logging --------------------
LOG_DIR = "/home/pi/TG/logs"
os.makedirs(LOG_DIR, exist_ok=True)

logger = logging.getLogger("weather-pipeline")
logger.setLevel(logging.INFO)
handler = RotatingFileHandler(os.path.join(LOG_DIR, "weather_pipeline.log"),
                              maxBytes=1_000_000, backupCount=5)
formatter = logging.Formatter("%(asctime)s [%(levelname)s] %(message)s")
handler.setFormatter(formatter)
logger.addHandler(handler)

# -------------------- Configuración --------------------
broker_ip = "192.168.1.1"
sync_topic = "sync/timestamp"
sensor_data_topic = "data/sensor/#"

# InfluxDB
influx_host = "localhost"
influx_port = 8086
influx_database = "weather_data"

# Conexión a InfluxDB
client = InfluxDBClient(host=influx_host, port=influx_port, database=influx_database)
client.create_database(influx_database)
logger.info("InfluxDB OK: %s", influx_database)

# Cliente MQTT
mqtt_client = mqtt.Client(client_id="pi_sync_publisher")
mqtt_client.reconnect_delay_set(min_delay=1, max_delay=30)

# -------------------- Callbacks MQTT --------------------
def on_connect(client_m, userdata, flags, rc):
    logger.info("MQTT conectado (rc=%s)", rc)
    client_m.subscribe(sensor_data_topic, qos=1)
    logger.info("Suscrito a: %s (QoS=1)", sensor_data_topic)

def on_disconnect(client_m, userdata, rc):
    logger.warning("MQTT desconectado (rc=%s). Reintentará.", rc)

def on_message(client_m, userdata, msg):
    try:
        data = json.loads(msg.payload.decode('utf-8'))
        logger.info("Datos recibidos: %s", data)
        insert_sensor_data(data)
    except json.JSONDecodeError:
        logger.warning("Mensaje no JSON en topic %s", msg.topic)
    except Exception as e:
        logger.error("Error procesando mensaje: %s", e, exc_info=True)

mqtt_client.on_connect = on_connect
mqtt_client.on_disconnect = on_disconnect
mqtt_client.on_message = on_message

# -------------------- Funciones --------------------
def is_valid_iso8601_z(ts):
    try:
        datetime.strptime(ts, "%Y-%m-%dT%H:%M:%SZ")
        return True
    except Exception:
        return False

def insert_sensor_data(data):
    sensor_id = data.get("sensor_id")
    temperature = data.get("temperature")
    humidity = data.get("humidity")
    timestamp = data.get("timestamp")

    if not isinstance(temperature, (int, float)) or not isinstance(humidity, (int, float)):
        logger.warning("Lectura invalida (no numerica). data=%s", data)
        return
    if not (sensor_id and isinstance(sensor_id, (int, str))):
        logger.warning("Falta sensor_id. data=%s", data)
        return
    if not (timestamp and isinstance(timestamp, str) and is_valid_iso8601_z(timestamp)):
        logger.warning("Timestamp invalido. data=%s", data)
        return

    json_body = [{
        "measurement": "sensor_data",
        "tags": {"sensor_id": str(sensor_id)},
        "time": timestamp,
        "fields": {
            "temperature": float(temperature),
            "humidity": float(humidity)
        }
    }]

    try:
        client.write_points(json_body, time_precision='s')
        logger.info("InfluxDB OK: sensor=%s temp=%.3f hum=%.3f t=%s",
                    sensor_id, temperature, humidity, timestamp)
    except Exception as e:
        logger.error("Error al escribir en InfluxDB: %s", e, exc_info=True)

def send_sync_timestamp():
    current_time = datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
    sync_message = json.dumps({"timestamp": current_time})
    mqtt_client.publish(sync_topic, sync_message, qos=1, retain=True)
    logger.info("Timestamp enviado (QoS=1, retain): %s", current_time)

# -------------------- Scheduler --------------------
scheduler = BackgroundScheduler(timezone="UTC")

def start_timestamp_scheduler():
    now = datetime.now(timezone.utc)
    next_minute = (now + timedelta(minutes=1)).replace(second=0, microsecond=0)
    wait_time = (next_minute - now).total_seconds()
    logger.info("Iniciando scheduler en %d s.", int(wait_time))
    time.sleep(wait_time)

    scheduler.add_job(
        send_sync_timestamp, 'interval',
        seconds=10, next_run_time=next_minute,
        coalesce=True, max_instances=1, misfire_grace_time=5
    )
    scheduler.start()
    logger.info("Scheduler iniciado (10s).")

# -------------------- Main loop --------------------
try:
    mqtt_client.connect(broker_ip, keepalive=60)
    mqtt_client.loop_start()
    logger.info("Loop MQTT iniciado")
    thread_sync = threading.Thread(target=start_timestamp_scheduler, daemon=True)
    thread_sync.start()
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
        scheduler.shutdown()
        logger.info("Scheduler detenido.")
    except Exception:
        pass
    try:
        client.close()
        logger.info("InfluxDB cerrada.")
    except Exception:
        pass
