import { httpClient } from './httpClient';

/** Duración de la cursada. Las materias anteriores a este campo son CUATRIMESTRAL. */
export type ModalidadCursada = 'CUATRIMESTRAL' | 'ANUAL';

export const MODALIDADES: { valor: ModalidadCursada; etiqueta: string }[] = [
  { valor: 'CUATRIMESTRAL', etiqueta: 'Cuatrimestral' },
  { valor: 'ANUAL', etiqueta: 'Anual' },
];

export function etiquetaModalidad(modalidad: ModalidadCursada | undefined | null): string {
  return modalidad === 'ANUAL' ? 'Anual' : 'Cuatrimestral';
}

export interface MateriaResponse {
  id: number;
  codigo: string;
  nombre: string;
  cargaHoraria: number;
  activa: boolean;
  modalidad: ModalidadCursada;
}

export interface MateriaRequest {
  codigo: string;
  nombre: string;
  cargaHoraria: number;
  modalidad: ModalidadCursada;
}

export async function listarMaterias(): Promise<MateriaResponse[]> {
  const { data } = await httpClient.get<MateriaResponse[]>('/materias');
  return data;
}

export async function listarMateriasPorCarrera(carreraId: number): Promise<MateriaResponse[]> {
  const { data } = await httpClient.get<MateriaResponse[]>('/materias', { params: { carreraId } });
  return data;
}

export async function listarMateriasPorPlan(planId: number): Promise<MateriaResponse[]> {
  const { data } = await httpClient.get<MateriaResponse[]>('/materias', { params: { planId } });
  return data;
}

export async function crearMateria(request: MateriaRequest): Promise<MateriaResponse> {
  const { data } = await httpClient.post<MateriaResponse>('/materias', request);
  return data;
}

export async function actualizarMateria(id: number, request: MateriaRequest): Promise<MateriaResponse> {
  const { data } = await httpClient.put<MateriaResponse>(`/materias/${id}`, request);
  return data;
}

export async function darDeBajaMateria(id: number): Promise<void> {
  await httpClient.delete(`/materias/${id}`);
}
