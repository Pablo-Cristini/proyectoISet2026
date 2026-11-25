import { useMemo, useState } from 'react';
import { useForm, Controller } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Chip,
  CircularProgress,
  FormControlLabel,
  IconButton,
  MenuItem,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import EditIcon from '@mui/icons-material/Edit';
import DeleteIcon from '@mui/icons-material/Delete';
import { DataTable, type DataTableColumn } from '../../components/DataTable';
import { FormModal } from '../../components/FormModal';
import { useSnackbar } from '../../components/SnackbarProvider';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { extraerMensajeError } from '../../utils/apiError';
import { listarCarreras } from '../../api/carrerasApi';
import { etiquetaModalidad, listarMaterias } from '../../api/materiasApi';
import {
  actualizarAsociacionMateriaPlan,
  actualizarPlanEstudio,
  asociarMateriaAPlan,
  crearPlanEstudio,
  listarMallaDelPlan,
  listarPlanesDeCarrera,
  quitarMateriaDelPlan,
  type PlanEstudioResponse,
  type PlanMateriaResponse,
} from '../../api/planesEstudioApi';

/** Mismo límite que el backend (PlanEstudio.LARGO_MAXIMO_VERSION). */
const LARGO_MAXIMO_VERSION = 100;

const planSchema = z.object({
  version: z
    .string()
    .trim()
    .min(1, 'La versión es obligatoria')
    .max(LARGO_MAXIMO_VERSION, `La versión puede tener hasta ${LARGO_MAXIMO_VERSION} caracteres`),
  vigente: z.boolean(),
});
type PlanFormValues = z.infer<typeof planSchema>;

// El cuatrimestre solo aplica a las materias cuatrimestrales: para las anuales se envía null.
const asociarSchema = z.object({
  materiaId: z.coerce.number().int().positive('Elegí una materia'),
  anio: z.coerce.number().int().min(1, 'Debe ser mayor a 0'),
  cuatrimestre: z.union([z.literal(''), z.coerce.number().int().min(1).max(2)]).optional(),
});
type AsociarFormInput = z.input<typeof asociarSchema>;
type AsociarFormValues = z.output<typeof asociarSchema>;

function textoCuatrimestre(pm: PlanMateriaResponse): string {
  return pm.modalidad === 'ANUAL' || pm.cuatrimestre == null ? 'Anual (todo el año)' : `${pm.cuatrimestre}°`;
}

export function PlanesEstudioPage() {
  const queryClient = useQueryClient();
  const { notify } = useSnackbar();

  const carrerasQuery = useQuery({ queryKey: ['carreras'], queryFn: listarCarreras });
  const [carreraId, setCarreraId] = useState<number | ''>('');
  const [planSeleccionado, setPlanSeleccionado] = useState<PlanEstudioResponse | null>(null);
  const [modalPlan, setModalPlan] = useState(false);
  const [planEnEdicion, setPlanEnEdicion] = useState<PlanEstudioResponse | null>(null);
  const [modalAsociar, setModalAsociar] = useState(false);
  /** null: asociar una materia nueva; si no, se edita la ubicación de esa materia en el plan. */
  const [asociacionEnEdicion, setAsociacionEnEdicion] = useState<PlanMateriaResponse | null>(null);
  const [asociacionAQuitar, setAsociacionAQuitar] = useState<PlanMateriaResponse | null>(null);

  const planesQuery = useQuery({
    queryKey: ['planes-estudio', carreraId],
    queryFn: () => listarPlanesDeCarrera(carreraId as number),
    enabled: carreraId !== '',
  });

  const materiasQuery = useQuery({ queryKey: ['materias'], queryFn: listarMaterias });

  const mallaQuery = useQuery({
    queryKey: ['materias-plan', planSeleccionado?.id],
    queryFn: () => listarMallaDelPlan(planSeleccionado!.id),
    enabled: !!planSeleccionado,
  });

  const planForm = useForm<PlanFormValues>({ resolver: zodResolver(planSchema), defaultValues: { vigente: true } });
  const asociarForm = useForm<AsociarFormInput, unknown, AsociarFormValues>({
    resolver: zodResolver(asociarSchema),
  });

  const invalidarPlanes = () => queryClient.invalidateQueries({ queryKey: ['planes-estudio', carreraId] });

  const crearMutation = useMutation({
    mutationFn: (values: PlanFormValues) => crearPlanEstudio(carreraId as number, values),
    onSuccess: () => {
      notify('Plan de estudio creado correctamente', 'success');
      invalidarPlanes();
      setModalPlan(false);
    },
    onError: (error) => notify(extraerMensajeError(error, 'No se pudo crear el plan (¿versión duplicada?)'), 'error'),
  });

  const actualizarMutation = useMutation({
    mutationFn: ({ id, values }: { id: number; values: PlanFormValues }) => actualizarPlanEstudio(id, values),
    onSuccess: () => {
      notify('Plan de estudio actualizado correctamente', 'success');
      invalidarPlanes();
      setModalPlan(false);
    },
    onError: (error) => notify(extraerMensajeError(error, 'No se pudo actualizar el plan'), 'error'),
  });

  const invalidarMalla = () => {
    queryClient.invalidateQueries({ queryKey: ['materias-plan', planSeleccionado?.id] });
    // las comisiones filtran materias por carrera (a través de los planes)
    queryClient.invalidateQueries({ queryKey: ['materias'] });
  };

  const asociarMutation = useMutation({
    mutationFn: (values: AsociarFormValues) => {
      const request = {
        anio: values.anio,
        cuatrimestre: esAnual(values.materiaId) || values.cuatrimestre === '' || values.cuatrimestre == null
          ? null
          : values.cuatrimestre,
      };
      return asociacionEnEdicion
        ? actualizarAsociacionMateriaPlan(asociacionEnEdicion.materiaId, planSeleccionado!.id, request)
        : asociarMateriaAPlan(values.materiaId, planSeleccionado!.id, request);
    },
    onSuccess: () => {
      notify(asociacionEnEdicion ? 'Ubicación de la materia actualizada' : 'Materia asociada al plan correctamente', 'success');
      invalidarMalla();
      setModalAsociar(false);
    },
    onError: (error) => notify(extraerMensajeError(error, 'No se pudo guardar la materia en el plan'), 'error'),
  });

  const quitarMutation = useMutation({
    mutationFn: (pm: PlanMateriaResponse) => quitarMateriaDelPlan(pm.materiaId, pm.planId),
    onSuccess: () => {
      notify('Materia quitada del plan', 'success');
      invalidarMalla();
      setAsociacionAQuitar(null);
    },
    onError: (error) => {
      notify(extraerMensajeError(error, 'No se pudo quitar la materia del plan'), 'error');
      setAsociacionAQuitar(null);
    },
  });

  const materiasNoAsociadas = useMemo(() => {
    const idsAsociadas = new Set((mallaQuery.data ?? []).map((m) => m.materiaId));
    return (materiasQuery.data ?? []).filter((m) => !idsAsociadas.has(m.id));
  }, [materiasQuery.data, mallaQuery.data]);

  function esAnual(materiaId: unknown): boolean {
    const id = Number(materiaId);
    const enMalla = (mallaQuery.data ?? []).find((m) => m.materiaId === id);
    const materia = (materiasQuery.data ?? []).find((m) => m.id === id);
    return (enMalla?.modalidad ?? materia?.modalidad) === 'ANUAL';
  }

  const materiaElegida = asociarForm.watch('materiaId');
  const materiaElegidaEsAnual = esAnual(materiaElegida);

  const abrirAsociar = () => {
    setAsociacionEnEdicion(null);
    asociarForm.reset({ materiaId: undefined, anio: 1, cuatrimestre: 1 });
    setModalAsociar(true);
  };

  const abrirEdicionAsociacion = (pm: PlanMateriaResponse) => {
    setAsociacionEnEdicion(pm);
    asociarForm.reset({ materiaId: pm.materiaId, anio: pm.anio, cuatrimestre: pm.cuatrimestre ?? '' });
    setModalAsociar(true);
  };

  const onSubmitAsociar = (values: AsociarFormValues) => {
    if (!esAnual(values.materiaId) && (values.cuatrimestre === '' || values.cuatrimestre == null)) {
      asociarForm.setError('cuatrimestre', { message: 'Elegí el cuatrimestre (1° o 2°)' });
      return;
    }
    asociarMutation.mutate(values);
  };

  const abrirAltaPlan = () => {
    setPlanEnEdicion(null);
    planForm.reset({ version: '', vigente: true });
    setModalPlan(true);
  };

  const abrirEdicionPlan = (plan: PlanEstudioResponse) => {
    setPlanEnEdicion(plan);
    planForm.reset({ version: plan.version, vigente: plan.vigente });
    setModalPlan(true);
  };

  const onSubmitPlan = (values: PlanFormValues) => {
    if (planEnEdicion) {
      actualizarMutation.mutate({ id: planEnEdicion.id, values });
    } else {
      crearMutation.mutate(values);
    }
  };

  const columnasPlanes: DataTableColumn<PlanEstudioResponse>[] = [
    { key: 'version', header: 'Versión', render: (p) => p.version },
    {
      key: 'vigente',
      header: 'Estado',
      render: (p) => <Chip label={p.vigente ? 'Vigente' : 'No vigente'} color={p.vigente ? 'success' : 'default'} size="small" />,
    },
    {
      key: 'acciones',
      header: 'Acciones',
      render: (p) => (
        <Stack direction="row">
          <Button size="small" onClick={() => setPlanSeleccionado(p)}>
            Ver malla
          </Button>
          <IconButton onClick={() => abrirEdicionPlan(p)} size="small">
            <EditIcon fontSize="small" />
          </IconButton>
        </Stack>
      ),
    },
  ];

  const columnasMalla: DataTableColumn<PlanMateriaResponse>[] = [
    { key: 'anio', header: 'Año', render: (m) => `${m.anio}°` },
    { key: 'cuatrimestre', header: 'Cuatrimestre', render: textoCuatrimestre },
    { key: 'codigo', header: 'Código', render: (m) => m.codigo },
    { key: 'nombre', header: 'Nombre', render: (m) => m.nombre },
    {
      key: 'modalidad',
      header: 'Modalidad',
      render: (m) => (
        <Chip
          label={etiquetaModalidad(m.modalidad)}
          color={m.modalidad === 'ANUAL' ? 'secondary' : 'default'}
          variant="outlined"
          size="small"
        />
      ),
    },
    {
      key: 'acciones',
      header: 'Acciones',
      render: (m) => (
        <Stack direction="row">
          <IconButton onClick={() => abrirEdicionAsociacion(m)} size="small" title="Cambiar año / cuatrimestre">
            <EditIcon fontSize="small" />
          </IconButton>
          <IconButton onClick={() => setAsociacionAQuitar(m)} size="small" title="Quitar del plan">
            <DeleteIcon fontSize="small" />
          </IconButton>
        </Stack>
      ),
    },
  ];

  return (
    <Stack spacing={3}>
      <Typography variant="h4">Planes de estudio</Typography>

      {carrerasQuery.isLoading && <CircularProgress />}
      {carrerasQuery.isError && <Alert severity="error">No se pudieron cargar las carreras.</Alert>}
      {carrerasQuery.data && (
        <TextField
          select
          label="Carrera"
          value={carreraId}
          onChange={(e) => {
            setCarreraId(e.target.value === '' ? '' : Number(e.target.value));
            setPlanSeleccionado(null);
          }}
          sx={{ maxWidth: 400 }}
        >
          {carrerasQuery.data.map((c) => (
            <MenuItem key={c.id} value={c.id}>
              {c.codigo} — {c.nombre}
            </MenuItem>
          ))}
        </TextField>
      )}

      {carreraId !== '' && (
        <>
          <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center' }}>
            <Typography variant="h6">Planes</Typography>
            <Button variant="contained" startIcon={<AddIcon />} onClick={abrirAltaPlan}>
              Nuevo plan
            </Button>
          </Stack>
          {planesQuery.isLoading && <CircularProgress />}
          {planesQuery.isError && <Alert severity="error">No se pudieron cargar los planes.</Alert>}
          {planesQuery.data && <DataTable columns={columnasPlanes} rows={planesQuery.data} getRowKey={(p) => p.id} />}
        </>
      )}

      {planSeleccionado && (
        <>
          <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center' }}>
            <Typography variant="h6">Malla — versión {planSeleccionado.version}</Typography>
            <Button variant="contained" startIcon={<AddIcon />} onClick={abrirAsociar}>
              Asociar materia
            </Button>
          </Stack>
          {mallaQuery.isLoading && <CircularProgress />}
          {mallaQuery.isError && <Alert severity="error">No se pudo cargar la malla del plan.</Alert>}
          {mallaQuery.data && (
            <DataTable columns={columnasMalla} rows={mallaQuery.data} getRowKey={(m) => m.materiaId} />
          )}
        </>
      )}

      <FormModal
        open={modalPlan}
        titulo={planEnEdicion ? 'Editar plan' : 'Nuevo plan'}
        onClose={() => setModalPlan(false)}
        onSubmit={planForm.handleSubmit(onSubmitPlan)}
        submitting={crearMutation.isPending || actualizarMutation.isPending}
      >
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField
            label="Versión"
            fullWidth
            error={!!planForm.formState.errors.version}
            helperText={
              planForm.formState.errors.version?.message ??
              `${(planForm.watch('version') ?? '').length}/${LARGO_MAXIMO_VERSION} caracteres`
            }
            slotProps={{ htmlInput: { maxLength: LARGO_MAXIMO_VERSION } }}
            {...planForm.register('version')}
          />
          <Controller
            name="vigente"
            control={planForm.control}
            render={({ field }) => (
              <FormControlLabel
                control={<Switch checked={field.value} onChange={(e) => field.onChange(e.target.checked)} />}
                label="Vigente"
              />
            )}
          />
        </Stack>
      </FormModal>

      <FormModal
        open={modalAsociar}
        titulo={asociacionEnEdicion ? `Ubicación de ${asociacionEnEdicion.nombre} en el plan` : 'Asociar materia al plan'}
        onClose={() => setModalAsociar(false)}
        onSubmit={asociarForm.handleSubmit(onSubmitAsociar)}
        submitting={asociarMutation.isPending}
      >
        <Stack spacing={2} sx={{ mt: 1 }}>
          <Controller
            name="materiaId"
            control={asociarForm.control}
            render={({ field }) => (
              <TextField
                select
                label="Materia"
                fullWidth
                disabled={!!asociacionEnEdicion}
                error={!!asociarForm.formState.errors.materiaId}
                helperText={asociarForm.formState.errors.materiaId?.message}
                {...field}
                value={field.value ?? ''}
              >
                {asociacionEnEdicion && (
                  <MenuItem value={asociacionEnEdicion.materiaId}>
                    {asociacionEnEdicion.codigo} — {asociacionEnEdicion.nombre}
                  </MenuItem>
                )}
                {!asociacionEnEdicion &&
                  materiasNoAsociadas.map((m) => (
                    <MenuItem key={m.id} value={m.id}>
                      {m.codigo} — {m.nombre} ({etiquetaModalidad(m.modalidad)})
                    </MenuItem>
                  ))}
              </TextField>
            )}
          />
          <TextField
            label="Año"
            type="number"
            fullWidth
            error={!!asociarForm.formState.errors.anio}
            helperText={asociarForm.formState.errors.anio?.message}
            {...asociarForm.register('anio')}
          />
          {materiaElegidaEsAnual ? (
            <Alert severity="info">Materia anual: se cursa todo el año, no lleva cuatrimestre.</Alert>
          ) : (
            <Controller
              name="cuatrimestre"
              control={asociarForm.control}
              render={({ field }) => (
                <TextField
                  select
                  label="Cuatrimestre"
                  fullWidth
                  error={!!asociarForm.formState.errors.cuatrimestre}
                  helperText={asociarForm.formState.errors.cuatrimestre?.message}
                  {...field}
                  value={field.value ?? ''}
                >
                  <MenuItem value={1}>1°</MenuItem>
                  <MenuItem value={2}>2°</MenuItem>
                </TextField>
              )}
            />
          )}
        </Stack>
      </FormModal>

      <ConfirmDialog
        open={!!asociacionAQuitar}
        titulo="Quitar materia del plan"
        mensaje={`¿Quitar "${asociacionAQuitar?.nombre}" de este plan? La materia no se borra, solo deja de estar en la malla.`}
        onConfirm={() => asociacionAQuitar && quitarMutation.mutate(asociacionAQuitar)}
        onCancel={() => setAsociacionAQuitar(null)}
      />
    </Stack>
  );
}
