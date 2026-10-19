# Pruebas E2E con dos dispositivos reales

Dirigen la UI de dos teléfonos por `adb` + `uiautomator`, sin tocar la pantalla, y comprueban
conexión, llamada y limpieza de audio con el estado real del sistema (`dumpsys audio`) y el logcat.

## Requisitos
- Dos dispositivos conectados por adb con la app instalada (`./gradlew installDebug` en cada uno).
- Permisos ya concedidos (micrófono, Bluetooth, dispositivos cercanos), misma red Wi-Fi, sin PIN.
- `python3` (solo biblioteca estándar). `adb` en el `PATH`, o en `ANDROID_HOME`/`~/Android/Sdk`.

## Uso
```bash
python3 scripts/e2e/two_device_e2e.py --list           # escenarios disponibles
python3 scripts/e2e/two_device_e2e.py                  # todos
python3 scripts/e2e/two_device_e2e.py connect call     # algunos
python3 scripts/e2e/two_device_e2e.py call --call-seconds 60
python3 scripts/e2e/two_device_e2e.py --caller SERIAL1 --callee SERIAL2
```
El *caller* busca y llama; el *callee* anuncia y acepta. Cada escenario arranca desde cero.
Salida en `scripts/e2e/runs/<fecha>/`: `report.md`, `report.json`, logcat y captura de cada lado.
Código de salida 1 si algún chequeo falla.

## Escenarios
| Nombre | Qué verifica |
|---|---|
| `connect` | Descubrir, solicitar, aceptar; ambos muestran el dispositivo conectado. Mide tiempos. |
| `call` | Llamada de N s con audio activo (modo, AudioTrack, micrófono); colgar libera el audio. |
| `callee_hangs_up` | Si cuelga quien recibe, el que llamó sale y libera el audio. |
| `reverse_call` | La llamada también funciona iniciada por el lado que anuncia. |
| `reject_call` | Rechazar muestra "Llamada rechazada" y no deja audio abierto. |
| `kill_during_call` | Matar el proceso remoto no deja una llamada zombie. Mide el tiempo de detección. |
| `reconnect` | Desconectar y volver a conectar sin reiniciar la app. |
| `wifi_drop` | Apagar el Wi-Fi del callee en plena llamada (informativo; siempre lo reactiva). |

## Límites conocidos
- Comprueba que el audio **está activo** (modo, player y grabación), no su contenido: no mide
  nivel de señal ni latencia de extremo a extremo. Eso requiere instrumentar la app.
- Depende de los textos de la UI en español; si cambian, hay que actualizar el script.
- La hoja de conexión no se cierra sola al conectar; el script la cierra para poder ver la lista.
