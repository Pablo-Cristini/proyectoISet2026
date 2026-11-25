# Informe de actualización SIGA — 16/11/2026

Base de trabajo: la versión anterior entregada (`Pablete-main-comisiones.zip`).

> **Estado de verificación (leer primero).** El **frontend compila** (`tsc -b` y `vite build` sin errores; `oxlint`
> solo muestra los 2 warnings que ya existían). El **backend NO se pudo compilar ni testear en este entorno**: el
> acceso a Maven Central está bloqueado, así que no hay dependencias (Spring, POI, H2…). Se verificó la **sintaxis**
> de los 283 archivos Java y se revisó el código a mano, pero **ningún test de backend se ejecutó todavía**. Los tests
> nuevos (incluidas las 5 pruebas de persistencia del Excel y un reinicio real del backend) están escritos y listos:
> hay que correr `./mvnw verify` en `backend/` (ver "Cómo verificar" al final). No doy por aprobado nada del backend
> hasta que eso pase.

---

## 1. Versión del plan de estudios: hasta 100 caracteres

**Problema:** la columna era `VARCHAR(10)` (entidad `length = 10` y migración V1) y no había límite en el DTO ni en el
formulario. Con más de 10 caracteres la base rechazaba el guardado. Además, en tu base local en disco,
`ddl-auto: update` **no agranda columnas**: aunque se cambiara la entidad, la columna seguiría en 10.

**Cambios:**
- `entity/PlanEstudio.java`: constante `LARGO_MAXIMO_VERSION = 100` y la columna con ese largo.
- `dto/request/PlanEstudioRequest.java`: `@Size(max = 100)` (crear y modificar).
- `db/migration/V7__…sql`: `ALTER TABLE plan_estudio MODIFY version VARCHAR(100)` (producción, MySQL).
- `config/LocalDatabaseConfig.java`: el mismo cambio sobre la H2 local existente, idempotente, al iniciar.
- `PlanesEstudioPage.tsx`: `max(100)` en el esquema, `maxLength` en el input y un contador "n/100".

**Tests:** `PlanEstudioControllerIT.version_de100Caracteres…` crea con 100 caracteres exactos, lo recupera completo,
lo modifica a otro de 100 y verifica que 101 da 400 (no se trunca).

## 2. Materias anuales además de cuatrimestrales

**Antes:** no existía la modalidad; `plan_materia.cuatrimestre` era obligatorio (1 o 2). La malla del plan no mostraba
año ni cuatrimestre, y **no había forma de editar ni de quitar una asociación** (solo crearla), aunque el
requerimiento pide verificar ambas cosas.

**Cambios:**
- `entity/ModalidadCursada.java` (nuevo): `CUATRIMESTRAL` / `ANUAL`.
- `entity/Materia.java`: campo `modalidad` (default `CUATRIMESTRAL`, con DEFAULT en la columna para que las materias
  existentes queden cuatrimestrales sin tocar nada).
- `entity/PlanMateria.java`: `cuatrimestre` pasa a `Integer` (null = anual).
- `MateriaRequest` / `MateriaResponse`: `modalidad` (si no se envía, es cuatrimestral: los clientes viejos siguen andando).
- `PlanMateriaRequest`: `cuatrimestre` opcional.
- `MateriaServiceImpl`:
  - Una materia cuatrimestral exige el cuatrimestre (`CUATRIMESTRE_REQUERIDO`); una anual no lo admite
    (`CUATRIMESTRE_NO_CORRESPONDE`).
  - Cambiar una materia de cuatrimestral a anual les quita el cuatrimestre en los planes. De anual a cuatrimestral,
    estando en planes, se rechaza (`MODALIDAD_CON_ASOCIACIONES`): no se adivina el cuatrimestre.
  - Nuevos `actualizarAsociacion`, `desasociarDePlan` y `listarDelPlan`. Quitar una materia del único plan de una
    carrera donde tiene comisiones se bloquea (`MATERIA_CON_COMISIONES`), porque dejaría esas comisiones inconsistentes.
- Endpoints nuevos:
  - `GET /api/v1/planes-estudio/{id}/materias`: malla con año, cuatrimestre y modalidad.
  - `PUT /api/v1/materias/{id}/plan/{planId}` y `DELETE /api/v1/materias/{id}/plan/{planId}`.
- Migración V7 y ajuste local: columna `modalidad` y `cuatrimestre` NULL-able.
- Frontend:
  - `MateriasPage`: selector Cuatrimestral/Anual y columna Modalidad.
  - `PlanesEstudioPage`: la malla muestra Año / Cuatrimestre ("Anual (todo el año)") / Modalidad, con editar y quitar.
    Al asociar una materia anual se oculta el cuatrimestre.
  - `ComisionesPage`: la modalidad aparece junto a la materia.

**Cuatrimestrales:** las existentes siguen igual (misma validación 1 o 2, mismo alta y asociación).

**Tests:**
- `MateriaServiceImplTest`: 7 casos nuevos.
- `PlanEstudioControllerIT.materiasAnualesYCuatrimestrales…`: crear, validaciones, ver la malla, editar
  asociaciones, modificar la materia, quitar, y usar una anual en una comisión.

## 3. Mostrar u ocultar la contraseña al crear un usuario

- `components/PasswordField.tsx` (nuevo): ícono de ojo. Arranca oculto, alterna con cada clic y vuelve a ocultarse al
  reabrir el formulario. Solo cambia el `type` del input.
- Se usa en: alta de usuario y "Crear nueva clave" (`UsuariosPage`), y en el autoregistro (`RegistroPage`, que también
  crea usuarios).
- **No se tocó** la autenticación ni el hasheo (BCrypt en el backend sigue igual).

## 4. DNI obligatorio de al menos 8 dígitos al crear usuarios

**Problema:** el alta de usuarios por el administrador **solo exigía que el DNI no estuviera vacío**, en el frontend
y en el backend. Se podía crear "abc" o "123". Ese usuario después no podía entrar, porque el login exige 7 a 9
dígitos. El autoregistro aceptaba 7.

**Cambios:**
- `validation/ReglasDni.java` (backend) y `utils/dni.ts` (frontend): regla única de alta `^\d{8,9}$`.
- Se aplica en `UsuarioRequest` (alta por el admin) y en `RegistroRequest`, y en ambos formularios. El campo de DNI
  limita a 9 caracteres.
- **Login:** sigue aceptando 7 a 9 dígitos, para no bloquear cuentas existentes con DNI de 7 dígitos. Como 8–9 está
  dentro de 7–9, **todo usuario que se cree puede iniciar sesión**. El tope de 9 es el que ya usaba el login.
- El mecanismo de autenticación no se modificó.

**Tests:** `UsuarioCarreraControllerIT.altaDeUsuario_conDniInvalido…` rechaza 7 dígitos, letras, puntos, espacios y
más de 9. Además crea usuarios con 8 y con 9 dígitos y verifica que **inicien sesión**.

## 5. Persistencia del archivo `data/historial-academico.xlsx` (prioridad crítica)

### 5.1 Análisis: causas reales encontradas

| # | Causa | Efecto |
|---|---|---|
| 1 | La escritura ocurría **dentro de la transacción**, antes del commit | Si la operación fallaba y la base hacía rollback, el Excel quedaba con un dato que la base no tenía, y se reimportaba al reiniciar |
| 2 | Al corregir un final aprobado (APROBADA → REGULAR) **la nota y la fecha viejas quedaban en el Excel** | Al reiniciar, la sincronización volvía a cargar la nota vieja en la base |
| 3 | **Si una escritura fallaba** (archivo abierto en Excel), al reiniciar el valor viejo del Excel **pisaba** el dato más nuevo de la base | Pérdida de datos |
| 4 | Inscribirse a una cursada (CURSANDO) **no se escribía** en el Excel | Operación de "crear" no reflejada |
| 5 | Darse de baja de una cursada **no actualizaba nada**: ni la base (quedaba CURSANDO para siempre) ni el Excel | Operación de "eliminar" no reflejada |
| 6 | Calificar cursada y registrar un final escribían el Excel pero **el resultado se descartaba** | La pantalla mostraba "guardado" aunque el Excel hubiera fallado |
| 7 | El import de un Excel subido actualizaba la base pero **no** el `data/historial-academico.xlsx` | Base y archivo desincronizados |
| 8 | Los **tests escribían en tu `data/historial-academico.xlsx` real** cada vez que calificaban algo | Datos de prueba mezclados con datos reales |
| 9 | `HistorialExcelPlantillaTest` exigía que el archivo real **no tuviera alumnos** | Fallaba en cuanto el sistema guardaba datos reales, y en un clon nuevo (el archivo no se versiona) |
| 10 | No se verificaba lo escrito | No había forma de saber si el archivo había quedado bien |

**Lo que estaba bien:**
- **Ruta:** `HistorialExcelPath` ya anclaba la ruta relativa a la raíz del proyecto, arrancara desde donde arrancara.
- **Creación:** el archivo se crea desde la plantilla si no existe, y nunca se pisa uno existente.
- **Escritura y errores:** la escritura era atómica (`.tmp` + `.bak`) y los errores se registraban en el log.

### 5.2 Cómo se resolvió
- **`excel/HistorialExcelSincronizador.java` (nuevo):** único punto por el que los cambios llegan al Excel.
  - Escribe **después del commit**.
  - Junta los cambios de una misma operación en un solo abrir/guardar.
  - Informa el resultado al cliente.
- **`HistorialExcelWriter`:**
  - Escritura en lote.
  - Las celdas CURSADA / FECHA / FINAL se escriben siempre juntas: lo que no corresponde se borra.
  - `PENDIENTE` vacía la celda (eliminar); la fila del alumno se conserva.
  - Si el archivo ya tenía el dato, no lo toca.
  - **Después de guardar relee el archivo y verifica cada celda**; solo entonces informa éxito.
- **`EstadoAcademicoServiceImpl`:** todo cambio (inscripción, baja, cursada, final, ajuste del admin) pasa por el
  sincronizador. Nuevo `liberarCursada`: la baja o el cambio a otra materia devuelve CURSANDO a PENDIENTE en la base y
  en el Excel.
- **`HistorialExcelSyncService` (al iniciar y en "Recargar historial"):**
  - Si un dato de la base tiene fecha **posterior** a la última modificación del archivo y difiere, es un pendiente
    que no llegó: **no se importa el valor viejo y se reescribe el de la base**.
  - Si el archivo se modificó después (edición a mano), manda el Excel, como antes.
  - El log muestra la ruta efectiva y los permisos de lectura y escritura.
- **`HistorialExcelImportService`:** lo importado y confirmado también se escribe en el Excel en disco.
- **Respuestas:** fijar estado, calificar cursada y registrar final devuelven `excelSincronizado` y `excelMensaje`
  (`ConExcel`, `@JsonUnwrapped`: los campos existentes no cambian). La interfaz muestra un **aviso amarillo** si el
  cambio quedó solo en la base.
- **Tests:** `src/test/resources/config/application.yml` hace que cada contexto use un Excel temporal propio. Ningún
  test toca tu archivo real.

### 5.3 Lectura
Toda verificación de los tests relee el archivo con el mismo `HistorialExcelReader` del sistema. El sistema no depende
de nada en memoria: base en disco (`data/siga-db.mv.db`) y Excel en disco.

### 5.4 Pruebas obligatorias (escritas, **pendientes de ejecutar**)

| Prueba | Dónde |
|---|---|
| 1 Creación (base + archivo + lectura) | `HistorialExcelPersistenciaIT`, `HistorialExcelWriterCrudTest` |
| 2 Modificación (sin duplicados, reemplazo de nota y fecha, recargar no "resucita" la nota) | ídem |
| 3 Eliminación (base PENDIENTE, celdas vacías, fila conservada) | ídem, más tests unitarios de baja de inscripción |
| 4 **Reinicio real**: levanta la app con H2 en disco, crea por HTTP, la detiene, la vuelve a levantar sobre los mismos archivos y verifica base y Excel (sin re-sembrar `data.sql` ni duplicar filas) | `HistorialExcelReinicioIT` |
| 5 Integridad: hojas y estructura conservadas, 30 escrituras seguidas, `.bak` válido, sin pérdida de filas | `HistorialExcelWriterCrudTest`, `HistorialExcelPersistenciaIT` |
| Extra: cambio que no llegó al Excel se reescribe y no se pisa; edición externa posterior manda el Excel | `HistorialExcelPersistenciaIT` |

## 6. Comisiones: seleccionar materias en todas las comisiones

**Causa:** la regla de la entrega anterior ("una sola comisión por materia y carrera"). En el formulario dejaba
**deshabilitadas** las materias que ya tenían comisión, así que la segunda comisión de una carrera con pocas materias
no podía elegir ninguna. Por tu elección (una materia por comisión, repetible), se reemplazó por la regla original del
sistema adaptada a la carrera: **no repetir el mismo nombre en el mismo período para la misma materia y carrera**.
Se pueden crear A, B, C… sin límite.

**Cambios:**
- `entity/Comision.java`: nueva restricción única.
- `ComisionRepository`, `ComisionServiceImpl` y `ComisionCarreraBackfill`.
- V7 y ajuste local: se quita la restricción vieja.
- `ComisionesPage`: ninguna materia se bloquea; se muestra cuántas comisiones tiene ya cada materia.

**Tests:** `ComisionMesaExamenControllerIT` crea A, B, C, D de la misma materia y carrera, verifica el 409 al repetir
nombre y período, y que se puede repetir en otro período. `InscripcionCursadaControllerIT` vuelve a usar A y B de la
misma materia. Hay test unitario nuevo, y la materia anual se usa en una comisión (punto 2).

## 7. Compilación y tests

| Verificación | Resultado |
|---|---|
| Frontend `tsc -b` | ✅ sin errores |
| Frontend `vite build` | ✅ |
| Frontend `oxlint` | ✅ (2 warnings preexistentes) |
| Frontend tests | El proyecto no tiene tests de frontend (solo build y lint) |
| Backend: sintaxis de los 283 `.java` | ✅ |
| Backend `mvnw verify` (compilación, unitarios e integración) | ❌ **no ejecutado**: Maven Central bloqueado en el entorno |

`package-lock.json` no se tocó: el frontend se compiló en una copia aparte.

## 8. Archivos modificados

**Backend, nuevos:**
- `dto/response/ConExcel.java`, `dto/response/PlanMateriaResponse.java`
- `entity/ModalidadCursada.java`
- `excel/HistorialExcelSincronizador.java`
- `validation/ReglasDni.java`
- `db/migration/V7__version_plan_materias_anuales_comisiones.sql`
- Tests: `HistorialExcelPersistenciaIT`, `HistorialExcelReinicioIT`, `HistorialExcelWriterCrudTest`,
  `src/test/resources/config/application.yml`

**Backend, modificados:**
- Config: `ComisionCarreraBackfill`, `LocalDatabaseConfig`
- Controladores: `AlumnoController`, `InscripcionCursadaController`, `InscripcionFinalController`,
  `MateriaController`, `PlanEstudioController`
- DTOs: `MateriaRequest`, `PlanEstudioRequest`, `PlanMateriaRequest`, `RegistroRequest`, `UsuarioRequest`,
  `EstadoAcademicoResponse`, `MateriaResponse`
- Entidades: `Comision`, `Materia`, `PlanEstudio`, `PlanMateria`
- Excel: `HistorialExcelImportService`, `HistorialExcelSyncService`, `HistorialExcelWriter`
- `MateriaMapper`
- Repositorios: `ComisionRepository`, `EstadoAcademicoMateriaRepository`, `InscripcionCursadaRepository`,
  `PlanMateriaRepository`
- Servicios: `ComisionServiceImpl`, `EstadoAcademicoServiceImpl`, `InscripcionCursadaServiceImpl`,
  `MateriaServiceImpl`, y las interfaces `EstadoAcademicoService` y `MateriaService`
- 18 archivos de test existentes ajustados a las firmas nuevas (en `MateriaRequest` se agregó el parámetro
  `modalidad` como `null`; se mantuvo el escenario de cada test)

**Frontend:**
- Nuevos: `api/excelSync.ts`, `components/PasswordField.tsx`, `utils/dni.ts`
- Modificados:
  - APIs: `inscripcionCursadaApi.ts`, `inscripcionFinalApi.ts`, `materiasApi.ts`, `planesEstudioApi.ts`
  - Páginas: `LoginPage` (solo usa la constante compartida, sin cambio de comportamiento), `RegistroPage`,
    `ComisionesPage`, `InscriptosMesaPage`, `MateriasPage`, `PlanesEstudioPage`, `UsuariosPage`,
    `AcreditacionCursadasPage`

**Docs:** `docs/11-historial-excel.md`, `CLAUDE.md`, este informe.

## 9. Pendientes y limitaciones

1. **Correr los tests del backend** (`./mvnw verify`). Es el criterio de finalización y no se pudo hacer acá. Si algo
   falla, pasame la salida y lo corrijo.
2. La detección de "pendiente de escribir" compara la fecha de la base con la de modificación del archivo. Si el
   archivo está en una carpeta de red con otro reloj, o en un sistema de archivos con 2 s de resolución (FAT), el
   resultado puede ser impreciso en ediciones hechas en el mismo segundo.
3. El import de un Excel subido **no** aplica la regla de DNI de 8 dígitos: sigue aceptando los DNI del archivo
   histórico, que pueden tener 7, para no rechazar alumnos reales. Esos alumnos igual pueden iniciar sesión.
4. Una materia cuyo nombre en SIGA no coincide con ningún encabezado del Excel (p. ej. "Base de Datos" de los datos
   demo) se guarda en la base, y la respuesta avisa que no llegó al Excel. Es por diseño: no se crean columnas.

## Cómo verificar

```bash
cd backend
./mvnw verify          # compila y corre unitarios e integración (incluye las 5 pruebas del Excel)
```

Prueba manual:
1. Levantá el backend y el frontend.
2. Creá una materia **anual** y asociala a un plan.
3. Creá dos comisiones de la misma materia.
4. Calificá una cursada y mirá `data/historial-academico.xlsx`.
5. Cerrá las dos Git Bash, volvé a abrirlas y verificá que la base y el Excel conservan todo.
