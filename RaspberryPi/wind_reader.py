# -*- coding: utf-8 -*-
import os
import json
import time
import logging
from logging.handlers import RotatingFileHandler
from datetime import datetime
import struct
from typing import Optional

import termios  # <-- para capturar errores del TTY (tcflush)
import serial
import paho.mqtt.client as mqtt
from influxdb import InfluxDBClient

# ================== CONFIG ==================
BROKER_IP   = "192.168.1.1"
SYNC_TOPIC  = "sync/timestamp"              # Se suscribe aquí
WIND_TOPIC  = "data/sensor/anemometer"      # Publica aquí (sin retain)
LOG_DIR     = "/home/pi/TG/logs"

# RS485 / Anemómetro
SERIAL_PORT = "/dev/serial/by-id/usb-1a86_USB_Serial-if00-port0"  # estable por-id
ANEMO_ADDR  = 2
BAUD        = 9600
TIMEOUT     = 0.5
SENSOR_ID   = "2"

# InfluxDB
INFLUX_HOST = "localhost"
INFLUX_PORT = 8086
INFLUX_DB   = "weather_data"
MEASUREMENT = "wind_data"                   # field: wind_speed (m/s), tag: sensor_id
# ============================================

os.makedirs(LOG_DIR, exist_ok=True)
logger = logging.getLogger("anemometer-worker")
logger.setLevel(logging.INFO)
handler = RotatingFileHandler(os.path.join(LOG_DIR, "anemometer_worker.log"),
                              maxBytes=1_000_000, backupCount=5)
handler.setFormatter(logging.Formatter("%(asctime)s [%(levelname)s] %(message)s"))
logger.addHandler(handler)

# -------------- Utilidades --------------
def is_valid_iso8601_z(ts: str) -> bool:
    try:
        datetime.strptime(ts, "%Y-%m-%dT%H:%M:%SZ")
        return True
    except Exception:
        return False

def modbus_crc16(data: bytes) -> int:
    crc = 0xFFFF
    for b in data:
        crc ^= b
        for _ in range(8):
            if crc & 0x0001:
                crc = (crc >> 1) ^ 0xA001
            else:
                crc >>= 1
    return crc & 0xFFFF

def build_read_holding_registers(slave: int, start_addr: int, reg_count: int) -> bytes:
    pdu = struct.pack(">B B H H", slave, 0x03, start_addr, reg_count)
    crc = modbus_crc16(pdu)
    return pdu + struct.pack("<H", crc)  # CRC little-endian

def parse_read_holding_registers(resp: bytes, slave: int, reg_count: int):
    if len(resp) < 5:
        raise ValueError(f"Respuesta corta ({len(resp)} bytes)")
    payload = resp[:-2]
    crc_recv = struct.unpack("<H", resp[-2:])[0]
    crc_calc = modbus_crc16(payload)
    if crc_calc != crc_recv:
        raise ValueError(f"CRC inválido (calc=0x{crc_calc:04X}, recv=0x{crc_recv:04X})")
    if payload[0] != slave:
        raise ValueError(f"Slave inesperado: {payload[0]}")
    if payload[1] != 0x03:
        if payload[1] & 0x80:
            exc_code = payload[2] if len(payload) > 2 else -1
            raise ValueError(f"Excepción Modbus 0x{payload[1]:02X}, code {exc_code}")
        raise ValueError(f"Func inesperada: 0x{payload[1]:02X}")
    byte_count = payload[2]
    expected = reg_count * 2
    if byte_count != expected:
        raise ValueError(f"Byte count inesperado ({byte_count} != {expected})")
    data = payload[3:3 + byte_count]
    vals = struct.unpack(">" + "H" * reg_count, data)
    return vals

def _safe_reset_input_buffer(ser) -> bool:
    """Resetea el buffer de entrada capturando errores del TTY."""
    try:
        ser.reset_input_buffer()
        return True
    except (termios.error, OSError, serial.SerialException) as e:
        logger.warning("reset_input_buffer falló (%s). Marcar puerto como caído.", e)
        return False

# -------------- RS485 / Anemómetro --------------
class Anemometer:
    def __init__(self, port: str, addr: int = 2, baud: int = 9600, timeout: float = 0.5):
        self.port = port
        self.addr = addr
        self.baud = baud
        self.timeout = timeout
        self._ser: Optional[serial.Serial] = None
        self._down_until = 0.0  # monotonic(): evita reintentos en ráfaga cuando está caído

    def open(self):
        if self._ser and self._ser.is_open:
            return
        self._ser = serial.Serial(
            port=self.port,
            baudrate=self.baud,
            bytesize=serial.EIGHTBITS,
            parity=serial.PARITY_NONE,
            stopbits=serial.STOPBITS_ONE,
            timeout=self.timeout,
            write_timeout=self.timeout,
            exclusive=True  # POSIX: evita aberturas concurrentes
        )
        # Asegura líneas en estado conocido (algunos dongles quedan “raros” tras reconexión)
        try:
            self._ser.setDTR(True)
            self._ser.setRTS(True)
        except Exception:
            pass
        logger.info("RS485 abierto: %s @ %d", self.port, self.baud)

    def close(self):
        try:
            if self._ser and self._ser.is_open:
                self._ser.close()
                logger.info("RS485 cerrado")
        except Exception as e:
            logger.debug("Error cerrando RS485: %s", e)
        finally:
            self._ser = None

    def ensure_open(self, retries: int = 5, delay: float = 0.2) -> bool:
        """Intenta abrir el puerto con reintentos y backoff suave."""
        from time import monotonic
        now = monotonic()
        if now < self._down_until:
            return False
        for i in range(retries + 1):
            try:
                if not (self._ser and self._ser.is_open):
                    self.open()
                return True
            except (serial.SerialException, OSError, FileNotFoundError) as e:
                if i == 0:
                    logger.warning("RS485 no disponible, intentando reabrir...")
                logger.debug("Intento %d/%d reabrir RS485: %s", i + 1, retries + 1, e)
                time.sleep(delay)
        # Evita spam: espera 2s antes de volver a intentar abrir
        self._down_until = monotonic() + 2.0
        return False

    def read_wind_speed(self, retries: int = 2) -> Optional[float]:
        """
        Lee 1 registro holding (0x0000) y devuelve m/s (resolución 0.1).
        Devuelve None si el puerto no está disponible o la lectura falla.
        """
        if not self.ensure_open():
            logger.debug("RS485 aún no disponible; se omitirá este ciclo.")
            return None

        req = build_read_holding_registers(self.addr, 0x0000, 1)

        for attempt in range(retries + 1):
            try:
                if not _safe_reset_input_buffer(self._ser):
                    # Puerto en mal estado: cerrar y reabrir
                    raise serial.SerialException("reset_input_buffer falló")

                self._ser.write(req)
                self._ser.flush()

                # Leer exactamente 7 bytes (respuesta 0x03 con 1 registro)
                buf = bytearray()
                deadline = time.time() + self.timeout
                while len(buf) < 7 and time.time() < deadline:
                    chunk = self._ser.read(7 - len(buf))
                    if chunk:
                        buf.extend(chunk)
                if len(buf) < 7:
                    raise TimeoutError("Timeout leyendo 0x0000")

                raw = parse_read_holding_registers(bytes(buf), self.addr, 1)[0]
                return raw / 10.0

            except (termios.error, serial.SerialException, OSError, FileNotFoundError) as e:
                # Puerto “stale” o USB reconectado a medias: cerrar, reabrir y salir si no se puede
                logger.warning("Error de serie (%s). Reabriendo RS485...", e)
                self.close()
                if not self.ensure_open():
                    return None  # seguirá intentando en siguientes sync
                # Si reabrió, vuelve al loop para reintentar

            except (TimeoutError, ValueError) as e:
                logger.debug("Reintento lectura anemómetro (intento %d): %s", attempt + 1, e)
                if attempt >= retries:
                    return None
                time.sleep(0.05)

        return None

# -------------- InfluxDB --------------
influx = InfluxDBClient(host=INFLUX_HOST, port=INFLUX_PORT, database=INFLUX_DB)
try:
    influx.create_database(INFLUX_DB)
    logger.info("InfluxDB OK: %s", INFLUX_DB)
except Exception as e:
    logger.error("Error preparando InfluxDB: %s", e, exc_info=True)

def insert_wind_data(sensor_id: str, wind_speed: float, timestamp: str):
    if not is_valid_iso8601_z(timestamp):
        logger.warning("Timestamp inválido (wind): %s", timestamp)
        return False
    json_body = [{
        "measurement": MEASUREMENT,
        "tags": {"sensor_id": str(sensor_id)},
        "time": timestamp,
        "fields": {"wind_speed": float(wind_speed)}
    }]
    try:
        influx.write_points(json_body, time_precision='s')
        logger.info("InfluxDB OK (%s): sensor=%s wind=%.1f m/s t=%s",
                    MEASUREMENT, sensor_id, wind_speed, timestamp)
        return True
    except Exception as e:
        logger.error("Error escribiendo en InfluxDB: %s", e, exc_info=True)
        return False

# -------------- MQTT --------------
anemo = Anemometer(SERIAL_PORT, ANEMO_ADDR, BAUD, TIMEOUT)
mqtt_client = mqtt.Client(client_id="anemometer_worker")
mqtt_client.reconnect_delay_set(min_delay=1, max_delay=30)

LAST_TS = None  # para evitar re-procesar la misma marca si ya se publicó/guardó bien

def on_connect(client_m, userdata, flags, rc):
    logger.info("MQTT conectado (rc=%s)", rc)
    client_m.subscribe(SYNC_TOPIC, qos=1)
    logger.info("Suscrito a: %s (QoS=1)", SYNC_TOPIC)

def on_message(client_m, userdata, msg):
    global LAST_TS
    try:
        payload = msg.payload.decode("utf-8")
        data = json.loads(payload)
        ts = data.get("timestamp")
        if not (isinstance(ts, str) and is_valid_iso8601_z(ts)):
            logger.warning("Sync inválido: %s", payload)
            return

        # Si ya lo procesamos correctamente antes, saltamos
        if ts == LAST_TS:
            logger.debug("Timestamp repetido ya procesado, ignorado: %s", ts)
            return

        # Leer anemómetro y publicar/guardar con el MISMO timestamp
        wind_speed = anemo.read_wind_speed(retries=3)  # un intento extra
        if wind_speed is None:
            # Puerto caído o lectura fallida; no marcamos LAST_TS para reintentar
            logger.info("Lectura de viento omitida (RS485 no disponible o timeout). ts=%s", ts)
            return

        wind_msg = {
            "sensor_id": SENSOR_ID,
            "type": "wind_speed",
            "unit": "m/s",
            "wind_speed": round(wind_speed, 1),
            "timestamp": ts
        }

        # Publicar primero por MQTT
        info = mqtt_client.publish(WIND_TOPIC, json.dumps(wind_msg), qos=1, retain=False)
        info.wait_for_publish(timeout=2.0)

        # Escribir en InfluxDB (no abortamos si falla, pero no marcamos LAST_TS)
        ok_influx = insert_wind_data(SENSOR_ID, wind_msg["wind_speed"], ts)

        # Solo marcamos LAST_TS si al menos publicamos en MQTT correctamente
        if info.rc == mqtt.MQTT_ERR_SUCCESS:
            LAST_TS = ts
            if not ok_influx:
                logger.warning("Publicado MQTT OK pero Influx falló para ts=%s", ts)
            logger.info("Wind MQTT publicado: %s", wind_msg)
        else:
            logger.error("Falló publish MQTT (rc=%s) para ts=%s", info.rc, ts)

    except Exception as e:
        logger.error("Error on_message: %s", e, exc_info=True)

def on_disconnect(client_m, userdata, rc):
    logger.warning("MQTT desconectado (rc=%s). Reintentará.", rc)

mqtt_client.on_connect = on_connect
mqtt_client.on_message = on_message
mqtt_client.on_disconnect = on_disconnect

def main():
    try:
        # abrir RS485 (si falla, se reintenta en ensure_open() al primer on_message)
        try:
            anemo.open()
        except Exception as e:
            logger.warning("No se pudo abrir RS485 al inicio: %s (se reintentará on-demand)", e)

        # conectar MQTT (paho maneja reconexión automática)
        mqtt_client.connect(BROKER_IP, keepalive=60)
        mqtt_client.loop_forever()
    except KeyboardInterrupt:
        logger.info("Interrumpido por usuario.")
    except Exception as e:
        logger.critical("Fallo no controlado: %s", e, exc_info=True)
    finally:
        try:
            anemo.close()
        except Exception:
            pass
        try:
            influx.close()
        except Exception:
            pass

if __name__ == "__main__":
    main()
