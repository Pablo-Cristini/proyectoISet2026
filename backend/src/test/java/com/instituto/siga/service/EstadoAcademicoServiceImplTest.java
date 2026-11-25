package com.instituto.siga.service;

import com.instituto.siga.audit.AuditoriaPublisher;
import com.instituto.siga.entity.Alumno;
import com.instituto.siga.entity.EstadoAcademicoMateria;
import com.instituto.siga.entity.EstadoMateria;
import com.instituto.siga.entity.Materia;
import com.instituto.siga.entity.Usuario;
import com.instituto.siga.excel.HistorialExcelSincronizador;
import com.instituto.siga.excel.HistorialExcelWriter;
import com.instituto.siga.repository.AlumnoRepository;
import com.instituto.siga.repository.EstadoAcademicoMateriaRepository;
import com.instituto.siga.repository.MateriaRepository;
import com.instituto.siga.service.impl.EstadoAcademicoServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EstadoAcademicoServiceImplTest {

    @Mock
    private EstadoAcademicoMateriaRepository estadoRepository;
    @Mock
    private AlumnoRepository alumnoRepository;
    @Mock
    private MateriaRepository materiaRepository;
    @Mock
    private AuditoriaPublisher auditoriaPublisher;
    @Mock
    private HistorialExcelSincronizador historialExcelSincronizador;
    @Mock
    private com.instituto.siga.repository.PlanMateriaRepository planMateriaRepository;

    private EstadoAcademicoServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new EstadoAcademicoServiceImpl(estadoRepository, alumnoRepository, materiaRepository,
                auditoriaPublisher, historialExcelSincronizador, planMateriaRepository);
        Usuario usuario = Usuario.builder().id(1L).dni("30111222").nombre("Ana").apellido("Paz").build();
        lenient().when(alumnoRepository.findById(1L)).thenReturn(Optional.of(Alumno.builder().usuarioId(1L).usuario(usuario).build()));
        lenient().when(materiaRepository.findById(1L)).thenReturn(Optional.of(Materia.builder().id(1L).nombre("Materia").build()));
    }

    @Test
    void obtenerEstado_sinFila_devuelvePendiente() {
        when(estadoRepository.findByAlumnoIdAndMateriaId(1L, 1L)).thenReturn(Optional.empty());

        assertThat(service.obtenerEstado(1L, 1L)).isEqualTo(EstadoMateria.PENDIENTE);
    }

    @Test
    void obtenerEstado_conFila_devuelveElEstadoGuardado() {
        when(estadoRepository.findByAlumnoIdAndMateriaId(1L, 1L))
                .thenReturn(Optional.of(EstadoAcademicoMateria.builder().estado(EstadoMateria.APROBADA).build()));

        assertThat(service.obtenerEstado(1L, 1L)).isEqualTo(EstadoMateria.APROBADA);
    }

    @Test
    void marcarCursando_sinFilaExistente_creaUnaNuevaEnCursando() {
        when(estadoRepository.findByAlumnoIdAndMateriaId(1L, 1L)).thenReturn(Optional.empty());

        service.marcarCursando(1L, 1L);

        ArgumentCaptor<EstadoAcademicoMateria> captor = ArgumentCaptor.forClass(EstadoAcademicoMateria.class);
        verify(estadoRepository).save(captor.capture());
        assertThat(captor.getValue().getEstado()).isEqualTo(EstadoMateria.CURSANDO);
    }

    @Test
    void marcarCursando_conFilaPendiente_laActualizaACursando() {
        EstadoAcademicoMateria fila = EstadoAcademicoMateria.builder().estado(EstadoMateria.PENDIENTE).build();
        when(estadoRepository.findByAlumnoIdAndMateriaId(1L, 1L)).thenReturn(Optional.of(fila));

        service.marcarCursando(1L, 1L);

        assertThat(fila.getEstado()).isEqualTo(EstadoMateria.CURSANDO);
        verify(estadoRepository).save(fila);
    }

    @Test
    void marcarCursando_conFilaYaRegular_noLaPisa() {
        EstadoAcademicoMateria fila = EstadoAcademicoMateria.builder().estado(EstadoMateria.REGULAR).build();
        when(estadoRepository.findByAlumnoIdAndMateriaId(1L, 1L)).thenReturn(Optional.of(fila));

        service.marcarCursando(1L, 1L);

        assertThat(fila.getEstado()).isEqualTo(EstadoMateria.REGULAR);
        verify(estadoRepository, never()).save(any());
    }

    @Test
    void fijarEstado_persisteYAudita() {
        when(estadoRepository.findByAlumnoIdAndMateriaId(1L, 1L)).thenReturn(Optional.empty());
        when(estadoRepository.save(any(EstadoAcademicoMateria.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.fijarEstado(1L, 1L, EstadoMateria.APROBADA, 8);

        assertThat(response.estado()).isEqualTo("APROBADA");
        verify(auditoriaPublisher, times(1)).publicar(any(), eq("ESTADO_ACADEMICO_MODIFICADO"), eq("EstadoAcademicoMateria"), any(), any());
    }

    private HistorialExcelWriter.Cambio cambioProgramado() {
        ArgumentCaptor<HistorialExcelWriter.Cambio> captor = ArgumentCaptor.forClass(HistorialExcelWriter.Cambio.class);
        verify(historialExcelSincronizador).programar(captor.capture());
        return captor.getValue();
    }

    private EstadoAcademicoMateria fila(EstadoMateria estado, Integer nota) {
        return EstadoAcademicoMateria.builder().id(7L)
                .alumno(alumnoRepository.findById(1L).orElseThrow())
                .materia(materiaRepository.findById(1L).orElseThrow())
                .estado(estado).nota(nota).build();
    }

    @Test
    void fijarEstado_programaLaEscrituraEnElExcel_conLaFechaDeAprobacion() {
        when(estadoRepository.findByAlumnoIdAndMateriaId(1L, 1L)).thenReturn(Optional.empty());
        when(estadoRepository.save(any(EstadoAcademicoMateria.class))).thenAnswer(inv -> inv.getArgument(0));

        service.fijarEstado(1L, 1L, EstadoMateria.APROBADA, 8);

        HistorialExcelWriter.Cambio cambio = cambioProgramado();
        assertThat(cambio.dni()).isEqualTo("30111222");
        assertThat(cambio.nombreMateria()).isEqualTo("Materia");
        assertThat(cambio.estado()).isEqualTo(EstadoMateria.APROBADA);
        assertThat(cambio.nota()).isEqualTo(8);
        assertThat(cambio.fecha()).isEqualTo(java.time.LocalDate.now());
        assertThat(cambio.alumnoNuevo().apellido()).isEqualTo("Paz");
    }

    @Test
    void marcarCursando_sinFila_tambienLlegaAlExcel() {
        when(estadoRepository.findByAlumnoIdAndMateriaId(1L, 1L)).thenReturn(Optional.empty());

        service.marcarCursando(1L, 1L);

        assertThat(cambioProgramado().estado()).isEqualTo(EstadoMateria.CURSANDO);
    }

    @Test
    void revocarFinal_programaRegularSinNotaNiFecha() {
        when(estadoRepository.findByAlumnoIdAndMateriaId(1L, 1L)).thenReturn(Optional.of(fila(EstadoMateria.APROBADA, 9)));

        service.revocarFinal(1L, 1L);

        HistorialExcelWriter.Cambio cambio = cambioProgramado();
        assertThat(cambio.estado()).isEqualTo(EstadoMateria.REGULAR);
        assertThat(cambio.nota()).isNull();
        assertThat(cambio.fecha()).isNull();
    }

    @Test
    void liberarCursada_desdeCursando_vuelveAPendienteYLoBorraDelExcel() {
        EstadoAcademicoMateria fila = fila(EstadoMateria.CURSANDO, null);
        when(estadoRepository.findByAlumnoIdAndMateriaId(1L, 1L)).thenReturn(Optional.of(fila));

        service.liberarCursada(1L, 1L);

        assertThat(fila.getEstado()).isEqualTo(EstadoMateria.PENDIENTE);
        verify(estadoRepository).save(fila);
        assertThat(cambioProgramado().estado()).isEqualTo(EstadoMateria.PENDIENTE);
    }

    @Test
    void liberarCursada_noTocaUnaMateriaYaRegular() {
        EstadoAcademicoMateria fila = fila(EstadoMateria.REGULAR, null);
        when(estadoRepository.findByAlumnoIdAndMateriaId(1L, 1L)).thenReturn(Optional.of(fila));

        service.liberarCursada(1L, 1L);

        assertThat(fila.getEstado()).isEqualTo(EstadoMateria.REGULAR);
        verify(estadoRepository, never()).save(any());
        verify(historialExcelSincronizador, never()).programar(any());
    }
}
