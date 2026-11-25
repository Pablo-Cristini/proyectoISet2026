package com.instituto.siga.validation;

/**
 * Regla unica de DNI de SIGA para CREAR usuarios (alta por el administrador y autoregistro): solo digitos, sin
 * puntos ni letras, 8 o 9 digitos. El frontend usa la misma regla (frontend/src/utils/dni.ts).
 *
 * <p>El login acepta 7 a 9 digitos (LoginPage) para no dejar afuera cuentas que ya existieran con un DNI de 7
 * digitos; como 8-9 esta contenido en 7-9, todo usuario creado con esta regla puede iniciar sesion.
 */
public final class ReglasDni {

    public static final String PATRON_ALTA = "^\\d{8,9}$";
    public static final String MENSAJE_ALTA = "El DNI debe tener 8 o 9 dígitos numéricos, sin puntos, letras ni espacios";

    private ReglasDni() {
    }
}
