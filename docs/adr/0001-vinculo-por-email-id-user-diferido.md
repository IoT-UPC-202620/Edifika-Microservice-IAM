# ADR 0001: Vínculo por email con id_user diferido y pre-chequeo síncrono de correo duplicado

**Status**: accepted (2026-11-10)

## Contexto

US01 «Registrar residente y vincularlo a su unidad» crea un vínculo entre una unidad residencial (Residential) y una cuenta de usuario (IAM) con rol OWNER. Residential no conoce el `idUser` hasta que IAM provisiona la cuenta, y IAM solo sabe del residente cuando Residential publica el evento de asignación. Además, el correo del residente puede ya estar registrado en IAM (Escenario E3), y hay una carrera inevitable entre cualquier pre-chequeo y la creación real en IAM. El vínculo debe poder quedar consultable mientras tanto, y nunca activo sobre una cuenta ajena.

Decisiones del esfuerzo US01: el vínculo (UserUnit) se crea por email con `id_user` diferido; el ciclo de vida es `PENDIENTE → ACTIVE → FAILED`; el correo duplicado se detecta dos veces — una antes de crear el vínculo (pre-chequeo) y otra dentro de IAM (backstop).

## Decisión

- **Vínculo por email, id_user diferido.** Residential crea el vínculo en estado `PENDIENTE` con el email del residente; `id_user` se rellena al completarse la provisión. IAM devuelve el resultado en el retorno `iam.resident.user-created` (`{email, unitId, idUser (nullable), messageId, status, reason}`, status ∈ {CREATED, REJECTED}).
- **Pre-chequeo síncrono Residential→IAM (E3).** Antes de crear el vínculo, Residential consulta `GET /api/v1/authentication/internal/users/email-exists?email=<normalized>` en IAM (header `X-Internal-Token`, propiedad `app.internal-api-token` ← env `APP_INTERNAL_TOKEN`; sin JWT de usuario). Si `exists=true` → 409 con mensaje exacto «El correo ya está registrado» y ningún vínculo se crea.
- **Fail closed.** Timeout obligatorio del lado cliente (2 s). Si IAM no responde o está caído → 503 «No se pudo verificar el correo; inténtalo de nuevo» y ningún vínculo se crea. Nunca se infiere «no existe» ante un error.
- **Backstop asíncrono (REJECTED → FAILED).** Si el correo ya existía en IAM (carrera posterior al pre-chequeo), IAM publica el retorno con `status=REJECTED`, `reason=EMAIL_ALREADY_REGISTERED`, `idUser=null` afterCommit. Residential marca el vínculo `PENDIENTE` correspondiente como `FAILED` con motivo «El correo ya está registrado». `CREATED` → `ACTIVE` (o `FAILED` si colisiona un titular activo, ADR vinculado a E2).
- Este ADR es referenciado desde el endpoint interno (`InternalUsersController`) y desde el cliente HTTP de Residential.

## Alternativas descartadas

- **Correlación por evento de creación (sin pre-chequeo).** Detecta el duplicado tarde — después de que el admin ya recibió un 201 sin feedback — y mueve el E3 completo al canal asíncrono. No cumple la aceptación del escenario E3 (mensaje exacto en el POST).
- **Lectura directa de la base de datos de IAM.** Acopla Residential al esquema de IAM (dos bounded contexts, dos buses de despliegue), rompe la encapsulación y obliga a mantener contrato de tablas. El endpoint interno con token comparte solo «¿existe este email?», nada más.

## Consecuencias

- Dependencia síncrona de disponibilidad de IAM en el camino de registro: si IAM está caído o lento (>2 s), ningún residente puede registrarse (503 fail closed). A cambio, el admin nunca crea un vínculo sobre un correo ajeno.
- El timeout de 2 s del cliente es obligatorio y forma parte del contrato; sin él, el fail closed degenera en timeout de plataforma (30 s) y mala UX.
- El backstop sigue siendo necesario: el pre-chequeo no cierra la carrera con la creación. El vínculo puede terminar `FAILED` incluso tras un 201; la UI lo expone como estado consultable.
- La dependencia es unidireccional (Residential → IAM); IAM no llama a Residential. El contrato del endpoint (`{"exists": bool}`) debe permanecer estable.
- Si en el futuro se migra a otro mecanismo de correlación, este ADR se supersede; no eliminar sin migrar ambos lados.
