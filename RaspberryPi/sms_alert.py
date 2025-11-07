# -*- coding: utf-8 -*-
"""
sms_alert.py — Envío de SMS usando módem Huawei vía huawei_lte_api,
con logging a archivo y diagnóstico en caso de fallo.

Requisitos:
  pip install huawei-lte-api requests

Log:
  /home/pi/TG/logs/sms_alert.log
"""

import os
import time
import logging
from logging.handlers import RotatingFileHandler
from typing import Optional, Union

from huawei_lte_api.Client import Client
from huawei_lte_api.Connection import Connection
from huawei_lte_api.exceptions import ResponseErrorException


# ========= Logging propio =========
LOG_DIR = "/home/pi/TG/logs"
os.makedirs(LOG_DIR, exist_ok=True)
_sms_log_path = os.path.join(LOG_DIR, "sms_alert.log")

logger = logging.getLogger("sms-alert")
logger.setLevel(logging.INFO)
# Evita duplicar handlers si se reimporta el módulo
if not any(isinstance(h, RotatingFileHandler) and getattr(h, 'baseFilename', None) == _sms_log_path for h in logger.handlers):
    fh = RotatingFileHandler(_sms_log_path, maxBytes=500_000, backupCount=3)
    fh.setFormatter(logging.Formatter("%(asctime)s [%(levelname)s] %(message)s"))
    logger.addHandler(fh)
# También añade un handler a stdout por si miras journalctl
if not any(isinstance(h, logging.StreamHandler) for h in logger.handlers):
    sh = logging.StreamHandler()
    sh.setFormatter(logging.Formatter("%(asctime)s [%(levelname)s] %(message)s"))
    logger.addHandler(sh)


def _normalize_phone(num: str) -> str:
    """Limpia espacios/guiones, respeta '+' si lo pones. No valida E.164 estrictamente."""
    if not isinstance(num, str):
        num = str(num)
    return num.strip().replace(" ", "").replace("-", "")


def _safe_modem_url(url: str) -> str:
    """Oculta credenciales al log."""
    try:
        from urllib.parse import urlparse
        p = urlparse(url)
        safe = f"{p.scheme}://{p.hostname}"
        if p.port:
            safe += f":{p.port}"
        safe += (p.path or "/")
        return safe
    except Exception:
        return "HIDDEN_URL"


def _is_ok_response(resp: Union[str, dict]) -> bool:
    """
    Acepta éxito en ambos formatos que devuelve el módem:
      - dict como {'response': 'OK'} (insensible a mayúsculas)
      - string 'OK'
    """
    if isinstance(resp, dict):
        val = (resp.get("response") or resp.get("Response") or "").strip().upper()
        return val == "OK"
    if isinstance(resp, str):
        return resp.strip().upper() == "OK"
    return False


def _diagnose_after_failure(client: Client) -> None:
    """Intenta registrar info útil del módem tras un fallo de SMS."""
    try:
        st = {}
        try:
            st["monitoring.status"] = client.monitoring.status()
        except Exception:
            pass
        try:
            st["net.current_plmn"] = client.net.current_plmn()
        except Exception:
            pass
        try:
            st["device.information"] = client.device.information()
        except Exception:
            pass
        # Recorta / anonimiza un poco antes de loguear
        if "monitoring.status" in st:
            m = st["monitoring.status"]
            keep = {k: m.get(k) for k in (
                "ConnectionStatus", "SignalIcon", "SignalStrength", "WifiStatus",
                "SimStatus", "CurrentNetworkType", "SmsStorageFull", "SmsReceivedFlag",
                "RoamingStatus"
            ) if k in m}
            logger.info("Diag monitoring.status: %s", keep)
        if "net.current_plmn" in st:
            n = st["net.current_plmn"]
            keep = {k: n.get(k) for k in ("State", "FullName", "ShortName", "Numeric")}
            logger.info("Diag net.current_plmn: %s", keep)
        if "device.information" in st:
            d = st["device.information"]
            keep = {k: d.get(k) for k in ("DeviceName", "SoftwareVersion", "WebUIVersion")}
            logger.info("Diag device.information: %s", keep)
    except Exception as e:
        logger.debug("Diagnóstico falló: %r", e)


def send_sms_alert(
    phone: str,
    message: str,
    modem_url: str = "http://admin:admin@192.168.8.1/",
    retries: int = 2,
    backoff_sec: float = 2.0,
    timeout: Optional[float] = None,  # reservado si ajustas timeouts abajo
) -> bool:
    """
    Envía un SMS a través del módem Huawei.

    Params:
      phone       : Número destino (string). Acepta '300xxxxxxx' o '+57300xxxxxxx'.
      message     : Texto a enviar.
      modem_url   : URL con credenciales, ej. "http://admin:admin@192.168.8.1/"
      retries     : Reintentos adicionales si falla (total = 1 + retries)
      backoff_sec : Espera exponencial entre reintentos (2x)
      timeout     : (No usado directamente aquí)

    Return:
      True si el módem respondió OK; False en caso contrario.
    """
    dest = _normalize_phone(phone)
    payload = message or ""
    safe_url = _safe_modem_url(modem_url)

    attempt = 0
    delay = backoff_sec

    while True:
        attempt += 1
        try:
            logger.info("SMS intento %d -> %s (len=%d) modem=%s",
                        attempt, dest, len(payload), safe_url)

            # Si quisieras timeouts más estrictos en requests, usa AuthorizedConnection con timeout.
            # Aquí usamos Connection básica por compatibilidad.
            with Connection(modem_url) as connection:
                client = Client(connection)

                resp = client.sms.send_sms(phone_numbers=[dest], message=payload)
                logger.info("Huawei SMS response: %s", resp)

                if _is_ok_response(resp):
                    return True

                # Respuesta no-OK sin excepción: tratar como fallo recuperable
                raise RuntimeError(f"Respuesta no OK del módem: {resp!r}")

        except ResponseErrorException as e:
            # Errores explícitos de la API Huawei (ej: códigos 113xxx)
            logger.error("Huawei SMS response error (intento %d): %s", attempt, e, exc_info=True)

            if attempt == 1:
                try:
                    with Connection(modem_url) as c2:
                        client2 = Client(c2)
                        _diagnose_after_failure(client2)
                except Exception as e2:
                    logger.debug("No se pudo abrir conexión para diagnóstico: %r", e2)

            if attempt > retries:
                logger.error("Agotados los reintentos (%d). SMS no enviado.", retries)
                return False

            time.sleep(delay)
            delay *= 2.0

        except Exception as e:
            logger.error("Huawei SMS error (intento %d): %s", attempt, e, exc_info=True)

            if attempt == 1:
                try:
                    with Connection(modem_url) as c2:
                        client2 = Client(c2)
                        _diagnose_after_failure(client2)
                except Exception as e2:
                    logger.debug("No se pudo abrir conexión para diagnóstico: %r", e2)

            if attempt > retries:
                logger.error("Agotados los reintentos (%d). SMS no enviado.", retries)
                return False

            time.sleep(delay)
            delay *= 2.0
