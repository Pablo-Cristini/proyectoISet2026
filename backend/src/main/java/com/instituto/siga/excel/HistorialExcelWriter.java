package com.instituto.siga.excel;

import com.instituto.siga.entity.EstadoMateria;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Escribe cambios de estado academico en la fila/columna correspondiente del .xlsx.
 * Nunca crea hojas ni columnas: si la materia no figura en ningun encabezado no escribe nada (evita
 * corromper la estructura de un plan) y lo informa con un {@link Resultado} concreto.
 * Si el alumno todavia no tiene fila y se pasan sus {@link DatosAlumno}, se le agrega una fila nueva
 * (DNI, apellido, nombre) en la hoja que tiene esa materia; si la materia esta en varias hojas, se elige
 * la que empieza con el codigo de la carrera ({@code SIST} -> {@code SIST_R6790_19}). Si no se puede
 * decidir la hoja, no se adivina: se informa.
 *
 * <p>Las tres celdas de la materia (CURSADA / FECHA / FINAL) se escriben siempre juntas: una nota o fecha que
 * ya no corresponde (p. ej. se corrigio un final aprobado) se BORRA, no queda la vieja. {@link EstadoMateria#PENDIENTE}
 * equivale a "sin registro": vacia las tres celdas (la fila del alumno se conserva).
 *
 * <p>Escritura segura: arma el libro en memoria, lo vuelca a un archivo temporal, hace una copia de
 * respaldo (.bak) del original y recien entonces reemplaza el archivo real. Despues RELEE el archivo y verifica
 * que cada celda tenga lo escrito: solo entonces informa {@link Resultado.Estado#ESCRITO}. Si algo falla
 * (archivo bloqueado por Excel, disco lleno, etc.) el original queda intacto y el temporal se descarta.
 */
@Slf4j
@Component
public class HistorialExcelWriter {

    /** Resultado de un intento de escritura; {@code mensaje} es apto para mostrar al usuario. */
    public record Resultado(Estado estado, String mensaje) {

        /** SIN_CAMBIOS: el archivo ya reflejaba el dato (p. ej. borrar una materia que el alumno no tenia). */
        public enum Estado { ESCRITO, SIN_CAMBIOS, ARCHIVO_INEXISTENTE, NO_ESTA_EN_EL_ARCHIVO, ERROR }

        /** true si el Excel quedo al dia con el cambio (escrito y verificado, o ya estaba asi). */
        public boolean escrito() {
            return estado == Estado.ESCRITO || estado == Estado.SIN_CAMBIOS;
        }

        static Resultado de(Estado estado, String mensaje) {
            return new Resultado(estado, mensaje);
        }
    }

    /** Datos para dar de alta la fila de un alumno que todavia no esta en el Excel. Todos pueden ser null. */
    public record DatosAlumno(String apellido, String nombre, String codigoCarrera) {
    }

    /** Un cambio a volcar. {@code fecha} solo tiene sentido con APROBADA; null la borra. */
    public record Cambio(String dni, String nombreMateria, EstadoMateria estado, Integer nota, LocalDate fecha,
                         DatosAlumno alumnoNuevo) {
    }

    /** Solo actualiza filas existentes: no agrega alumnos nuevos. */
    public Resultado actualizarCelda(Path archivo, String dni, String nombreMateria,
                                      EstadoMateria estado, Integer nota, LocalDate fecha) {
        return actualizarCelda(archivo, dni, nombreMateria, estado, nota, fecha, null);
    }

    /** Actualiza la fila del alumno; si no existe y {@code alumnoNuevo != null}, la crea. */
    public Resultado actualizarCelda(Path archivo, String dni, String nombreMateria,
                                      EstadoMateria estado, Integer nota, LocalDate fecha, DatosAlumno alumnoNuevo) {
        return aplicar(archivo, List.of(new Cambio(dni, nombreMateria, estado, nota, fecha, alumnoNuevo))).get(0);
    }

    /**
     * Aplica varios cambios abriendo y guardando el archivo UNA sola vez (mismo orden: si dos cambios tocan la
     * misma celda, gana el ultimo). Devuelve un resultado por cambio, en el mismo orden.
     */
    public List<Resultado> aplicar(Path archivo, List<Cambio> cambios) {
        List<Resultado> resultados = new ArrayList<>();
        if (cambios.isEmpty()) {
            return resultados;
        }
        HistorialExcelLock.LOCK.lock();
        try {
            if (!Files.exists(archivo)) {
                log.error("No se sincronizo a Excel: el archivo del historial no existe en {}", archivo);
                Resultado r = Resultado.de(Resultado.Estado.ARCHIVO_INEXISTENTE,
                        "No existe el archivo del historial: " + archivo + "; el cambio quedó solo en la base de datos");
                cambios.forEach(c -> resultados.add(r));
                return resultados;
            }
            List<Cambio> escritos = new ArrayList<>();
            try (Workbook workbook = abrir(archivo)) {
                for (Cambio cambio : cambios) {
                    Resultado r = aplicarEnLibro(workbook, cambio);
                    resultados.add(r);
                    if (r.estado() == Resultado.Estado.ESCRITO) {
                        escritos.add(cambio);
                    }
                }
                if (escritos.isEmpty()) {
                    return resultados;
                }
                guardarConBackup(archivo, workbook);
            }
            String fallaVerificacion = verificar(archivo, ultimosPorCelda(escritos));
            if (fallaVerificacion != null) {
                log.error("El historial Excel {} no quedo como se escribio: {}", archivo, fallaVerificacion);
                Resultado r = Resultado.de(Resultado.Estado.ERROR, "Se escribió el Excel pero al releerlo no coincide ("
                        + fallaVerificacion + "); revisá el archivo");
                reemplazarEscritos(resultados, r);
            } else {
                log.info("Historial Excel actualizado y verificado ({} cambio(s)): {}", escritos.size(), archivo);
            }
            return resultados;
        } catch (IOException | RuntimeException e) {
            log.error("No se pudo escribir el cambio en el historial Excel ({})", archivo, e);
            Resultado r = Resultado.de(Resultado.Estado.ERROR, "No se pudo escribir en el Excel del historial ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + "). ¿Está abierto en otro programa? El cambio quedó solo en la base de datos y se reintenta "
                    + "al reiniciar el sistema o al usar \"Recargar historial\"");
            while (resultados.size() < cambios.size()) {
                resultados.add(r);
            }
            reemplazarEscritos(resultados, r);
            return resultados;
        } finally {
            HistorialExcelLock.LOCK.unlock();
        }
    }

    private void reemplazarEscritos(List<Resultado> resultados, Resultado reemplazo) {
        for (int i = 0; i < resultados.size(); i++) {
            if (resultados.get(i).estado() == Resultado.Estado.ESCRITO) {
                resultados.set(i, reemplazo);
            }
        }
    }

    private Workbook abrir(Path archivo) throws IOException {
        try (FileInputStream entrada = new FileInputStream(archivo.toFile())) {
            return WorkbookFactory.create(entrada);
        }
    }

    private Resultado aplicarEnLibro(Workbook workbook, Cambio c) {
        if (c.dni() == null || c.dni().isBlank() || c.nombreMateria() == null || c.nombreMateria().isBlank()
                || c.estado() == null) {
            return Resultado.de(Resultado.Estado.NO_ESTA_EN_EL_ARCHIVO, "Faltan datos para ubicar la celda en el Excel");
        }
        List<Sheet> hojasConLaMateria = new ArrayList<>();
        for (Sheet sheet : workbook) {
            HistorialExcelLayout layout = HistorialExcelLayout.leer(sheet);
            if (layout.getColumnaDni() < 0) continue;
            HistorialExcelLayout.ColumnasMateria columnas = layout.columnas(c.nombreMateria());
            if (columnas == null) continue;
            hojasConLaMateria.add(sheet);

            Row row = buscarFila(sheet, layout, c.dni());
            if (row != null) {
                if (yaEsta(row, columnas, c)) {
                    return Resultado.de(Resultado.Estado.SIN_CAMBIOS, "El Excel del historial ya tenía ese dato");
                }
                volcar(workbook, row, columnas, c);
                return Resultado.de(Resultado.Estado.ESCRITO, "Cambio guardado en el Excel del historial");
            }
        }

        if (hojasConLaMateria.isEmpty()) {
            return noEsta("La materia \"" + c.nombreMateria() + "\" no figura en ningún encabezado del Excel del "
                    + "historial (el nombre de la materia en SIGA tiene que coincidir con el de la columna, "
                    + "p. ej. \"ALG\" para \"1 - ALG CURSADA\")");
        }
        if (c.estado() == EstadoMateria.PENDIENTE) {
            // Borrar algo que el alumno no tiene en el archivo: no hay que agregarle una fila vacia.
            return Resultado.de(Resultado.Estado.SIN_CAMBIOS, "El alumno no figuraba en el Excel: no había nada que borrar");
        }
        if (c.alumnoNuevo() == null) {
            return noEsta("El DNI " + c.dni() + " no figura en el Excel del historial");
        }
        Sheet hoja = elegirHoja(hojasConLaMateria, c.alumnoNuevo().codigoCarrera());
        if (hoja == null) {
            String nombres = hojasConLaMateria.stream().map(Sheet::getSheetName).collect(Collectors.joining(", "));
            return noEsta("El DNI " + c.dni() + " no figura en el Excel y la materia \"" + c.nombreMateria()
                    + "\" está en varias hojas (" + nombres + "): no se sabe en cuál agregarlo. Poné como código "
                    + "de la carrera el prefijo de la hoja (p. ej. \"SIST\") o agregá al alumno a mano");
        }

        HistorialExcelLayout layout = HistorialExcelLayout.leer(hoja);
        Row row = filaLibre(hoja, layout);
        celda(row, layout.getColumnaDni()).setCellValue(c.dni());
        if (layout.getColumnaApellido() >= 0 && c.alumnoNuevo().apellido() != null) {
            celda(row, layout.getColumnaApellido()).setCellValue(c.alumnoNuevo().apellido().toUpperCase(Locale.ROOT));
        }
        if (layout.getColumnaNombre() >= 0 && c.alumnoNuevo().nombre() != null) {
            celda(row, layout.getColumnaNombre()).setCellValue(c.alumnoNuevo().nombre().toUpperCase(Locale.ROOT));
        }
        volcar(workbook, row, layout.columnas(c.nombreMateria()), c);
        log.info("DNI {} agregado al historial Excel en la hoja {} (fila {})", c.dni(), hoja.getSheetName(), row.getRowNum() + 1);
        return Resultado.de(Resultado.Estado.ESCRITO,
                "Cambio guardado en el Excel del historial (se agregó al alumno en la hoja " + hoja.getSheetName() + ")");
    }

    private Resultado noEsta(String motivo) {
        return Resultado.de(Resultado.Estado.NO_ESTA_EN_EL_ARCHIVO, motivo + "; el cambio quedó solo en la base de datos");
    }

    private Row buscarFila(Sheet sheet, HistorialExcelLayout layout, String dni) {
        for (int i = HistorialExcelLayout.PRIMERA_FILA_DATOS; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row != null && HistorialExcelCellUtils.texto(row.getCell(layout.getColumnaDni())).equals(dni)) {
                return row;
            }
        }
        return null;
    }

    /** Una sola hoja con la materia: esa. Varias: la que empieza con el codigo de la carrera. Si no, null. */
    private Sheet elegirHoja(List<Sheet> candidatas, String codigoCarrera) {
        if (candidatas.size() == 1) return candidatas.get(0);
        if (codigoCarrera == null || codigoCarrera.isBlank()) return null;
        String prefijo = codigoCarrera.trim().toUpperCase(Locale.ROOT);
        List<Sheet> porCarrera = candidatas.stream()
                .filter(s -> {
                    String nombre = s.getSheetName().toUpperCase(Locale.ROOT);
                    return nombre.equals(prefijo) || nombre.startsWith(prefijo + "_");
                })
                .toList();
        return porCarrera.size() == 1 ? porCarrera.get(0) : null;
    }

    /** La fila siguiente al ultimo alumno (con DNI). Si ya existe (p. ej. con formulas de PROMEDIO) se reusa. */
    private Row filaLibre(Sheet sheet, HistorialExcelLayout layout) {
        int ultimaConDni = HistorialExcelLayout.PRIMERA_FILA_DATOS - 1;
        for (int i = HistorialExcelLayout.PRIMERA_FILA_DATOS; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row != null && !HistorialExcelCellUtils.texto(row.getCell(layout.getColumnaDni())).isBlank()) {
                ultimaConDni = i;
            }
        }
        int indice = ultimaConDni + 1;
        Row existente = sheet.getRow(indice);
        return existente != null ? existente : sheet.createRow(indice);
    }

    /** Lo que deberia quedar en la celda CURSADA para este cambio ("" = vacia). */
    private static String textoEsperado(Cambio c) {
        return c.estado() == EstadoMateria.PENDIENTE ? "" : c.estado().name();
    }

    private static Integer notaEsperada(Cambio c) {
        return c.estado() == EstadoMateria.PENDIENTE ? null : c.nota();
    }

    private static LocalDate fechaEsperada(Cambio c) {
        return c.estado() == EstadoMateria.PENDIENTE ? null : c.fecha();
    }

    private boolean yaEsta(Row row, HistorialExcelLayout.ColumnasMateria columnas, Cambio c) {
        String estado = HistorialExcelCellUtils.texto(row.getCell(columnas.colCursada()));
        boolean estadoIgual = estado.trim().equalsIgnoreCase(textoEsperado(c))
                || (c.estado() == EstadoMateria.PENDIENTE && estado.trim().matches("-*"));
        return estadoIgual
                && Objects.equals(HistorialExcelCellUtils.nota(row.getCell(columnas.colFinal())), notaEsperada(c))
                && Objects.equals(HistorialExcelCellUtils.fecha(row.getCell(columnas.colFecha())), fechaEsperada(c))
                && (notaEsperada(c) != null || HistorialExcelCellUtils.texto(row.getCell(columnas.colFinal())).isBlank()
                    || HistorialExcelCellUtils.texto(row.getCell(columnas.colFinal())).trim().matches("-+"));
    }

    /** Escribe las tres celdas juntas; lo que no corresponde se vacia (no queda una nota/fecha vieja). */
    private void volcar(Workbook workbook, Row row, HistorialExcelLayout.ColumnasMateria columnas, Cambio c) {
        String estado = textoEsperado(c);
        Cell celdaEstado = celda(row, columnas.colCursada());
        if (estado.isEmpty()) {
            celdaEstado.setBlank();
        } else {
            celdaEstado.setCellValue(estado);
        }

        Cell celdaFecha = celda(row, columnas.colFecha());
        LocalDate fecha = fechaEsperada(c);
        if (fecha == null) {
            celdaFecha.setBlank();
        } else {
            celdaFecha.setCellValue(fecha);
            if (!DateUtil.isCellDateFormatted(celdaFecha)) {
                celdaFecha.setCellStyle(estiloFecha(workbook, celdaFecha));
            }
        }

        Cell celdaNota = celda(row, columnas.colFinal());
        Integer nota = notaEsperada(c);
        if (nota == null) {
            celdaNota.setBlank();
        } else {
            celdaNota.setCellValue(nota);
        }
    }

    /** Reusa la celda existente (y su estilo/formato) en vez de reemplazarla. */
    private Cell celda(Row row, int columna) {
        Cell existente = row.getCell(columna);
        return existente != null ? existente : row.createCell(columna);
    }

    /** Una celda vacia no trae formato de fecha: sin esto el serial numerico se veria como "45123". Se
     * conserva el resto del estilo de la celda y solo se fuerza el formato dd-mm-yy del archivo original. */
    private CellStyle estiloFecha(Workbook workbook, Cell celda) {
        CellStyle estilo = workbook.createCellStyle();
        estilo.cloneStyleFrom(celda.getCellStyle());
        estilo.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("dd-mm-yy"));
        return estilo;
    }

    private void guardarConBackup(Path archivo, Workbook workbook) throws IOException {
        Path temporal = archivo.resolveSibling(archivo.getFileName().toString() + ".tmp");
        Path backup = archivo.resolveSibling(archivo.getFileName().toString() + ".bak");
        try {
            try (FileOutputStream salida = new FileOutputStream(temporal.toFile())) {
                workbook.write(salida);
            }
            Files.copy(archivo, backup, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporal, archivo, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporal); // no deja basura si el reemplazo fallo (p. ej. archivo bloqueado)
        }
    }

    /** Si dos cambios tocan la misma celda, el archivo debe tener el ultimo. */
    private List<Cambio> ultimosPorCelda(List<Cambio> escritos) {
        List<Cambio> ultimos = new ArrayList<>();
        for (int i = escritos.size() - 1; i >= 0; i--) {
            Cambio c = escritos.get(i);
            boolean yaHayUnoPosterior = ultimos.stream().anyMatch(u -> u.dni().equals(c.dni())
                    && u.nombreMateria().equalsIgnoreCase(c.nombreMateria()));
            if (!yaHayUnoPosterior) {
                ultimos.add(c);
            }
        }
        return ultimos;
    }

    /** Relee el archivo recien guardado: null si cada celda tiene lo escrito, o la primera diferencia. */
    private String verificar(Path archivo, List<Cambio> escritos) throws IOException {
        try (Workbook workbook = abrir(archivo)) {
            for (Cambio c : escritos) {
                boolean encontrada = false;
                for (Sheet sheet : workbook) {
                    HistorialExcelLayout layout = HistorialExcelLayout.leer(sheet);
                    if (layout.getColumnaDni() < 0) continue;
                    HistorialExcelLayout.ColumnasMateria columnas = layout.columnas(c.nombreMateria());
                    if (columnas == null) continue;
                    Row row = buscarFila(sheet, layout, c.dni());
                    if (row == null) continue;
                    encontrada = true;
                    if (!yaEsta(row, columnas, c)) {
                        return "DNI " + c.dni() + " / " + c.nombreMateria();
                    }
                    break;
                }
                if (!encontrada) {
                    return "no se encontró la fila del DNI " + c.dni();
                }
            }
            return null;
        }
    }
}
