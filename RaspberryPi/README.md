# Servicios Python en la Raspberry Pi (capa de procesamiento)

Este directorio contiene los **servicios principales en Python** que se ejecutan en la **Raspberry Pi 4 Model B**, encargada de la adquisición, sincronización, almacenamiento y análisis de datos ambientales dentro del sistema IoT de alerta temprana de incendios forestales.  
Los procesos se ejecutan como *daemons* gestionados por **systemd**, garantizando arranque automático, reinicio ante fallos y registro persistente de eventos.

---

## 📘 Descripción general

Los scripts contenidos en esta carpeta implementan la lógica funcional de la **capa de procesamiento** del sistema.  
Cada proceso cumple una función independiente, pero todos se comunican mediante el **corredor MQTT** local y la base de datos de series temporales **InfluxDB**.

| Archivo | Función principal |
|----------|-------------------|
| [`Main.py`](Main.py) | Publica sellos temporales (`sync/timestamp`), recibe mediciones desde los nodos ESP32 y las almacena en InfluxDB. |
| [`wind_reader.py`](wind_reader.py) | Lee la velocidad del viento desde el anemómetro RS485 y publica los datos sincronizados en MQTT e InfluxDB. |
| [`F_index.py`](F_index.py) | Calcula el **Índice F** a partir de temperatura, humedad y viento, y publica los resultados. |
| [`sms_alert.py`](sms_alert.py) | Supervisa el Índice F y envía alertas por SMS a través del módem **Huawei E5573**. |
