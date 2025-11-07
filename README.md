# Sistema IoT de alerta temprana de riesgo de incendios forestales basado en la norma ITU-T Y.4113

Este repositorio contiene el desarrollo completo del **sistema IoT modular de alerta temprana de incendios forestales**, implementado como proyecto de grado por **Alejandro Rincón Maldonado**.  
El diseño sigue la arquitectura de referencia definida por las normas **ITU-T Y.2060** y **ITU-T Y.4113**, estructurada en cuatro capas: **dispositivos, red, procesamiento y aplicación**.

---

## 🌍 Descripción general

El sistema permite monitorear variables ambientales críticas —**temperatura, humedad y velocidad del viento**— mediante una red de sensores ESP32 y una unidad central Raspberry Pi.  
A partir de estas mediciones se calcula el **Índice F**, indicador del nivel de riesgo de incendio forestal.  
Cuando el índice supera el umbral de alerta, el sistema **envía notificaciones por SMS**, y los datos se visualizan tanto localmente como a través de una **API web** y una **aplicación Android**.

---

## 🧩 Arquitectura general del sistema

```text
+--------------------------------------------------------------+
|                       Capa de Aplicación                     |
|  Dashboard Web (Node.js + ECharts) / App Android (Kotlin)    |
+-----------------------------+--------------------------------+
|                     Capa de Procesamiento                    |
|  Raspberry Pi 4: MQTT Broker, InfluxDB, Grafana, Node.js API |
|  Scripts Python: Main.py / wind_reader.py / F_index.py / SMS |
+-----------------------------+--------------------------------+
|                         Capa de Red                          |
|  Wi-Fi local "WeatherStation" + túnel Cloudflare + Módem MiFi|
+-----------------------------+--------------------------------+
|                      Capa de Dispositivos                    |
|  Nodos ESP32 + DHT22 + anemómetro RS485 (DFRobot SEN0483)    |
+--------------------------------------------------------------+
