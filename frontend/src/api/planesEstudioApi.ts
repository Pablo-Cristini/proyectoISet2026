import { httpClient } from './httpClient';
import type { ModalidadCursada } from './materiasApi';

export interface PlanEstudioResponse {
  id: number;
  carreraId: number;
  version: string;
  vigente: boolean;
}

export interface PlanEstudioRequest {
  version: string;
  vigente: boolean;
}

export async function listarPlanesDeCarrera(carreraId: number): Promise<PlanEstudioResponse[]> {
  const { data } = await httpClient.get<PlanEstudioResponse[]>(`/carreras/${carreraId}/planes`);
  return data;
}

export async function crearPlanEstudio(carreraId: number, request: PlanEstudioRequest): Promise<PlanEstudioResponse> {
  const { data } = await httpClient.post<PlanEstudioResponse>(`/carreras/${carreraId}/planes`, request);
  return data;
}

export async function actualizarPlanEstudio(id: number, request: PlanEstudioRequest): Promise<PlanEstudioResponse> {
  const { data } = await httpClient.put<PlanEstudioResponse>(`/planes-estudio/${id}`, request);
  return data;
}

/** Ubicación de una materia en el plan. cuatrimestre: 1 o 2 si es cuatrimestral; null si es anual. */
export interface PlanMateriaRequest {
  anio: number;
  cuatrimestre: number | null;
}

/** Una materia de la malla: año, cuatrimestre (null = anual) y modalidad. */
export interface PlanMateriaResponse {
  planId: number;
  materiaId: number;
  codigo: string;
  nombre: string;
  cargaHoraria: number;
  activa: boolean;
  modalidad: ModalidadCursada;
  anio: number;
  cuatrimestre: number | null;
}

export async function listarMallaDelPlan(planId: number): Promise<PlanMateriaResponse[]> {
  const { data } = await httpClient.get<PlanMateriaResponse[]>(`/planes-estudio/${planId}/materias`);
  return data;
}

export async function asociarMateriaAPlan(materiaId: number, planId: number, request: PlanMateriaRequest): Promise<void> {
  await httpClient.post(`/materias/${materiaId}/plan/${planId}`, request);
}

export async function actualizarAsociacionMateriaPlan(
  materiaId: number,
  planId: number,
  request: PlanMateriaRequest,
): Promise<void> {
  await httpClient.put(`/materias/${materiaId}/plan/${planId}`, request);
}

export async function quitarMateriaDelPlan(materiaId: number, planId: number): Promise<void> {
  await httpClient.delete(`/materias/${materiaId}/plan/${planId}`);
}
