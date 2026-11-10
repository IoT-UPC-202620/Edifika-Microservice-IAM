# Research: RabbitMQ y SMTP en dev/test (US01 — Vinculación Residente)

Fecha: 2026-11-10. Ticket: `.scratch/us01-vinculacion-residente/tickets/02-rabbitmq-smtp-dev.md` (mapa US01).
Rama throwaway `research/us01-amqp-smtp` — nunca se mergea, sin PR.

Fuentes primarias: código de los micros en `C:\dev\iot`, docs oficiales (Docker Hub `rabbitmq`, Testcontainers for Java, Spring Boot reference, repo Mailpit). "Hechos, no decisiones".

## 1. RabbitMQ en este entorno

- **Docker CLI disponible**: `Docker version 29.9.2`. **PERO el daemon NO estaba corriendo** al verificar (2026-11-10): `failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine`. Hay que arrancar Docker Desktop antes de cualquier `docker run` / Testcontainers. No hay imágenes cacheadas localmente (no se pudo listar por daemon caído).
- **No hay RabbitMQ local instalado** como servicio nativo conocido; no hay `docker-compose.yml` en el workspace para levantarlo (no existe compose hoy).
- **Imagen recomendada**: `rabbitmq:4-management` (tag actual `4-management` → 4.4.x; verificado en Docker Hub: tags `4.4.0`, `4.4`, `4`, `latest` y variantes `-management`). Es la misma imagen que ya usa IoT-Access-Management en sus tests (`rabbitmq:4-management`). La variante `management` incluye el plugin de management en el puerto **15672** (UI) y AMQP en **5672**.
  - Fuente: https://hub.docker.com/_/rabbitmq
- **Credenciales por defecto**: `guest` / `guest` (solo válidas desde localhost por defecto en RabbitMQ), vhost `/`. Se pueden cambiar con `RABBITMQ_DEFAULT_USER` / `RABBITMQ_DEFAULT_PASS` / `RABBITMQ_DEFAULT_VHOST`.
  - Fuente: https://hub.docker.com/_/rabbitmq (sección "Setting default user and password")
- **Cómo levantarlo en dev** (sin compose hoy):

  ```powershell
  docker run -d --hostname edifika-rabbit --name edifika-rabbit `
    -p 5672:5672 -p 15672:15672 `
    rabbitmq:4-management
  # UI: http://localhost:15672  (guest/guest)
  ```

  Nota: el docs oficial recomienda fijar `--hostname` explícito para estabilidad de datos. No hay compose; si se quiere uno, sería un archivo nuevo (fuera del alcance de esta investigación).
- Alternativa sin Docker en absoluto: ninguna razonable para dev real; para tests de CI, Testcontainers (sección 3).

## 2. Cómo lo usa hoy IoT-Access-Management (patrón a imitar)

Repo: `C:\dev\iot\Edifika-Microservice-IoT-Access-Management` (Spring Boot **4.0.6**, Java **21**, spring-amqp vía `spring-boot-starter-amqp`).

### `RabbitMqConfiguration` (`src/main/java/com/edifika/access/access/infrastructure/messaging/rabbitmq/configuration/RabbitMqConfiguration.java`)

- Exchanges (TopicExchange, durable, no auto-delete):
  - `edifika.events` (EVENTS_EXCHANGE) — eventos de integración
  - `edifika.edge.commands` (COMMANDS_EXCHANGE) — comandos a edge
  - `edifika.dlx` (DLX_EXCHANGE) — dead-letter exchange
- Colas declaradas:
  - `access.edge-events` — durable, con args `x-dead-letter-exchange=edifika.dlx` y `x-dead-letter-routing-key=access.edge-events`
  - `access.edge-events.dlq` — durable, ligada al DLX con routing key `access.edge-events`
- Bindings: `access.edge-events` ← `edifika.events` con routing keys `edge.access-attempt`, `edge.credential-changed`, `edge.device-status`, `edge.command-result`, `edge.card-enrolled`, `edge.device-discovered`. Topología empaquetada en un bean `Declarables`.
- `MessageConverter`: `Jackson2JsonMessageConverter` con `ObjectMapper` (JavaTimeModule, sin timestamps como arrays).
- `RabbitTemplate`: bean propio con el converter inyectado. **NO configura publisher confirms** (`CorrelationData`, `setConfirmCallback`, `spring.rabbitmq.publisher-confirm-type`) en ningún punto del código — verificado con búsqueda de `confirm|CorrelationData` (solo falsos positivos de dominio). Los publishers (`RabbitIntegrationEventPublisher`, `RabbitEdgeCommandPublisher`) usan `convertAndSend` con `MessageDeliveryMode.PERSISTENT` y log estructurado; fire-and-forget + `Jackson2JsonMessageConverter`.
- Listener factory: `SimpleRabbitListenerContainerFactory` con `defaultRequeueRejected=false`, retry stateless `maxRetries(3)`, backoff exponencial 1000ms ×2.0 hasta 4000ms, `RejectAndDontRequeueRecoverer` → los mensajes que agotan reintentos van al DLQ vía DLX.
- `@RabbitListener` en `EdgeEventConsumer`.

### application.properties (patrón env-var con defaults)

```properties
spring.rabbitmq.host=${RABBITMQ_HOST:localhost}
spring.rabbitmq.port=${RABBITMQ_PORT:5672}
spring.rabbitmq.username=${RABBITMQ_USERNAME:guest}
spring.rabbitmq.password=${RABBITMQ_PASSWORD:guest}
spring.rabbitmq.virtual-host=${RABBITMQ_VHOST:/}
spring.rabbitmq.ssl.enabled=${RABBITMQ_SSL:false}
```

Los mismos nombres de env vars están documentados en el README del micro (`RABBITMQ_HOST/PORT/USERNAME/PASSWORD/VIRTUAL_HOST`). Nota: el README documenta `RABBITMQ_VIRTUAL_HOST` pero el properties usa `RABBITMQ_VHOST` — discrepancia menor existente.

### Tests

- `TestcontainersConfiguration` (`src/test/java/.../support/TestcontainersConfiguration.java`): `@ServiceConnection` con `RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management"))` y `PostgreSQLContainer("postgres:17-alpine")`. Tests IT usan `AmqpAdmin` para declarar/borrar colas efímeras y `RabbitTemplate` para publicar.

### Para imitar el patrón en IAM/Residential

- Declarar exchange(s)/cola(s)/DLQ + bindings en una `@Configuration` con `Declarables`.
- `Jackson2JsonMessageConverter`, `RabbitTemplate` propio, `SimpleRabbitListenerContainerFactory` con retry + DLQ.
- Config vía env vars con defaults localhost:5672 guest/guest.
- Publisher confirms: **no se usan hoy**; el spec no los exige — patrón actual = persistente + DLQ + idempotencia (`ProcessedEventGuard`).

## 3. Testcontainers para RabbitMQ con Spring Boot 3.4.5 / Java 21

- Módulo: `org.testcontainers:rabbitmq` (clase `RabbitMQContainer`). Docs oficiales vigentes listan versión **2.0.5** (`testcontainers-rabbitmq:2.0.5`) — pero eso es Testcontainers for Java 2.x.
  - Fuente: https://java.testcontainers.org/modules/rabbitmq/
- **Spring Boot 3.4.5 gestiona Testcontainers 1.20.6** (`<testcontainers.version>1.20.6</testcontainers.version>` en `spring-boot-dependencies-3.4.5.pom`, verificado en repo1.maven.org) y spring-amqp 3.2.5.
  - Fuente: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/3.4.5/spring-boot-dependencies-3.4.5.pom
- Por tanto, con Boot 3.4.5 / Java 21 lo correcto es **no fijar versión** y usar el BOM: `org.testcontainers:rabbitmq` (test scope) → resuelve 1.20.6. Testcontainers 2.x requeriría override manual y no es lo que gestiona el BOM 3.4.5 (sí lo usa, con override a 1.20.4, IoT-Access-Management en Boot 4.0.6 — otro dato: **IAM está en Spring Boot 4.0.6 / Java 17**, no 3.4.5/21; el ticket menciona 3.4.5/Java 21 que corresponde a **Residential-Management**).
- Requisitos: Docker daemon corriendo (hoy caído — arrancar Docker Desktop), `@ServiceConnection` (Spring Boot 3.1+) sobre `RabbitMQContainer` para inyectar `spring.rabbitmq.*` automáticamente, o `@DynamicPropertySource` manual.
- Alternativas ligeras: no hay fake AMQP embebido maduro (a diferencia de H2). `spring-rabbit-test` ofrece `TestRabbitTemplate`/mocks pero no un broker. Para tests de integración reales: Testcontainers es la vía. Para tests unitarios del handler: mockear `RabbitTemplate`/`@RabbitListener` con Mockito (sin broker).
- Patrón ITs existente en IoT-AM: contenedor compartido `@ServiceConnection` + colas efímeras declaradas/borradas por test vía `AmqpAdmin`.

## 4. SMTP para dev sin cuenta real: Mailpit/Mailhog vs log mode

### Mailpit (recomendado; MailHog está abandonado)

- Repo: https://github.com/axllent/mailpit — "email and SMTP testing tool with API". MailHog "no longer maintained" (sin updates de seguridad desde hace años).
- Puertos por defecto: **SMTP 1025**, **UI web 8025** (`http://localhost:8025`). API REST para asserts en tests de integración.
- Imagen Docker oficial: `axllent/mailpit` (`docker run -d -p 1025:1025 -p 8025:8025 axllent/mailpit`). Binario estático también disponible.
- Sin credenciales, sin cuenta real, captura todo el correo; ideal para dev y para verificar "el correo incluye email + password temporal + URL".

### MailHog

- Mismos puertos (1025/8025), pero proyecto sin mantenimiento → no recomendado para empezar algo nuevo.

### spring-boot-starter-mail con "modo log"

- **No existe un "modo log" nativo** en `spring-boot-starter-mail` / `JavaMailSender`. Lo que sí existe:
  - Auto-config solo si `spring.mail.host` está definido; sin host, no hay `JavaMailSender`.
  - Patrón habitual: bean stub/fake de `JavaMailSender` en perfil `dev` que loguea el contenido (implementación propia, ~20 líneas), o GreenMail (`com.icegreen:greenmail-junit5`) para tests de integración SMTP en-JVM.
- Comparación factual: Mailpit encaja "sin credenciales" con cero código y además da UI + API para verificación manual/E2E; el stub-log encaja si no se quiere dependencia Docker ni verificar el correo renderizado. Ambos son válidos; combinables con `spring.profiles`.

## 5. SMTP real opcional por env vars (ejemplo)

`spring-boot-starter-mail` + `spring.mail.*` (docs: Spring Boot reference "Sending Email"; propiedades de `MailProperties`). Ejemplo de `application.properties` con env vars y defaults seguros para dev (Mailpit):

```properties
spring.mail.host=${SMTP_HOST:localhost}
spring.mail.port=${SMTP_PORT:1025}
spring.mail.username=${SMTP_USER:}
spring.mail.password=${SMTP_PASS:}
spring.mail.properties.mail.smtp.auth=${SMTP_AUTH:false}
spring.mail.properties.mail.smtp.starttls.enable=${SMTP_STARTTLS:false}
spring.mail.properties.mail.smtp.ssl.enable=${SMTP_SSL:false}
spring.mail.properties.mail.smtp.connectiontimeout=5000
spring.mail.properties.mail.smtp.timeout=3000
spring.mail.properties.mail.smtp.writetimeout=5000
spring.mail.default-encoding=UTF-8
```

Para SMTP real (p.ej. Gmail/SES/relay corporativo) vía env vars:

```bash
SMTP_HOST=smtp.example.com
SMTP_PORT=587            # 587 = STARTTLS, 465 = SSL implícito
SMTP_USER=apikey-or-user
SMTP_PASS=secret
SMTP_AUTH=true
SMTP_STARTTLS=true       # STARTTLS en 587; para 465 usar SMTP_SSL=true y STARTTLS=false
SMTP_SSL=false
```

Notas:
- `spring.mail.properties.mail.smtp.starttls.enable=true` + port 587 es el caso típico; `mail.smtp.ssl.enable=true` + 465 para SSL implícito. `mail.smtp.auth=true` si hay credenciales.
- Timeouts infinitos por defecto → conviene fijar `mail.smtp.connectiontimeout/timeout/writetimeout` (docs Spring Boot).
- El remitente (`spring.mail.username` o `from` explícito en el `MimeMessage`/`SimpleMailMessage`) debe estar autorizado por el proveedor.
- Gmail personal: requiere App Password (2FA), no la password de la cuenta.
- Fuentes: https://docs.spring.io/spring-boot/reference/io/email.html ; propiedades `spring.mail.*` en el apéndice de Common Application Properties / `MailProperties`.

## Observaciones / discrepancias detectadas

- El ticket asume "Spring Boot 3.4.5 / Java 21" — correcto para **Residential-Management** (3.4.5 / 21). **IAM está en Spring Boot 4.0.6 / Java 17** y **IoT-Access-Management en Boot 4.0.6 / Java 21**. El BOM de Boot 3.4.5 gestiona Testcontainers 1.20.6; IoT-AM fuerza 1.20.4 con Boot 4.
- Docker Desktop instalado pero daemon apagado al verificar; sin imágenes cacheadas y sin compose en el workspace.
- IoT-AM no usa publisher confirms; su "garantía" de entrega = mensaje persistente + retry + DLQ + idempotencia de consumo.
- README de IoT-AM documenta `RABBITMQ_VIRTUAL_HOST` pero el código usa `RABBITMQ_VHOST`.

## Fuentes

- Docker Hub `rabbitmq` (tags, guest/guest, management 15672, 5672): https://hub.docker.com/_/rabbitmq
- Testcontainers for Java — RabbitMQ module: https://java.testcontainers.org/modules/rabbitmq/
- `spring-boot-dependencies-3.4.5.pom` (testcontainers 1.20.6, spring-amqp 3.2.5, angus-mail 2.0.3): https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/3.4.5/spring-boot-dependencies-3.4.5.pom
- Spring Boot reference — Sending Email: https://docs.spring.io/spring-boot/reference/io/email.html
- Mailpit repo (puertos 1025/8025, imagen `axllent/mailpit`, MailHog sin mantenimiento): https://github.com/axllent/mailpit
- Código local: `Edifika-Microservice-IoT-Access-Management` (`RabbitMqConfiguration.java`, `TestcontainersConfiguration.java`, `application.properties`, `pom.xml`, `README.md`), `Edifika-Microservice-IAM/pom.xml`, `Edifika-Microservice-Residential-Managemen/pom.xml`.
