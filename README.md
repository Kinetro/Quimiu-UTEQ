# Quimidios — Asistente móvil de detección de equipos de laboratorio (UTEQ)

App Android que detecta equipos del laboratorio en tiempo real con un modelo YOLO propio
(exportado a `.tflite`) y responde preguntas sobre el equipo detectado usando un asistente RAG
con IA on-device (Gemini Nano vía ML Kit GenAI) y respaldo en la nube (Gemini Cloud).

Proyecto para la asignación "Asistente móvil inteligente para la detección de equipos de
laboratorios de la UTEQ" — Tema: Laboratorio de Biotecnología.

## Demo

[Video de demostración](https://drive.google.com/drive/folders/1BYnTFkEswS9VTjbvgOUfY5oSenG9feNO?usp=sharing)

### Capturas de pantalla

| Detección del equipo | Selección para consultar |
|---|---|
| ![Detección del Vortex Mixer](Foto%20evidencia/fotodereconocimientodeldispositivo.png) | ![Equipo seleccionado](Foto%20evidencia/fotodereconocimientoalaplatarelbotondelaparato.png) |

| Pregunta al asistente | Esperando la respuesta de la IA |
|---|---|
| ![Escribiendo la pregunta](Foto%20evidencia/fotomostrandolapeticionoconsultaqueserealizara.png) | ![Cargando respuesta](Foto%20evidencia/fotodecargaesperandolarespuestadelaia.png) |

## Arquitectura

```
Cámara (CameraX) → YoloDetector (TFLite) → DetectionOverlayView (cajas + %)
                                          → selección de equipo
                                          → EquipmentQaRepository
                                               ├── EquipmentDocumentProvider (docs_equipos/*.txt)
                                               ├── LocalEquipmentInfoProvider (info_equipos.json, respaldo)
                                               ├── KeywordSearch (recuperación de fragmentos RAG
                                               │   + búsqueda por palabras clave sin IA)
                                               ├── GeminiNanoService (LLM on-device, ML Kit GenAI)
                                               └── GeminiCloudService (respaldo en la nube, Google AI SDK)
```

El modelo de detección y la recuperación de fragmentos corren enteramente en el dispositivo. Para
responder preguntas, la app intenta en cascada: Gemini Nano on-device (requiere Android 14+ con
AICore) → Gemini Cloud (requiere internet + API key, para el resto de dispositivos) → búsqueda
por palabras clave sin IA (si no hay internet o falla la nube). La recuperación de fragmentos
(`KeywordSearch.retrieveFragments`) evita mandar el documento completo del equipo al LLM — solo
los 2-3 párrafos más relevantes para la pregunta viajan al prompt.

## Clases del detector (19)

`labels.txt`: Agitador_Orbital_DLAB, Autoclave, Balanza_Analitica_Ohaus,
Balanza_Granataria_Ohaus, Camara_Extractora_Biobase, Centrifuga_Ohaus_Frontier,
Contador_Colonias_CC1, Destilador_agua, Estufa_Memmert, Horno_Secado_Biobase, Incubadora,
Memmert_Unidad2, Microscopio_Binocular, Microscopio_camara, Microscopio_estereo,
Phmetro_Ohaus, Plancha_Agitacion_Cimarec, Sistema_Rotaevaporacion, Vortex_Mixer_LabNet.

## Estructura del repositorio

- `app/` — proyecto Android (Kotlin, CameraX, TensorFlow Lite, ML Kit GenAI).
- `training/train_yolo_quibio.ipynb` — notebook de entrenamiento (Colab): descarga el dataset
  anotado desde Roboflow, entrena YOLO11n, valida (mAP50/mAP50-95) y exporta a `.tflite`.
- `PROYECTO APPMOVIL.../` — fotografías originales del dataset, organizadas por carpeta de
  clase (~740 imágenes). El etiquetado (bounding boxes) y el split 70/15/15
  train/valid/test se hicieron en Roboflow (proyecto `azambranoy-uteq-edu-ec/qui-bio`); no
  están versionados en este repo.
- `Documentos de aparatos de laboratorio/` — manuales/fichas técnicas en PDF, fuente de los
  `.txt` en `app/src/main/assets/docs_equipos/` usados por el RAG.
- `Foto evidencia/` — capturas de pantalla de la app en uso (ver sección Demo).

## Cómo correr

1. Agregar a `local.properties` (no versionado): `GEMINI_API_KEY=<tu API key de
   https://aistudio.google.com/apikey>`.
2. Abrir en Android Studio, conectar un dispositivo físico (o emulador con cámara).
3. Ejecutar la app. El chat con IA usa Gemini Nano en dispositivos compatibles (Android 14+
   con AICore, ej. Pixel 8/9, Samsung S24+/S25+); en el resto de dispositivos usa
   automáticamente Gemini Cloud (requiere internet), y si tampoco hay conexión, cae a un modo
   de búsqueda por palabras clave sin IA.

## Estado respecto al enunciado — pendientes conocidos

- Los `.txt` de `docs_equipos/` cubren 11 de las 19 clases; el resto usa `info_equipos.json`
  como respaldo (ver `app/src/main/assets/docs_equipos/README.md` para el detalle de qué falta
  y por qué).
- No se versiona el split 70/15/15 ni las anotaciones (bounding boxes) en este repo; viven en
  el proyecto de Roboflow usado por el notebook de entrenamiento.
- No hay resultados de evaluación (mAP) commiteados; el notebook los calcula en Colab pero hay
  que exportarlos manualmente como evidencia.
