package com.instituto.siga.dto.request;

import com.instituto.siga.validation.ReglasDni;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Autoregistro de alumnos. No lleva roles: el rol es siempre ALUMNO (nadie se asigna ADMINISTRADOR/PRECEPTOR). */
public record RegistroRequest(
        @NotBlank
        @Pattern(regexp = ReglasDni.PATRON_ALTA, message = ReglasDni.MENSAJE_ALTA)
        String dni,
        @Email @NotBlank @Size(max = 150) String email,
        @NotBlank @Size(max = 100) String nombre,
        @NotBlank @Size(max = 100) String apellido,
        @NotBlank
        @Pattern(regexp = "^(?=.*[A-Z])(?=.*\\d).{8,}$",
                message = "Debe tener al menos 8 caracteres, una mayúscula y un número")
        String password) {
}
