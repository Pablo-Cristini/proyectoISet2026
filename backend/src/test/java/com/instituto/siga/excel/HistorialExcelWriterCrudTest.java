package com.instituto.siga.excel;

import com.instituto.siga.entity.EstadoMateria;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Circuito completo sobre el .xlsx (copia de la plantilla real): crear, modificar, eliminar, operaciones en lote y
 * muchas escrituras seguidas. Cada verificacion RELEE el archivo con el mismo lector que usa el sistema.
 */
class HistorialExcelWriterCrudTest {

    private static final String DNI = "99000888";
    private static final String MATERIA = "FISICA"; // figura en una sola hoja de la plantilla
    private static final HistorialExcelWriter.DatosAlumno DATOS =
            new HistorialExcelWriter.DatosAlumno("Prueba", "Crud", null);

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

    private HistorialExcelWriter.Resultado escribir(EstadoMateria estado, Integer nota, LocalDate fecha) {
        return writer.actualizarCelda(archivo, DNI, MATERIA, estado, nota, fecha, DATOS);
    }

    private HistorialExcelMateriaCelda celdaLeida() throws IOException {
        return reader.leer(archivo).stream().filter(f -> f.dni().equals(DNI)).findFirst()
                .flatMap(f -> f.materias().stream().filter(m -> m.nombreMateria().equalsIgnoreCase(MATERIA)).findFirst())
                .orElse(null);
    }

    @Test
    void crear_modificar_eliminar_seReflejanEnElArchivo() throws IOException {
        // CREAR
        assertThat(escribir(EstadoMateria.CURSANDO, null, null).estado())
                .isEqualTo(HistorialExcelWriter.Resultado.Estado.ESCRITO);
        assertThat(celdaLeida().estadoTexto()).isEqualTo("CURSANDO");

        // MODIFICAR: aprobado con nota y fecha
        LocalDate fecha = LocalDate.of(2026, 11, 16);
        assertThat(escribir(EstadoMateria.APROBADA, 9, fecha).escrito()).isTrue();
        HistorialExcelMateriaCelda aprobada = celdaLeida();
        assertThat(aprobada.estadoTexto()).isEqualTo("APROBADA");
        assertThat(aprobada.nota()).isEqualTo(9);
        assertThat(aprobada.fecha()).isEqualTo(fecha);

        // MODIFICAR: se corrige el final -> REGULAR. La nota y la fecha viejas NO deben quedar en el archivo
        // (antes quedaban y, al reiniciar, la nota 9 se volvia a importar a la base).
        assertThat(escribir(EstadoMateria.REGULAR, null, null).escrito()).isTrue();
        HistorialExcelMateriaCelda corregida = celdaLeida();
        assertThat(corregida.estadoTexto()).isEqualTo("REGULAR");
        assertThat(corregida.nota()).isNull();
        assertThat(corregida.fecha()).isNull();

        // Sin duplicados: una sola fila para el alumno
        assertThat(reader.leer(archivo).stream().filter(f -> f.dni().equals(DNI))).hasSize(1);

        // ELIMINAR (PENDIENTE = sin registro): las celdas de la materia quedan vacias
        assertThat(escribir(EstadoMateria.PENDIENTE, null, null).escrito()).isTrue();
        assertThat(celdaLeida()).isNull();
        assertThat(reader.leer(archivo).stream().filter(f -> f.dni().equals(DNI))).hasSize(1); // la fila se conserva
    }

    @Test
    void escribirLoMismoDosVeces_noTocaElArchivo() throws IOException {
        escribir(EstadoMateria.REGULAR, null, null);
        long antes = Files.getLastModifiedTime(archivo).toMillis();
        byte[] contenido = Files.readAllBytes(archivo);

        HistorialExcelWriter.Resultado r = escribir(EstadoMateria.REGULAR, null, null);

        assertThat(r.estado()).isEqualTo(HistorialExcelWriter.Resultado.Estado.SIN_CAMBIOS);
        assertThat(r.escrito()).isTrue();
        assertThat(Files.readAllBytes(archivo)).isEqualTo(contenido);
        assertThat(Files.getLastModifiedTime(archivo).toMillis()).isEqualTo(antes);
    }

    @Test
    void eliminarAlgoQueNoEstaba_noAgregaFilas() throws IOException {
        HistorialExcelWriter.Resultado r = escribir(EstadoMateria.PENDIENTE, null, null);

        assertThat(r.estado()).isEqualTo(HistorialExcelWriter.Resultado.Estado.SIN_CAMBIOS);
        assertThat(reader.leer(archivo)).isEmpty();
    }

    @Test
    void enLote_ganaElUltimoCambioDeCadaCelda_yUnSoloGuardado() throws IOException {
        List<HistorialExcelWriter.Resultado> r = writer.aplicar(archivo, List.of(
                new HistorialExcelWriter.Cambio(DNI, MATERIA, EstadoMateria.CURSANDO, null, null, DATOS),
                new HistorialExcelWriter.Cambio(DNI, MATERIA, EstadoMateria.REGULAR, null, null, DATOS),
                new HistorialExcelWriter.Cambio("99000889", MATERIA, EstadoMateria.CURSANDO, null, null,
                        new HistorialExcelWriter.DatosAlumno("Otro", "Alumno", null))));

        assertThat(r).hasSize(3).allMatch(HistorialExcelWriter.Resultado::escrito);
        assertThat(celdaLeida().estadoTexto()).isEqualTo("REGULAR");
        assertThat(reader.leer(archivo)).hasSize(2);
    }

    @Test
    void muchasOperacionesSeguidas_noCorrompenElArchivo_niPierdenRegistrosAnteriores() throws Exception {
        int hojasOriginales;
        List<String> problemasOriginales = reader.problemasDeEstructura(archivo);
        try (FileInputStream in = new FileInputStream(archivo.toFile()); Workbook wb = WorkbookFactory.create(in)) {
            hojasOriginales = wb.getNumberOfSheets();
        }
        List<String> dnis = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String dni = String.valueOf(99100000 + i);
            dnis.add(dni);
            HistorialExcelWriter.Resultado r = writer.actualizarCelda(archivo, dni, MATERIA,
                    i % 2 == 0 ? EstadoMateria.REGULAR : EstadoMateria.APROBADA, i % 2 == 0 ? null : 7,
                    i % 2 == 0 ? null : LocalDate.of(2026, 3, 1), new HistorialExcelWriter.DatosAlumno("A" + i, "N" + i, null));
            assertThat(r.escrito()).as(r.mensaje()).isTrue();
        }

        // El archivo abre, conserva sus hojas y estructura, y estan los 30 alumnos con sus datos
        try (FileInputStream in = new FileInputStream(archivo.toFile()); Workbook wb = WorkbookFactory.create(in)) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(hojasOriginales);
        }
        assertThat(reader.problemasDeEstructura(archivo)).isEqualTo(problemasOriginales);
        List<HistorialExcelRow> filas = reader.leer(archivo);
        assertThat(filas).extracting(HistorialExcelRow::dni).containsExactlyInAnyOrderElementsOf(dnis);
        assertThat(filas).allSatisfy(f -> assertThat(f.materias()).hasSize(1));
        // El respaldo existe y tambien es un .xlsx valido
        try (FileInputStream in = new FileInputStream(archivo.resolveSibling("historial.xlsx.bak").toFile());
             Workbook wb = WorkbookFactory.create(in)) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(hojasOriginales);
        }
    }

    @Test
    void archivoInexistente_seInformaComoError_noComoExito() {
        HistorialExcelWriter.Resultado r = writer.actualizarCelda(tmp.resolve("no-existe.xlsx"), DNI, MATERIA,
                EstadoMateria.REGULAR, null, null, DATOS);

        assertThat(r.escrito()).isFalse();
        assertThat(r.estado()).isEqualTo(HistorialExcelWriter.Resultado.Estado.ARCHIVO_INEXISTENTE);
    }
}
