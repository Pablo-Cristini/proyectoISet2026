package com.instituto.siga.excel;

import com.instituto.siga.entity.EstadoMateria;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Alumno dado de alta desde SIGA que todavia no tiene fila en el Excel: el writer se la agrega en la hoja
 * correcta (en vez de descartar el cambio, que era el motivo de que "no se guardara nada en el .xlsx").
 * Trabaja sobre una copia de la plantilla empaquetada (9 hojas, sin alumnos).
 */
class HistorialExcelWriterAltaTest {

    private static final String DNI = "99000777";
    private static final HistorialExcelWriter.DatosAlumno DATOS =
            new HistorialExcelWriter.DatosAlumno("Gómez", "Lucía", null);

    private final HistorialExcelWriter writer = new HistorialExcelWriter();
    private final HistorialExcelReader reader = new HistorialExcelReader();

    @TempDir
    Path tmp;
    private Path archivo;

    @BeforeEach
    void copiarPlantilla() throws IOException {
        archivo = tmp.resolve("historial.xlsx");
        try (InputStream in = new ClassPathResource(HistorialExcelBootstrap.PLANTILLA).getInputStream()) {
            Files.copy(in, archivo);
        }
    }

    @Test
    void alumnoNuevo_conMateriaDeUnaSolaHoja_seAgregaUnaFilaYSeEscribeLaNota() throws IOException {
        // "FISICA" figura en una sola hoja de la plantilla
        HistorialExcelWriter.Resultado r = writer.actualizarCelda(archivo, DNI, "FISICA",
                EstadoMateria.APROBADA, 8, LocalDate.of(2026, 11, 5), DATOS);

        assertThat(r.escrito()).as(r.mensaje()).isTrue();
        List<HistorialExcelRow> filas = reader.leer(archivo);
        assertThat(filas).hasSize(1);
        HistorialExcelRow fila = filas.get(0);
        assertThat(fila.dni()).isEqualTo(DNI);
        assertThat(fila.apellido()).isEqualTo("GÓMEZ");
        assertThat(fila.nombre()).isEqualTo("LUCÍA");
        assertThat(fila.materias()).singleElement().satisfies(c -> {
            assertThat(c.nombreMateria()).isEqualToIgnoringCase("FISICA");
            assertThat(c.estadoTexto()).isEqualTo("APROBADA");
            assertThat(c.nota()).isEqualTo(8);
        });
        assertThat(r.mensaje()).contains(fila.hoja());
    }

    @Test
    void segundaEscrituraDelMismoAlumno_actualizaSuFila_sinDuplicarla() throws IOException {
        writer.actualizarCelda(archivo, DNI, "FISICA", EstadoMateria.REGULAR, null, null, DATOS);
        writer.actualizarCelda(archivo, DNI, "FISICA", EstadoMateria.APROBADA, 9, LocalDate.now(), DATOS);

        List<HistorialExcelRow> filas = reader.leer(archivo);
        assertThat(filas).hasSize(1);
        assertThat(filas.get(0).materias().get(0).estadoTexto()).isEqualTo("APROBADA");
        assertThat(filas.get(0).materias().get(0).nota()).isEqualTo(9);
    }

    @Test
    void materiaEnVariasHojas_usaElCodigoDeCarreraParaElegirLaHoja() throws IOException {
        // "MTM 1" esta en ALIM_R11297_97, PROG_R6175_03 y SIST_R6790_19
        HistorialExcelWriter.Resultado r = writer.actualizarCelda(archivo, DNI, "MTM 1", EstadoMateria.REGULAR,
                null, null, new HistorialExcelWriter.DatosAlumno("Gómez", "Lucía", "sist"));

        assertThat(r.escrito()).as(r.mensaje()).isTrue();
        assertThat(reader.leer(archivo)).singleElement()
                .satisfies(f -> assertThat(f.hoja()).isEqualTo("SIST_R6790_19"));
    }

    @Test
    void materiaEnVariasHojas_sinCodigoQueLasDistinga_noAdivinaYNoTocaElArchivo() throws IOException {
        byte[] antes = Files.readAllBytes(archivo);

        HistorialExcelWriter.Resultado r = writer.actualizarCelda(archivo, DNI, "MTM 1", EstadoMateria.REGULAR,
                null, null, DATOS);

        assertThat(r.estado()).isEqualTo(HistorialExcelWriter.Resultado.Estado.NO_ESTA_EN_EL_ARCHIVO);
        assertThat(r.mensaje()).contains("SIST_R6790_19").contains("PROG_R6175_03");
        assertThat(Files.readAllBytes(archivo)).isEqualTo(antes);
    }

    @Test
    void materiaQueNoEstaEnNingunEncabezado_seInforma() {
        HistorialExcelWriter.Resultado r = writer.actualizarCelda(archivo, DNI, "Base de Datos",
                EstadoMateria.REGULAR, null, null, DATOS);

        assertThat(r.estado()).isEqualTo(HistorialExcelWriter.Resultado.Estado.NO_ESTA_EN_EL_ARCHIVO);
        assertThat(r.mensaje()).contains("Base de Datos");
    }
}
