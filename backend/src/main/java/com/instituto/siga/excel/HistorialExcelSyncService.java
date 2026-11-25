package com.instituto.siga.excel;

import com.instituto.siga.audit.AuditoriaPublisher;
import com.instituto.siga.dto.response.HistorialImportResultResponse;
import com.instituto.siga.entity.Alumno;
import com.instituto.siga.entity.EstadoAcademicoMateria;
import com.instituto.siga.entity.EstadoMateria;
import com.instituto.siga.entity.Materia;
import com.instituto.siga.repository.AlumnoRepository;
import com.instituto.siga.repository.EstadoAcademicoMateriaRepository;
import com.instituto.siga.repository.MateriaRepository;
import com.instituto.siga.repository.PlanMateriaRepository;
import com.instituto.siga.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Orquesta la sincronizacion .xlsx -> base de datos: lee el historial academico desde el Excel
 * configurado y hace upsert de EstadoAcademicoMateria para cada (alumno, materia) que reconoce.
 *
 * <h3>Fuente de verdad</h3>
 * El Excel es la fuente del <b>historial academico pasado</b> (estado de cursada y nota final por materia).
 * La base es la fuente de todo lo demas (usuarios, mesas, inscripciones) y de los cambios academicos hechos
 * desde SIGA, que se reflejan de vuelta en el Excel (EstadoAcademicoServiceImpl.fijarEstado). Reglas:
 * <ul>
 *   <li>Registro nuevo en el Excel (no existe en la base): se crea.</li>
 *   <li>Registro existente: se aplica lo que dice el Excel, salvo <b>conflicto</b>: si la base ya tiene la
 *       materia APROBADA y el Excel dice otro estado, o una nota distinta, se conserva la base y se
 *       registra el conflicto (nunca se degrada ni se pisa en silencio una aprobacion).</li>
 *   <li>Celda en blanco o con marca de "sin dato" ({@code -----}): no borra nada de la base.</li>
 *   <li>Nota ausente en el Excel: no borra la nota de la base.</li>
 *   <li>Fila eliminada del Excel: no se borra nada de la base (el import solo agrega o actualiza).</li>
 *   <li>Mismo (DNI, materia) repetido en varias hojas/filas: se aplican en orden de lectura; como el upsert
 *       es por (alumno, materia), repetir la sincronizacion no genera duplicados.</li>
 *   <li>Un DNI que no corresponde a ningun Alumno del sistema NO se importa (no se crea un Usuario: el
 *       archivo no trae email/legajo, obligatorios). Una materia que no matchea (exacto, sin el prefijo
 *       ordinal, case-insensitive) ninguna Materia cargada tampoco (el alta es del ABM de administracion).</li>
 *   <li><b>Cambio de la base todavia no escrito en el Excel</b> (la escritura fallo, p. ej. con el archivo abierto en
 *       Excel): si la fecha de actualizacion de la base es POSTERIOR a la ultima modificacion del archivo y los
 *       valores difieren, el dato vigente es el de la base: no se importa el valor viejo del Excel y se vuelve a
 *       escribir el de la base en el archivo. Si el archivo se modifico despues (alguien lo edito), manda el Excel
 *       con las reglas de arriba.</li>
 *   <li>Valores de estado que SIGA no modela (p. ej. EQUIVALENCIA, RECURSA) se informan y no se importan.</li>
 * </ul>
 * Todo lo que no se pueda sincronizar se cuenta y se lista como advertencia en la respuesta.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HistorialExcelSyncService {

    private static final int MAX_ADVERTENCIAS = 50;

    private final HistorialExcelReader reader;
    private final HistorialExcelPath historialExcelPath;
    private final HistorialExcelBootstrap bootstrap;
    private final AlumnoRepository alumnoRepository;
    private final MateriaRepository materiaRepository;
    private final EstadoAcademicoMateriaRepository estadoRepository;
    private final AuditoriaPublisher auditoriaPublisher;
    private final HistorialExcelSincronizador sincronizador;
    private final PlanMateriaRepository planMateriaRepository;

    /** El arranque del sistema nunca debe depender de que el Excel exista o tenga el formato
     * esperado: cualquier problema queda logueado, no tumba el contexto de Spring. */
    @EventListener(ApplicationReadyEvent.class)
    public void sincronizarAlIniciar() {
        try {
            Path archivo = historialExcelPath.resolver();
            bootstrap.asegurarArchivo(archivo);
            log.info("Historial academico Excel: {} (existe: {}, lectura: {}, escritura: {})", archivo,
                    Files.exists(archivo), Files.isReadable(archivo), Files.isWritable(archivo));
            if (Files.exists(archivo) && !Files.isWritable(archivo)) {
                log.error("SIN PERMISO DE ESCRITURA sobre {}: los cambios academicos quedaran solo en la base hasta "
                        + "que se corrija el permiso (se reescriben al reiniciar)", archivo);
            }
            HistorialImportResultResponse resultado = ejecutar();
            log.info("Historial academico sincronizado desde Excel al iniciar ({}): {} hoja(s), {} fila(s), "
                            + "{} actualizado(s), {} alumno(s) no encontrado(s), {} materia(s) no encontrada(s), {} conflicto(s)",
                    resultado.archivo(), resultado.hojasProcesadas(), resultado.filasLeidas(), resultado.actualizados(),
                    resultado.alumnosNoEncontrados(), resultado.materiasNoEncontradas(), resultado.conflictos());
            resultado.advertencias().stream().limit(10).forEach(a -> log.warn("Historial Excel: {}", a));
        } catch (Exception e) {
            log.error("No se pudo sincronizar el historial academico desde Excel al iniciar", e);
        }
    }

    /** Version expuesta por API (ADMIN): re-lee el archivo desde disco ahora mismo, por si alguien
     * lo edito externamente, y audita la operacion. */
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @Transactional
    public HistorialImportResultResponse sincronizar() {
        HistorialImportResultResponse resultado = ejecutar();
        auditoriaPublisher.publicar(SecurityUtils.usuarioActualId(), "HISTORIAL_EXCEL_SINCRONIZADO",
                "EstadoAcademicoMateria", null,
                Map.of(
                        "hojasProcesadas", resultado.hojasProcesadas(),
                        "filasLeidas", resultado.filasLeidas(),
                        "actualizados", resultado.actualizados(),
                        "alumnosNoEncontrados", resultado.alumnosNoEncontrados(),
                        "materiasNoEncontradas", resultado.materiasNoEncontradas(),
                        "conflictos", resultado.conflictos()));
        return resultado;
    }

    private HistorialImportResultResponse ejecutar() {
        Path archivo = historialExcelPath.resolver();
        String ruta = archivo.toString();
        List<String> advertencias = new ArrayList<>();

        if (!Files.exists(archivo)) {
            advertencias.add("No existe el archivo configurado: " + archivo);
            return new HistorialImportResultResponse(0, 0, 0, 0, 0, LocalDateTime.now(), advertencias, 0, ruta);
        }

        Instant modificacionArchivo;
        List<HistorialExcelRow> filas;
        try {
            modificacionArchivo = Files.getLastModifiedTime(archivo).toInstant();
            filas = reader.leer(archivo);
        } catch (Exception e) {
            log.error("No se pudo leer el historial academico desde {}", archivo, e);
            advertencias.add("El archivo no se pudo leer (formato invalido, corrupto o bloqueado por otro programa): "
                    + e.getMessage());
            return new HistorialImportResultResponse(0, 0, 0, 0, 0, LocalDateTime.now(), advertencias, 0, ruta);
        }

        try {
            reader.problemasDeEstructura(archivo).forEach(p -> agregarAdvertencia(advertencias, p));
        } catch (Exception e) {
            log.warn("No se pudo revisar la estructura de los encabezados de {}", archivo, e);
        }

        Set<String> hojas = new HashSet<>();
        Set<String> estadosNoReconocidos = new HashSet<>();
        int actualizados = 0;
        int alumnosNoEncontrados = 0;
        int materiasNoEncontradas = 0;
        int conflictos = 0;

        // Cambios de la base posteriores a la ultima escritura del archivo: candidatos a "pendientes de escribir".
        LocalDateTime desde = LocalDateTime.ofInstant(modificacionArchivo, ZoneId.systemDefault());
        Map<String, EstadoAcademicoMateria> masNuevosQueElArchivo = new HashMap<>();
        for (EstadoAcademicoMateria e : estadoRepository.findActualizadosDespuesDe(desde)) {
            masNuevosQueElArchivo.put(clave(e.getAlumno().getUsuarioId(), e.getMateria().getId()), e);
        }
        Set<String> vistosEnElExcel = new HashSet<>();
        List<EstadoAcademicoMateria> paraReescribir = new ArrayList<>();

        for (HistorialExcelRow fila : filas) {
            hojas.add(fila.hoja());

            List<Alumno> candidatos = alumnoRepository.buscar(null, fila.dni(), null, null);
            if (candidatos.size() != 1) {
                alumnosNoEncontrados++;
                agregarAdvertencia(advertencias, "DNI " + fila.dni() + " (" + fila.hoja() + "): "
                        + (candidatos.isEmpty() ? "no existe como alumno en el sistema" : "coincide con mas de un alumno"));
                continue;
            }
            Alumno alumno = candidatos.get(0);

            for (HistorialExcelMateriaCelda celda : fila.materias()) {
                EstadoMateria estado = resolverEstado(celda.estadoTexto());
                if (estado == null && esValorConContenido(celda.estadoTexto())
                        && estadosNoReconocidos.add(celda.estadoTexto().trim().toUpperCase(Locale.ROOT))) {
                    agregarAdvertencia(advertencias, "Estado \"" + celda.estadoTexto().trim()
                            + "\" no modelado por SIGA (hoja " + fila.hoja() + "): esas celdas no cambian el estado");
                }

                List<Materia> materias = materiaRepository.findByNombreIgnoreCase(celda.nombreMateria());
                if (materias.size() != 1) {
                    materiasNoEncontradas++;
                    agregarAdvertencia(advertencias, "Materia \"" + celda.nombreMateria() + "\" (" + fila.hoja() + "): "
                            + (materias.isEmpty() ? "no encontrada" : "nombre ambiguo, coincide con varias materias"));
                    continue;
                }
                Materia materia = materias.get(0);

                EstadoAcademicoMateria existente = estadoRepository
                        .findByAlumnoIdAndMateriaId(alumno.getUsuarioId(), materia.getId())
                        .orElse(null);

                String clave = clave(alumno.getUsuarioId(), materia.getId());
                vistosEnElExcel.add(clave);
                EstadoAcademicoMateria pendiente = masNuevosQueElArchivo.get(clave);
                if (pendiente != null && (pendiente.getEstado() != estado || !Objects.equals(pendiente.getNota(), celda.nota()))) {
                    // La base cambio despues de la ultima escritura del archivo: el Excel tiene un valor viejo.
                    paraReescribir.add(pendiente);
                    continue;
                }

                if (existente != null && hayConflicto(existente, estado, celda.nota())) {
                    conflictos++;
                    agregarAdvertencia(advertencias, "CONFLICTO DNI " + fila.dni() + " / \"" + celda.nombreMateria()
                            + "\": la base tiene " + existente.getEstado() + " (nota " + existente.getNota()
                            + ") y el Excel dice " + (estado == null ? "(sin estado)" : estado) + " (nota " + celda.nota()
                            + "); se conserva la base");
                    continue;
                }

                EstadoAcademicoMateria destino = existente != null ? existente
                        : EstadoAcademicoMateria.builder().alumno(alumno).materia(materia).build();
                boolean cambio = existente == null;

                if (estado != null && estado != destino.getEstado()) {
                    destino.setEstado(estado);
                    cambio = true;
                } else if (destino.getEstado() == null) {
                    destino.setEstado(EstadoMateria.PENDIENTE);
                }
                if (celda.nota() != null && !Objects.equals(celda.nota(), destino.getNota())) {
                    destino.setNota(celda.nota());
                    cambio = true;
                }
                if (cambio) {
                    destino.setFechaActualizacion(LocalDateTime.now());
                    estadoRepository.save(destino);
                    actualizados++;
                }
            }
        }

        // Cambios de la base que el Excel no tiene en absoluto (p. ej. una inscripcion cuyo guardado en el Excel fallo).
        masNuevosQueElArchivo.forEach((clave, e) -> {
            if (!vistosEnElExcel.contains(clave) && e.getEstado() != EstadoMateria.PENDIENTE) {
                paraReescribir.add(e);
            }
        });
        reescribirPendientes(paraReescribir, advertencias);

        return new HistorialImportResultResponse(hojas.size(), filas.size(), actualizados,
                alumnosNoEncontrados, materiasNoEncontradas, LocalDateTime.now(), advertencias, conflictos, ruta);
    }

    private static String clave(Long alumnoId, Long materiaId) {
        return alumnoId + "|" + materiaId;
    }

    /** Vuelca al Excel los cambios de la base que no habian llegado al archivo. Los que no corresponden al archivo
     * (materia sin columna) se ignoran en silencio: es lo esperado para materias que el Excel no lleva. */
    private void reescribirPendientes(List<EstadoAcademicoMateria> pendientes, List<String> advertencias) {
        if (pendientes.isEmpty()) {
            return;
        }
        List<HistorialExcelWriter.Cambio> cambios = new ArrayList<>();
        for (EstadoAcademicoMateria e : pendientes) {
            List<String> codigos = planMateriaRepository.buscarCodigosCarreraPorMateria(e.getMateria().getId());
            cambios.add(new HistorialExcelWriter.Cambio(e.getAlumno().getUsuario().getDni(), e.getMateria().getNombre(),
                    e.getEstado(), e.getNota(),
                    e.getEstado() == EstadoMateria.APROBADA && e.getFechaActualizacion() != null
                            ? e.getFechaActualizacion().toLocalDate() : null,
                    new HistorialExcelWriter.DatosAlumno(e.getAlumno().getUsuario().getApellido(),
                            e.getAlumno().getUsuario().getNombre(),
                            codigos == null || codigos.isEmpty() ? null : codigos.get(0))));
        }
        List<HistorialExcelWriter.Resultado> resultados = sincronizador.escribir(cambios);
        long escritos = resultados.stream().filter(r -> r.estado() == HistorialExcelWriter.Resultado.Estado.ESCRITO).count();
        long fallidos = resultados.stream().filter(r -> r.estado() == HistorialExcelWriter.Resultado.Estado.ERROR
                || r.estado() == HistorialExcelWriter.Resultado.Estado.ARCHIVO_INEXISTENTE).count();
        if (escritos > 0) {
            log.info("Historial Excel: se escribieron {} cambio(s) de la base que no estaban en el archivo", escritos);
            agregarAdvertencia(advertencias, escritos + " cambio(s) de la base que no habían llegado al Excel se escribieron ahora");
        }
        if (fallidos > 0) {
            agregarAdvertencia(advertencias, fallidos + " cambio(s) de la base siguen sin poder escribirse en el Excel: "
                    + resultados.stream().filter(r -> !r.escrito()).findFirst().map(HistorialExcelWriter.Resultado::mensaje).orElse(""));
        }
    }

    /** Una aprobacion ya registrada en la base no se degrada ni se reemplaza por otra cosa desde el Excel. */
    private boolean hayConflicto(EstadoAcademicoMateria existente, EstadoMateria estadoExcel, Integer notaExcel) {
        if (existente.getEstado() != EstadoMateria.APROBADA) {
            return false;
        }
        boolean estadoContradice = estadoExcel != null && estadoExcel != EstadoMateria.APROBADA;
        boolean notaContradice = notaExcel != null && existente.getNota() != null
                && !notaExcel.equals(existente.getNota());
        return estadoContradice || notaContradice;
    }

    private void agregarAdvertencia(List<String> advertencias, String mensaje) {
        if (advertencias.size() < MAX_ADVERTENCIAS) {
            advertencias.add(mensaje);
        }
    }

    private EstadoMateria resolverEstado(String texto) {
        if (texto == null || texto.isBlank()) return null;
        try {
            return EstadoMateria.valueOf(texto.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** "-----------" es la marca de "sin dato" del archivo original, no un estado desconocido. */
    private boolean esValorConContenido(String texto) {
        return texto != null && !texto.isBlank() && !texto.trim().matches("-+");
    }
}
