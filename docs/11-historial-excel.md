# 11 — Historial académico en Excel (`historial-academico.xlsx`)

Integración con el libro Excel que la secretaría ya usa para llevar el historial académico. SIGA lo **lee al
arrancar**, lo **re-lee a pedido** del administrador y **escribe de vuelta** los cambios académicos que se hacen
desde el sistema. Código: paquete `com.instituto.siga.excel`.

## 1. Ubicación y configuración

| Qué | Valor |
|---|---|
| Ubicación por defecto | `data/historial-academico.xlsx` **en la raíz del proyecto** (la carpeta que contiene `backend/` y `frontend/`) |
| Propiedad | `app.historial.excel-path` (`application.yml`) |
| Variable de entorno | `HISTORIAL_EXCEL_PATH` (ruta absoluta o relativa) |
| Plantilla vacía de respaldo | `backend/src/main/resources/historial-academico-plantilla.xlsx` (versionada, sin alumnos) |

**Cómo se resuelve la ruta** (`HistorialExcelPath`): una ruta **absoluta** se respeta tal cual. Una ruta
**relativa** (el default `./data/historial-academico.xlsx`) no depende de desde dónde se arranque: si el directorio
de trabajo es la raíz del proyecto o la carpeta `backend/`, se ancla a la raíz del proyecto (la que contiene
`backend/pom.xml`); en cualquier otro caso se resuelve contra el directorio de trabajo. Así `mvnw spring-boot:run`
desde `backend/` y desde la raíz leen **el mismo** archivo. La ruta efectiva se informa en el log de arranque y en
el campo `archivo` de la respuesta de `POST /api/v1/historial-academico/recargar`.

Para usar otro archivo:

```bash
# Git Bash
export HISTORIAL_EXCEL_PATH="C:/datos/siga/historial-academico.xlsx"
```
```powershell
# PowerShell (solo para esa sesión)
$env:HISTORIAL_EXCEL_PATH = "C:\datos\siga\historial-academico.xlsx"
```

`/data/` está en `.gitignore`: una vez cargado, el archivo tiene DNI y notas reales y **no se versiona**.

## 2. Estructura (tomada del ejemplo original `RESUMEN ACADEMICO TODAS LAS CARRERAS.xlsx`)

El archivo del repositorio conserva **las 9 hojas y todos los encabezados del ejemplo original**, sin ningún
alumno. Una hoja = una carrera/plan; el nombre de la hoja es `CÓDIGO_RESOLUCIÓN_AÑO`:

`ALIM_R11297_97`, `PROG_R6175_03`, `ENF_R854_16`, `EMER_R42_17`, `LAC_R205_18`, `IQUI_R5620_19`,
`SIST_R6790_19`, `CDIA_R2730_22`, `ALIM_R5563_23`.

Dentro de cada hoja:

| Fila (Excel) | Contenido |
|---|---|
| 1 | Encabezado: `DNI` (en `PROG`, `ENF`, … la celda A1; en `ALIM_R11297_97` está en blanco), `APELLIDO/S`, `NOMBRE/S`, `COHORTE` y, **por cada materia del plan, tres columnas consecutivas**: `N - MATERIA CURSADA`, `N - MATERIA FECHA`, `N - MATERIA FINAL`. Después, columnas de resumen (`PROMEDIO`, `CANTIDAD MATERIAS APROBADAS`, `TOTAL FINALES …`). |
| 2 | Rótulos `DNI` / `APELLIDO/S` / `NOMBRE/S` (la etiqueta `DNI` de la columna A está acá en `ALIM_R11297_97`). **No es una fila de datos.** |
| 3 en adelante | **Un alumno por fila.** Las filas vacías intercaladas se ignoran (no tienen DNI). |

Significado de las tres columnas de cada materia:

| Columna | Contenido | Ejemplo |
|---|---|---|
| `… CURSADA` | Estado de la materia para el alumno | `APROBADA`, `REGULAR`, `CURSANDO`, `LIBRE`, `PENDIENTE` |
| `… FECHA` | Fecha del final / aprobación | fecha de Excel (`25-11-25`) |
| `… FINAL` | Nota del final (1–10) | `8` |

Lo que SIGA reconoce como estado es el enum `EstadoMateria` (`PENDIENTE`, `CURSANDO`, `REGULAR`, `APROBADA`,
`LIBRE`). El archivo original usa además `EQUIVALENCIA`, `RECURSA` y la marca `-----------` (sin dato): ver §7.

### Cómo agregar o modificar registros a mano

1. Cerrá el archivo en Excel y, de preferencia, hacé una copia.
2. En la hoja de la carrera, escribí el alumno en la **primera fila libre desde la fila 3**: `DNI` (solo dígitos, como
   número o texto), `APELLIDO/S`, `NOMBRE/S`, `COHORTE`.
3. En las columnas de cada materia: `CURSADA` con **uno de los estados de arriba (en mayúsculas)**, `FECHA` y `FINAL`.
4. **No cambies ni reordenes los encabezados de la fila 1**: el nombre de materia (sin el `N - ` del orden) es lo que
   enlaza la columna con la materia cargada en SIGA.
5. Guardá, cerrá Excel y en SIGA (administrador → Configuración) usá **Recargar historial desde Excel**, o reiniciá
   el backend.

## 3. Qué se lee y qué se escribe

**Se lee** (Excel → base), al iniciar el backend (`ApplicationReadyEvent`) y con `POST /api/v1/historial-academico/recargar`
(solo ADMINISTRADOR, auditado): por cada fila con DNI, el alumno (se busca por DNI exacto entre los alumnos ya
existentes) y por cada materia con datos, el `EstadoAcademicoMateria` (estado + nota). Los datos de acceso
(contraseñas, tokens) **nunca** están ni se leen del Excel.

**Se escribe** (base → Excel) **todo cambio del estado académico**, venga de donde venga, por un único punto
(`HistorialExcelSincronizador`):

| Operación en SIGA | Qué queda en la celda de la materia |
|---|---|
| Inscripción a una cursada | `CURSANDO` (si estaba PENDIENTE) |
| Baja de la única inscripción activa en la materia, o cambio de comisión a otra materia | **se vacía** (vuelve a PENDIENTE) |
| Cursada aprobada / corregida | `REGULAR` / vuelve a `CURSANDO` |
| Final aprobado / corregido | `APROBADA` + nota + fecha / vuelve a `REGULAR` **borrando la nota y la fecha** |
| Admin fija el estado (`PUT /api/v1/alumnos/{id}/estado-academico/{materiaId}`) | ese estado; `PENDIENTE` vacía la celda |
| Importar un Excel subido (confirmado) | lo importado |

Las tres celdas de la materia (CURSADA / FECHA / FINAL) se escriben **juntas**: una nota o fecha que ya no corresponde se
borra (antes quedaba la vieja y, al reiniciar, se volvía a importar a la base). La fila del alumno nunca se borra.

**Se escribe después del commit** de la base, nunca antes: si la operación falla y la base hace rollback, el Excel no
queda con un dato que la base no tiene. Después de guardar, el archivo **se relee y se verifica** celda por celda; recién
entonces se informa éxito. Las respuestas de fijar estado, calificar cursada y registrar final incluyen
`excelSincronizado` y `excelMensaje`, y la interfaz avisa (en amarillo) si el cambio quedó solo en la base. Si el alumno
o la materia **no figuran en el Excel**, o el archivo está bloqueado/no existe, el mensaje lo dice (no se finge éxito).

**No se crean hojas ni columnas.** Si el alumno todavía no tiene fila (p. ej. lo dio de alta el administrador desde
SIGA), **se le agrega una fila** (DNI, APELLIDO/S, NOMBRE/S) en la hoja que tiene esa materia; si la materia está en
varias hojas, se usa la que empieza con el **código de la carrera** de la materia (`SIST` → `SIST_R6790_19`). Si aun así
no se puede decidir la hoja, no se adivina: se informa en `excelMensaje`. El nombre de la materia en SIGA tiene que
coincidir con el del encabezado (p. ej. `ALG` para `1 - ALG CURSADA`).

**Si una escritura falla** (Excel abierto, sin permisos): el dato queda en la base y se reintenta solo al reiniciar o al
usar *Recargar historial*: un cambio de la base con fecha posterior a la última modificación del archivo es un
pendiente, así que **no** se importa encima el valor viejo del Excel sino que se escribe el de la base. Si el archivo se
modificó después (alguien lo editó a mano), manda el Excel con las reglas de §4. Al iniciar, el log indica la ruta
efectiva y si hay permiso de lectura/escritura.

**Escritura segura:** se arma el libro en memoria, se vuelca a `…xlsx.tmp`, se copia el original a `…xlsx.bak` y recién
entonces se reemplaza el archivo. Si falla el original queda intacto y el `.tmp` se descarta. Un único candado serializa
lecturas y escrituras dentro del proceso; los cambios de una misma operación se escriben de una vez.

## 4. Fuente de verdad y resolución de conflictos

| Información | Fuente de verdad |
|---|---|
| Historial académico **anterior** a SIGA (estados y notas por materia) | **Excel** (se importa) |
| Usuarios, roles, mesas, inscripciones, comprobantes, auditoría | **Base de datos** (nunca están en el Excel) |
| Cambios académicos hechos desde SIGA | **Base de datos**, reflejados de vuelta en el Excel |

Reglas del importador (`HistorialExcelSyncService`), todas verificadas por tests:

- **Registro nuevo** (no existe en la base): se crea.
- **Registro existente**: se aplica lo que dice el Excel, **salvo conflicto**: si la base ya tiene la materia `APROBADA` y
  el Excel dice otro estado o una nota distinta, **se conserva la base**, se cuenta en `conflictos` y se lista como
  advertencia `CONFLICTO …`. Nunca se degrada ni se pisa una aprobación en silencio.
- **Celda en blanco / `-----`**: no borra nada de la base. **Nota ausente**: no borra la nota existente.
- **Fila eliminada del Excel**: no se borra nada de la base (el importador solo agrega o actualiza).
- **Duplicados**: el upsert es por (alumno, materia); sincronizar N veces da el mismo resultado (la segunda corrida informa
  `actualizados = 0`).
- **DNI sin alumno en el sistema** / **DNI que coincide con más de un alumno** / **materia inexistente o ambigua**: no se
  importa esa celda; se cuenta (`alumnosNoEncontrados`, `materiasNoEncontradas`) y se lista (hasta 50 advertencias).
- **Problemas de estructura** (materia repetida, columnas FECHA/FINAL ausentes): se informan como advertencia.

## 5. Qué pasa al reiniciar el backend

- **Default (`dev,local`): H2 guardada en disco** en `data/siga-db.mv.db` (raíz del proyecto, junto al Excel; otra
  ubicación con `SIGA_DB_PATH`). Todo lo creado desde la interfaz **persiste entre reinicios**. `data.sql` (usuarios demo
  y oferta de ejemplo) se carga solo la primera vez, con la base vacía. Para empezar de cero: frenar el backend y borrar
  `data/siga-db.mv.db`.
- **Solo `dev`** (tests con `@ActiveProfiles("dev")` y Docker): H2 **en memoria**, se reconstruye vacía en cada arranque
  (`create-drop` + `data.sql`). Ahí lo único que persiste es el Excel, y de él solo se re-importan los alumnos que existan
  en la base en ese momento.
- **Perfil `prod` (MySQL + Flyway):** usuarios, mesas e inscripciones persisten en MySQL; el Excel se re-sincroniza al
  arrancar según las reglas de §4.

## 6. Si el archivo se daña o desaparece

- **Falta el archivo:** al arrancar, SIGA lo crea **vacío** copiando la plantilla empaquetada (mismas hojas y
  encabezados) — nunca pisa uno existente.
- **Está dañado / no abre:** el backend sigue funcionando; la sincronización informa el error (`advertencias`) y los
  cambios se guardan solo en la base. Recuperación: (1) cerrá Excel; (2) probá con la última copia
  `historial-academico.xlsx.bak` (se genera en cada escritura): renombrala quitando `.bak`; (3) si tampoco sirve, borrá el
  archivo dañado y reiniciá (se crea vacío) o copiá a mano `backend/src/main/resources/historial-academico-plantilla.xlsx`
  a la ruta configurada; luego re-cargá los alumnos desde tu fuente.
- **Está abierto en Excel:** la lectura suele funcionar, pero la escritura falla con un mensaje claro y el original no se
  modifica. Cerralo y repetí el cambio.

## 7. Limitaciones conocidas (no se inventaron reglas)

- Los estados `EQUIVALENCIA` y `RECURSA` del archivo original **no los modela SIGA**: se informan una vez por valor y no
  cambian el estado. Si deben contar como aprobada/cursando, hace falta una decisión institucional.
- El nombre de materia del encabezado debe coincidir (ignorando mayúsculas y el prefijo `N - `) con el **nombre** de una
  Materia cargada en SIGA (`MTM 1`, `ALG`, …). Los nombres del ejemplo original son abreviaturas: hay que cargar las
  materias con esos mismos nombres (o ajustar el encabezado).
- Una materia repetida en la misma hoja (en `PROG_R6175_03`, `EDI` figura 3 veces: columnas 8, 17 y 24) no se distingue por
  nombre: se usa **la primera columna** y se informa.
- El encabezado `15 - SO FECHAS` (plural) del original se tolera.
- `Alumno` no tiene carrera mapeada en el modelo: la hoja de un alumno nuevo se deduce de la materia (columna) y, si esa
  materia está en varias hojas, del código de su carrera. Una materia cuyo nombre no coincide con ningún encabezado
  (p. ej. "Base de Datos" de los datos demo) nunca se escribe en el Excel; queda solo en la base.
- Las fórmulas de resumen (`PROMEDIO`, …) no están en las filas de la plantilla; Excel las recalcula si se copian desde una fila existente.

## 8. Pruebas

Ninguna prueba toca `data/historial-academico.xlsx`: cada contexto de Spring de los tests usa un Excel temporal propio
creado desde la plantilla (`src/test/resources/config/application.yml`).

- `HistorialExcelWriterCrudTest`: crear / modificar (incluida la corrección que borra nota y fecha) / eliminar, sin
  duplicados, escritura en lote, 30 escrituras seguidas sin corromper el archivo ni perder filas, respaldo válido.
- `HistorialExcelPersistenciaIT` (API real): pruebas 1, 2, 3 y 5 verificando base y archivo, y el reintento de un cambio
  que no había llegado al Excel.
- `HistorialExcelReinicioIT`: prueba 4, **reinicio real** (levanta la aplicación con base H2 en disco, crea datos,
  la detiene, la vuelve a levantar sobre los mismos archivos y verifica base y Excel).
- `HistorialExcelSyncIT`, `HistorialExcelWriterAltaTest`, `HistorialExcelPlantillaTest` (valida la plantilla
  empaquetada), `HistorialExcelPathTest`, `HistorialExcelBootstrapTest`, `HistorialImportacionIT`.
