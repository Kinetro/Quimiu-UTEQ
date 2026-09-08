# docs_equipos — fichas técnicas / manuales por equipo

Cada archivo debe llamarse EXACTAMENTE igual que la etiqueta del modelo TFLite
(ver `app/src/main/assets/labels.txt`), por ejemplo `Autoclave.txt`,
`Balanza_Granataria_Ohaus.txt`, etc. `EquipmentDocumentProvider` busca el
archivo por ese nombre exacto; si no existe, la app cae automáticamente al
resumen corto de `app/src/main/assets/info_equipos.json`.

## Archivos ya generados (extraídos automáticamente de "Documentos de aparatos
## de laboratorio/" con `pdftotext`)

| Archivo .txt                        | PDF origen                                                        | Confianza |
|--------------------------------------|--------------------------------------------------------------------|-----------|
| Contador_Colonias_CC1.txt            | boeco-colony-counter.pdf                                            | Alta (el PDF trae una página de un producto distinto —una bomba de vacío N96— pegada antes del datasheet real del CC-1; se dejó tal cual, no afecta la respuesta) |
| Plancha_Agitacion_Cimarec.txt        | Cimarec_Hotplates_Stirrers_user_manual.pdf                          | Alta |
| Agitador_Orbital_DLAB.txt            | DLAB_Shakers-non-incubating_SK-L180_SK-O180_manual.pdf              | Alta |
| Centrifuga_Ohaus_Frontier.txt        | Ohaus Frontier 5000 Series FC5707 FC5706P Instruction Manual.pdf    | Alta |
| Balanza_Analitica_Ohaus.txt          | ohaus-pioneer-manual.pdf                                            | Alta |
| Sistema_Rotaevaporacion.txt          | Operation-Manual-Rotary-Evaporator-Hei-VAP-Precision.pdf            | Alta |
| Estufa_Memmert.txt                   | UN110.pdf + tabla comparativa de D30237-TD-UN-SN-GG2012.pdf         | Alta (UN110 coincide con "Memmert, modelo UN 110" de info_equipos.json) |
| Memmert_Unidad2.txt                  | UN160pa.pdf + tabla comparativa de D30237-TD-UN-SN-GG2012.pdf       | **Media** — info_equipos.json marca esta unidad como "pendiente de confirmar" (¿estufa o incubadora?); UN160pa es una estufa de parafina, encaja pero **conviene confirmarlo con el encargado del laboratorio** |
| Balanza_Granataria_Ohaus.txt         | ohaus-scout-pro-balance-manual.pdf (descargado de conquerscientific.com) | **Media** — info_equipos.json solo especifica marca "OHAUS" sin modelo exacto; se eligió la Scout Pro por ser la balanza de plataforma OHAUS sin cabina de vidrio más común en laboratorios docentes. **Conviene confirmar el modelo real con el encargado del laboratorio** |
| Phmetro_Ohaus.txt                    | Ohaus-Starter-3100-pH-Meter-manual.pdf (descargado de dmx.ohaus.com, fuente oficial OHAUS) | **Media** — mismo caso: marca conocida (OHAUS) pero modelo no confirmado; se eligió el Starter 3100 (pHmetro de mesa) por ser un modelo común de laboratorio. **Conviene confirmar el modelo real** |
| Vortex_Mixer_LabNet.txt              | Labnet-Vortex-Mixer-CLSLN-AN-1042.pdf (descargado de labnetinternational.com, fuente oficial Labnet) | **Media** — info_equipos.json no especifica el número de catálogo; se usó el manual oficial del Labnet Vortex Mixer S0200 (el vórtex más común de Labnet). **Conviene confirmar el modelo real** |

## Búsqueda intentada sin éxito

Para `Camara_Extractora_Biobase` y `Horno_Secado_Biobase` (marca Biobase conocida
pero sin modelo exacto) se encontraron manuales candidatos en internet, pero:
- El manual de campana extractora (`BIOBASE-Fume-Hood-manual.pdf`, gsi-lab.com)
  es un PDF escaneado sin capa de texto (`pdftotext` no extrae nada) y no había
  herramienta de OCR disponible en este entorno para procesarlo.
- El manual del horno de secado solo se encontró alojado en Scribd (bloqueado
  para descarga directa/scraping) o en dominios que no resolvieron.

Ambas clases siguen usando el fallback de `info_equipos.json` hasta conseguir
un PDF descargable con capa de texto, o el modelo exacto para buscar mejor.

`D30237-TD-UN-SN-GG2012.pdf` es una tabla técnica genérica de la serie UN/SN
de Memmert (tamaños 30/55/75/110/160/260/450/750); se anexó completa al final
de `Estufa_Memmert.txt` y `Memmert_Unidad2.txt` porque cubre ambos tamaños
(110 y 160). Para responder preguntas de medidas exactas, buscar la columna
del tamaño correspondiente dentro de esa tabla.

## PDF sin mapear a ninguna clase actual

`Orbit Digital Shakers (CLSLN-AN-1010DOC REV3).pdf` (Labnet) es un agitador
orbital digital, pero **no coincide con ninguna de las 20 clases actuales**:
no es el mismo producto que `Agitador_Orbital_DLAB` (marca distinta) ni que
`Vortex_Mixer_LabNet` (un vórtex, no un agitador orbital). Decidir si:
  a) es en realidad el mismo equipo físico que una de las clases existentes
     (revisar con el encargado del laboratorio), o
  b) hace falta agregarlo como una clase nueva del modelo TFLite.
No se copió a esta carpeta hasta resolver esa duda.

## Clases SIN documento todavía (usan el fallback de info_equipos.json)

Autoclave, Camara_Extractora_Biobase, Destilador_agua, Horno_Secado_Biobase,
Incubadora, Microscopio_Binocular, Microscopio_camara,
Microscopio_invertido_con_camara, Microscopio_estereo.

De estas, `Autoclave`, `Destilador_agua`, `Incubadora` y los 3 microscopios ni
siquiera tienen marca/modelo confirmado en `info_equipos.json` ("pendiente de
confirmar") — antes de buscar un manual conviene confirmar esos datos con el
encargado del laboratorio, para no adjuntar el manual de un equipo distinto
por error.

Para completarlas: conseguir la ficha técnica/manual en PDF con capa de texto
(no escaneado), extraer el texto (por ejemplo con `pdftotext -layout archivo.pdf
salida.txt` en desktop, o con PdfBox-Android/iText si se prefiere hacerlo desde
la propia app Android) y guardarlo acá con el nombre exacto de la clase.
