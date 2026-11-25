package com.instituto.siga.service.impl;

import com.instituto.siga.audit.AuditoriaPublisher;
import com.instituto.siga.dto.response.EstadoAcademicoResponse;
import com.instituto.siga.entity.Alumno;
import com.instituto.siga.entity.EstadoAcademicoMateria;
import com.instituto.siga.entity.EstadoMateria;
import com.instituto.siga.entity.Materia;
import com.instituto.siga.exception.ResourceNotFoundException;
import com.instituto.siga.excel.HistorialExcelSincronizador;
import com.instituto.siga.excel.HistorialExcelWriter;
import com.instituto.siga.repository.AlumnoRepository;
import com.instituto.siga.repository.EstadoAcademicoMateriaRepository;
import com.instituto.siga.repository.MateriaRepository;
import com.instituto.siga.repository.PlanMateriaRepository;
import com.instituto.siga.security.SecurityUtils;
import com.instituto.siga.service.interfaces.EstadoAcademicoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EstadoAcademicoServiceImpl implements EstadoAcademicoService {

    private final EstadoAcademicoMateriaRepository estadoRepository;
    private final AlumnoRepository alumnoRepository;
    private final MateriaRepository materiaRepository;
    private final AuditoriaPublisher auditoriaPublisher;
    private final HistorialExcelSincronizador historialExcelSincronizador;
    private final PlanMateriaRepository planMateriaRepository;

    @Override
    @Transactional(readOnly = true)
    public EstadoMateria obtenerEstado(Long alumnoId, Long materiaId) {
        return estadoRepository.findByAlumnoIdAndMateriaId(alumnoId, materiaId)
                .map(EstadoAcademicoMateria::getEstado)
                .orElse(EstadoMateria.PENDIENTE);
    }

    @Override
    @Transactional
    public void marcarCursando(Long alumnoId, Long materiaId) {
        EstadoAcademicoMateria estado = estadoRepository.findByAlumnoIdAndMateriaId(alumnoId, materiaId).orElse(null);

        if (estado == null) {
            Alumno alumno = alumnoRepository.findById(alumnoId)
                    .orElseThrow(() -> new ResourceNotFoundException("Alumno no encontrado: " + alumnoId));
            Materia materia = materiaRepository.findById(materiaId)
                    .orElseThrow(() -> new ResourceNotFoundException("Materia no encontrada: " + materiaId));
            estadoRepository.save(EstadoAcademicoMateria.builder()
                    .alumno(alumno).materia(materia)
                    .estado(EstadoMateria.CURSANDO).fechaActualizacion(LocalDateTime.now())
                    .build());
            sincronizarConExcel(alumno, materia, EstadoMateria.CURSANDO, null);
        } else if (estado.getEstado() == EstadoMateria.PENDIENTE) {
            aplicar(estado, EstadoMateria.CURSANDO, estado.getNota());
        }
        // Si ya esta CURSANDO/REGULAR/APROBADA/LIBRE no se pisa: inscribirse de nuevo no retrocede el estado.
    }

    @Override
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @Transactional
    public EstadoAcademicoResponse fijarEstado(Long alumnoId, Long materiaId, EstadoMateria nuevoEstado, Integer nota) {
        Alumno alumno = alumnoRepository.findById(alumnoId)
                .orElseThrow(() -> new ResourceNotFoundException("Alumno no encontrado: " + alumnoId));
        Materia materia = materiaRepository.findById(materiaId)
                .orElseThrow(() -> new ResourceNotFoundException("Materia no encontrada: " + materiaId));

        EstadoAcademicoMateria estado = estadoRepository.findByAlumnoIdAndMateriaId(alumnoId, materiaId)
                .orElseGet(() -> EstadoAcademicoMateria.builder().alumno(alumno).materia(materia).build());
        estado.setEstado(nuevoEstado);
        estado.setNota(nota);
        estado.setFechaActualizacion(LocalDateTime.now());

        EstadoAcademicoMateria guardado = estadoRepository.save(estado);
        auditoriaPublisher.publicar(SecurityUtils.usuarioActualId(), "ESTADO_ACADEMICO_MODIFICADO",
                "EstadoAcademicoMateria", guardado.getId(),
                Map.of("alumnoId", alumnoId, "materiaId", materiaId, "estado", nuevoEstado.name()));

        sincronizarConExcel(alumno, materia, nuevoEstado, nota);

        // excelSincronizado/excelMensaje los completa el controlador: el Excel se escribe DESPUES del commit.
        return new EstadoAcademicoResponse(alumnoId, materiaId, materia.getNombre(), nuevoEstado.name(), nota,
                guardado.getFechaActualizacion(), false, null);
    }

    // Los 4 metodos siguientes los invocan servicios que ya verificaron el rol del personal; no tienen @PreAuthorize propio.

    @Override
    @Transactional
    public void acreditarCursada(Long alumnoId, Long materiaId) {
        EstadoAcademicoMateria estado = obtenerOCrear(alumnoId, materiaId);
        if (estado.getEstado() == null || estado.getEstado() == EstadoMateria.PENDIENTE
                || estado.getEstado() == EstadoMateria.CURSANDO || estado.getEstado() == EstadoMateria.LIBRE) {
            aplicar(estado, EstadoMateria.REGULAR, estado.getNota());
        }
    }

    @Override
    @Transactional
    public void revocarCursada(Long alumnoId, Long materiaId) {
        EstadoAcademicoMateria estado = obtenerOCrear(alumnoId, materiaId);
        if (estado.getEstado() == EstadoMateria.REGULAR) {
            aplicar(estado, EstadoMateria.CURSANDO, estado.getNota());
        }
    }

    @Override
    @Transactional
    public void acreditarFinal(Long alumnoId, Long materiaId, Integer nota) {
        aplicar(obtenerOCrear(alumnoId, materiaId), EstadoMateria.APROBADA, nota);
    }

    @Override
    @Transactional
    public void revocarFinal(Long alumnoId, Long materiaId) {
        EstadoAcademicoMateria estado = obtenerOCrear(alumnoId, materiaId);
        if (estado.getEstado() == EstadoMateria.APROBADA) {
            aplicar(estado, EstadoMateria.REGULAR, null);
        }
    }

    @Override
    @Transactional
    public void liberarCursada(Long alumnoId, Long materiaId) {
        estadoRepository.findByAlumnoIdAndMateriaId(alumnoId, materiaId)
                .filter(e -> e.getEstado() == EstadoMateria.CURSANDO)
                .ifPresent(e -> aplicar(e, EstadoMateria.PENDIENTE, e.getNota()));
    }

    private EstadoAcademicoMateria obtenerOCrear(Long alumnoId, Long materiaId) {
        return estadoRepository.findByAlumnoIdAndMateriaId(alumnoId, materiaId).orElseGet(() -> {
            Alumno alumno = alumnoRepository.findById(alumnoId)
                    .orElseThrow(() -> new ResourceNotFoundException("Alumno no encontrado: " + alumnoId));
            Materia materia = materiaRepository.findById(materiaId)
                    .orElseThrow(() -> new ResourceNotFoundException("Materia no encontrada: " + materiaId));
            return EstadoAcademicoMateria.builder().alumno(alumno).materia(materia)
                    .estado(EstadoMateria.PENDIENTE).fechaActualizacion(LocalDateTime.now()).build();
        });
    }

    private void aplicar(EstadoAcademicoMateria estado, EstadoMateria nuevo, Integer nota) {
        estado.setEstado(nuevo);
        estado.setNota(nota);
        estado.setFechaActualizacion(LocalDateTime.now());
        estadoRepository.save(estado);
        sincronizarConExcel(estado.getAlumno(), estado.getMateria(), nuevo, nota);
    }

    /** Programa la escritura hacia el .xlsx configurado; ocurre al confirmarse la transaccion (ver
     * HistorialExcelSincronizador). Si el alumno no tiene fila en el Excel (p. ej. lo dio de alta el admin desde
     * SIGA), el writer se la crea. La fecha solo se escribe al aprobar: es la fecha del final. Un problema armando
     * el cambio nunca rompe la operacion en base: queda logueado y la sincronizacion al iniciar lo reintenta. */
    private void sincronizarConExcel(Alumno alumno, Materia materia, EstadoMateria estado, Integer nota) {
        try {
            LocalDate fecha = estado == EstadoMateria.APROBADA ? LocalDate.now() : null;
            List<String> codigos = planMateriaRepository.buscarCodigosCarreraPorMateria(materia.getId());
            HistorialExcelWriter.DatosAlumno datos = new HistorialExcelWriter.DatosAlumno(
                    alumno.getUsuario().getApellido(), alumno.getUsuario().getNombre(),
                    codigos == null || codigos.isEmpty() ? null : codigos.get(0));
            historialExcelSincronizador.programar(new HistorialExcelWriter.Cambio(
                    alumno.getUsuario().getDni(), materia.getNombre(), estado, nota, fecha, datos));
        } catch (Exception e) {
            log.error("No se pudo programar la sincronizacion con el Excel (alumno {}, materia {})",
                    alumno.getUsuarioId(), materia.getId(), e);
        }
    }
}
