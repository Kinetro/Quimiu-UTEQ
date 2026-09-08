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

**Resultado final** — respuesta de Gemini Cloud citando el dato correcto del manual (3,400 rpm):

![Respuesta final de la IA](Foto%20evidencia/resultadofinal.png)

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
- `training/weights/best.pt` — modelo original entrenado (YOLO11n, formato PyTorch).
- `Qui-bio.v1i.yolov8/` — dataset exportado desde Roboflow (proyecto
  `azambranoy-uteq-edu-ec/qui-bio`, licencia CC BY 4.0): 584 imágenes ya divididas en
  `train/` (409), `valid/` (88) y `test/` (87) — split 70/15/15 —, cada una con su anotación de
  cuadros delimitadores en formato YOLO (`labels/*.txt`) y `data.yaml` con las 19 clases.
- `PROYECTO APPMOVIL.../` — fotografías originales sin anotar, organizadas por carpeta de clase
  (~740 imágenes, 1.8 GB). Es la materia prima antes de anotar/dividir en Roboflow; **no está
  versionada en este repo por su tamaño** (ver `.gitignore`) — se comparte aparte si se necesita.
- `Documentos de aparatos de laboratorio/` — manuales/fichas técnicas en PDF, fuente de los
  `.txt` en `app/src/main/assets/docs_equipos/` usados por el RAG.
- `Foto evidencia/` — capturas de pantalla de la app en uso en un teléfono real (ver sección Demo).

## Cómo correr

1. Agregar a `local.properties` (no versionado): `GEMINI_API_KEY=<tu API key de
   https://aistudio.google.com/apikey>`.
2. Abrir en Android Studio, conectar un dispositivo físico (o emulador con cámara).
3. Ejecutar la app. El chat con IA usa Gemini Nano en dispositivos compatibles (Android 14+
   con AICore, ej. Pixel 8/9, Samsung S24+/S25+); en el resto de dispositivos usa
   automáticamente Gemini Cloud (requiere internet), y si tampoco hay conexión, cae a un modo
   de búsqueda por palabras clave sin IA.

## Entregables comunes

| Entregable | Estado | Dónde está |
|---|---|---|
| Repositorio del proyecto Android | ✅ | Este repositorio. |
| Conjunto de datos organizado y documentado | ✅ | `Qui-bio.v1i.yolov8/` (584 imágenes, split 70/15/15, con `README.dataset.txt`/`README.roboflow.txt`). |
| Anotaciones con cuadros delimitadores | ✅ | `Qui-bio.v1i.yolov8/{train,valid,test}/labels/*.txt` (formato YOLO, una por imagen). |
| Código o cuaderno de entrenamiento | ✅ | `training/train_yolo_quibio.ipynb`. |
| Modelo original y modelo .tflite | ✅ | `training/weights/best.pt` (original) y `app/src/main/assets/yolo_model.tflite` (exportado). |
| Aplicación Android instalable | ✅ | [APK instalable (Drive)](https://drive.google.com/drive/folders/1BYnTFkEswS9VTjbvgOUfY5oSenG9feNO?usp=sharing) — también se compila localmente con `./gradlew assembleDebug`. |
| Evidencias de funcionamiento en un teléfono real | ✅ | `Foto evidencia/` (5 capturas, ver sección Demo). |
| Video de demostración | ✅ | Enlace en la sección Demo. |

Nota del enunciado: "Todos los proyectos mantienen exactamente la misma arquitectura y
evaluación. Solamente cambian el laboratorio, las clases de equipos, las fotografías
recolectadas y los manuales o guías incorporados al RAG" — este proyecto usa esa arquitectura
común (cámara → YOLO → RAG) aplicada al Laboratorio de Biotecnología de la UTEQ, con sus 19
clases de equipos y los manuales propios en `Documentos de aparatos de laboratorio/`.

### Otros pendientes conocidos (no forman parte de los entregables comunes)

- Los `.txt` de `docs_equipos/` cubren 11 de las 19 clases; el resto usa `info_equipos.json`
  como respaldo (ver `app/src/main/assets/docs_equipos/README.md` para el detalle de qué falta
  y por qué).
- No hay resultados de evaluación (mAP) commiteados; el notebook los calcula en Colab pero hay
  que exportarlos manualmente como evidencia.
