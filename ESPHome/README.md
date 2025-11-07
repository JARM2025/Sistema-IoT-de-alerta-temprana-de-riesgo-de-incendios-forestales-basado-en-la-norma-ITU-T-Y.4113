# Firmware ESPHome del nodo sensor (ESP32 + DHT22)

Este directorio contiene el firmware declarativo desarrollado con **ESPHome** para los nodos sensores de la estación meteorológica del sistema IoT de alerta temprana de incendios forestales.  
El firmware define la adquisición de temperatura y humedad mediante un sensor **DHT22**, la sincronización temporal con la **Raspberry Pi** mediante mensajes **MQTT**, y las políticas locales de validación y manejo de errores.

---

## 📘 Descripción general

Cada nodo ESP32 ejecuta el archivo [`weather_station_node.yaml`](weather_station_node.yaml), el cual especifica:

- Conexión del sensor **DHT22** al pin **GPIO23**.  
- Publicación de lecturas solo al recibir un sello temporal (`timestamp`) desde el tópico `sync/timestamp`.  
- Validación de rango de medición (0–60 °C y 0–100 % HR).  
- Reinicio automático tras cinco errores consecutivos.  
- Publicación en formato **JSON** hacia el tópico `data/sensor/1`.  
- Punto de acceso *fallback* para configuración local (`esp32-fallback`).  
- Servicio OTA habilitado para actualizaciones remotas.  

---

## ⚙️ Estructura del archivo YAML

El firmware está dividido en las siguientes secciones:

| Sección | Descripción |
|----------|--------------|
| **esphome:** | Define la plataforma, placa y metadatos del proyecto. |
| **wifi:** | Configura la red principal y el punto de acceso de respaldo. |
| **mqtt:** | Establece la comunicación con el corredor MQTT (broker). |
| **globals:** | Variables internas del nodo, como contador de errores y último timestamp recibido. |
| **sensor:** | Configuración del DHT22 con actualización manual (solo al recibir `timestamp`). |
| **script:** | Lógica de validación y publicación sincronizada. |
| **logger / ota:** | Registro local y servicio OTA para mantenimiento remoto. |

---

## 🔧 Requisitos

- **Hardware:**  
  - ESP32 DevKit V1  
  - Sensor DHT22 conectado al GPIO23  
  - Resistencia *pull-up* de 10 kΩ a 3.3 V  
  - Condensador de desacoplo de 100 nF entre VCC y GND  

- **Software:**  
  - [ESPHome](https://esphome.io/) ≥ 2024.6.0  
  - Python 3.10+  
  - Broker MQTT activo (por defecto en `192.168.50.1`)  
  - Conectividad Wi-Fi con SSID `WeatherStation`  

---

## 🚀 Compilación y carga del firmware

### Opción 1: Desde ESPHome Dashboard

1. Abre ESPHome (interfaz web o add-on).  
2. Crea un nuevo proyecto y reemplaza el contenido por el de [`weather_station_node.yaml`](weather_station_node.yaml).  
3. Conecta el ESP32 por USB y selecciona **Install → Plug into this computer**.  
4. Una vez compilado, el dispositivo se conectará automáticamente a la red `WeatherStation`.

### Opción 2: Desde línea de comandos

```bash
esphome run weather_station_node.yaml

