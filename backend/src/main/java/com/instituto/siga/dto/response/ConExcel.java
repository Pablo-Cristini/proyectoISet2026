package com.instituto.siga.dto.response;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * Respuesta de una operacion que tambien debia actualizar el Excel del historial: los campos de {@code datos}
 * salen tal cual (aplanados) y se agregan {@code excelSincronizado} / {@code excelMensaje}, para que el cliente no
 * presente como completamente exitoso algo que quedo solo en la base. {@code excelSincronizado == null}: la
 * operacion no cambio el historial academico (no habia nada que escribir).
 */
public final class ConExcel<T> {

    private final T datos;
    private final Boolean excelSincronizado;
    private final String excelMensaje;

    public ConExcel(T datos, Boolean excelSincronizado, String excelMensaje) {
        this.datos = datos;
        this.excelSincronizado = excelSincronizado;
        this.excelMensaje = excelMensaje;
    }

    @JsonUnwrapped
    public T getDatos() {
        return datos;
    }

    public Boolean getExcelSincronizado() {
        return excelSincronizado;
    }

    public String getExcelMensaje() {
        return excelMensaje;
    }
}
