/**
 * Regla única de DNI para CREAR usuarios (alta por el administrador y autoregistro), la misma que valida el
 * backend (ReglasDni.PATRON_ALTA): solo dígitos, sin puntos ni letras, 8 o 9 dígitos.
 */
export const DNI_ALTA_REGEX = /^\d{8,9}$/;
export const DNI_ALTA_MENSAJE = 'El DNI debe tener 8 o 9 dígitos numéricos, sin puntos, letras ni espacios';

/**
 * El login acepta 7 a 9 dígitos para no dejar afuera cuentas que ya existieran con un DNI de 7 dígitos. Todo DNI
 * válido para el alta (8-9) también es válido para el login, así que nadie queda creado sin poder ingresar.
 */
export const DNI_LOGIN_REGEX = /^\d{7,9}$/;
