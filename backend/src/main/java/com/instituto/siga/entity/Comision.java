package com.instituto.siga.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
/** Cada comisión dicta UNA materia para UNA carrera; la misma materia puede tener varias comisiones en la misma
 * carrera (A, B, C...). No puede repetirse el mismo nombre en el mismo período para la misma materia y carrera.
 * carrera_id es nullable a nivel base solo para no romper filas viejas al actualizar el esquema; el servicio
 * siempre la exige y ComisionCarreraBackfill completa las existentes. */
@Table(name = "comision", uniqueConstraints = @UniqueConstraint(
        name = "uk_comision_materia_carrera_nombre_periodo",
        columnNames = {"materia_id", "carrera_id", "nombre", "periodo_lectivo"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Comision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "materia_id", nullable = false)
    private Materia materia;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "carrera_id")
    private Carrera carrera;

    @Column(nullable = false, length = 20)
    private String nombre;

    @Column(name = "periodo_lectivo", nullable = false, length = 10)
    private String periodoLectivo;

    @Column(name = "cupo_maximo", nullable = false)
    private int cupoMaximo;

    @Column(length = 150)
    private String profesor;

    @Builder.Default
    @OneToMany(mappedBy = "comision", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<Horario> horarios = new ArrayList<>();
}
