# Edifika · Microservicio IAM

Autenticación (JWT), usuarios y roles (`ADMIN`, `OWNER`, `TENANT`).

## Configuración

Las variables se leen del entorno o de un archivo `.env` en la raíz del proyecto (`spring.config.import`).
Copia `.env.example` a `.env` y completa los valores. El `.env` no se sube a git.

| Variable | Obligatoria | Uso |
|---|---|---|
| `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | sí (fuera del perfil `local`) | PostgreSQL |
| `JWT_SECRET` | sí | Firma de los JWT. Debe ser el mismo en todos los servicios. |
| `APP_INTERNAL_TOKEN` | sí | Token de los endpoints internos `/api/v1/authentication/internal/**` (lo usa Residential) |
| `SEED_ADMIN_PASSWORD`, `SEED_OWNER_PASSWORD` | no | Contraseñas de los usuarios demo. Vacías = no se crean. |

Ya no hay valores por defecto para la base de datos, `JWT_SECRET` ni `APP_INTERNAL_TOKEN`: sin ellos el servicio no arranca.

## Perfil `local` (desarrollo)

`src/main/resources/application-local.properties` se activa con `SPRING_PROFILES_ACTIVE=local`.
Define el puerto, PostgreSQL en `localhost` (`postgres`/`password`), un pool pequeño y los datos de prueba (`data.sql`).
En el perfil por defecto `data.sql` no se ejecuta (`spring.sql.init.mode=never`).

### Usuarios demo (solo perfil `local`)

| Email | Rol | Contraseña |
|---|---|---|
| admin@edifika.com | ADMIN | `Edifika#2026` |
| propietario@edifika.com | OWNER | `Edifika#2026` |
| ana.perez@edifika.com, maria.rojas@edifika.com | OWNER | `Edifika#2026` |
| luis.gomez@edifika.com | TENANT | `Edifika#2026` |

`Edifika#2026` es la contraseña demo por defecto del perfil `local` (`SEED_ADMIN_PASSWORD` / `SEED_OWNER_PASSWORD`). No la uses fuera de desarrollo.

## Endpoints de gestión de residentes (solo ADMIN)

- `POST /api/v1/users/residents`: crea un usuario `OWNER` o `TENANT` (cualquier otro rol devuelve 400) y envía las credenciales por correo. La contraseña es opcional; si falta, se genera una temporal.
- `GET /api/v1/users/search?documentNumber=...` o `?name=...`: busca solo `OWNER`/`TENANT`, con al menos 3 caracteres.
- `POST /api/v1/authentication/sign-up` (registro público): crea siempre un `ADMIN` e ignora el rol del request.
