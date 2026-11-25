package com.instituto.siga.excel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Punto unico por el que los cambios academicos llegan al Excel del historial.
 *
 * <p><b>Se escribe despues del COMMIT</b> de la transaccion que hizo el cambio, nunca antes: si la operacion
 * falla y la base hace rollback, el Excel no queda con un dato que la base no tiene (y que al reiniciar se
 * volveria a importar como si fuera real). Los cambios de una misma transaccion se juntan y se escriben de una
 * vez (un solo abrir/guardar del archivo); si la misma materia de un alumno cambia dos veces, queda la ultima.
 * Fuera de una transaccion se escribe en el momento.
 *
 * <p>Si la escritura falla (archivo abierto en Excel, sin permisos...), el cambio igual queda en la base y
 * {@link HistorialExcelSyncService} lo vuelve a escribir al reiniciar o al "Recargar historial": la base tiene
 * una fecha de actualizacion posterior a la del archivo, y eso indica que el dato pendiente es el de la base.
 *
 * <p>{@link #capturar} permite que un controlador informe al cliente si el Excel quedo al dia (los callbacks de
 * after-commit corren en el mismo hilo, antes de que el servicio transaccional le devuelva el control).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HistorialExcelSincronizador {

    private static final ThreadLocal<List<HistorialExcelWriter.Resultado>> CAPTURA = new ThreadLocal<>();

    private final HistorialExcelWriter writer;
    private final HistorialExcelPath historialExcelPath;

    /** Resumen para el cliente: {@code sincronizado == null} si la operacion no toco el Excel. */
    public record Informe(Boolean sincronizado, String mensaje) {
        static final Informe NINGUNO = new Informe(null, null);
    }

    public record Capturado<T>(T valor, Informe informe) {
    }

    /** Programa un cambio: se escribe al confirmarse la transaccion actual (o ya, si no hay transaccion). */
    public void programar(HistorialExcelWriter.Cambio cambio) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            escribir(List.of(cambio));
            return;
        }
        Pendientes pendientes = (Pendientes) TransactionSynchronizationManager.getResource(this);
        if (pendientes == null) {
            pendientes = new Pendientes();
            TransactionSynchronizationManager.bindResource(this, pendientes);
            Pendientes registrados = pendientes;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    escribir(new ArrayList<>(registrados.porCelda.values()));
                }

                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(HistorialExcelSincronizador.this);
                    if (status == STATUS_ROLLED_BACK && !registrados.porCelda.isEmpty()) {
                        log.info("Transaccion revertida: no se escriben {} cambio(s) en el Excel del historial",
                                registrados.porCelda.size());
                    }
                }
            });
        }
        pendientes.porCelda.put(cambio.dni() + "|" + cambio.nombreMateria().trim().toUpperCase(Locale.ROOT), cambio);
    }

    /** Ejecuta {@code operacion} (tipicamente un servicio @Transactional) y junta lo que paso con el Excel. */
    public <T> Capturado<T> capturar(Supplier<T> operacion) {
        List<HistorialExcelWriter.Resultado> anterior = CAPTURA.get();
        List<HistorialExcelWriter.Resultado> resultados = new ArrayList<>();
        CAPTURA.set(resultados);
        try {
            T valor = operacion.get();
            return new Capturado<>(valor, resumir(resultados));
        } finally {
            if (anterior == null) {
                CAPTURA.remove();
            } else {
                CAPTURA.set(anterior);
            }
        }
    }

    /** Escritura directa (sin transaccion), p. ej. los pendientes que reescribe la sincronizacion al iniciar. */
    public List<HistorialExcelWriter.Resultado> escribir(List<HistorialExcelWriter.Cambio> cambios) {
        List<HistorialExcelWriter.Resultado> resultados;
        try {
            resultados = writer.aplicar(historialExcelPath.resolver(), cambios);
        } catch (RuntimeException e) {
            log.error("Error inesperado al escribir el historial Excel", e);
            HistorialExcelWriter.Resultado r = new HistorialExcelWriter.Resultado(HistorialExcelWriter.Resultado.Estado.ERROR,
                    "No se pudo escribir en el Excel del historial: " + e.getMessage());
            resultados = cambios.stream().map(c -> r).toList();
        }
        for (int i = 0; i < resultados.size(); i++) {
            HistorialExcelWriter.Resultado r = resultados.get(i);
            if (r.estado() == HistorialExcelWriter.Resultado.Estado.ERROR
                    || r.estado() == HistorialExcelWriter.Resultado.Estado.ARCHIVO_INEXISTENTE) {
                log.error("Cambio NO escrito en el historial Excel (DNI {}, materia \"{}\"): {}",
                        cambios.get(i).dni(), cambios.get(i).nombreMateria(), r.mensaje());
            }
        }
        List<HistorialExcelWriter.Resultado> captura = CAPTURA.get();
        if (captura != null) {
            captura.addAll(resultados);
        }
        return resultados;
    }

    static Informe resumir(List<HistorialExcelWriter.Resultado> resultados) {
        if (resultados.isEmpty()) {
            return Informe.NINGUNO;
        }
        boolean todos = resultados.stream().allMatch(HistorialExcelWriter.Resultado::escrito);
        Set<String> mensajes = new LinkedHashSet<>();
        resultados.stream()
                .filter(r -> todos || !r.escrito())
                .map(HistorialExcelWriter.Resultado::mensaje)
                .forEach(mensajes::add);
        return new Informe(todos, String.join(" | ", mensajes));
    }

    private static final class Pendientes {
        private final Map<String, HistorialExcelWriter.Cambio> porCelda = new LinkedHashMap<>();
    }
}
