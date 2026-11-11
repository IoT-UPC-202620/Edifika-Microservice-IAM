# Edifika IAM — Glosario

Vocabulario de dominio del módulo IAM (identidad y acceso Edifika). Este glosario describe el dominio, no la implementación.

## Language

**Titular**:
El residente que es propietario (rol OWNER) de una unidad y está activo, con fecha de fin de titularidad vacía. Un correo ya registrado no implica titularidad en una unidad.
_Avoid_: dueño, poseedor, el que está en la unidad

**Vínculo**:
La relación entre un residente y una unidad (un UserUnit en el dominio Residential), creada por email con el identificador de usuario IAM diferido hasta que la cuenta existe.
_Avoid_: linkeo, relación, asignación

**PENDIENTE**:
Estado inicial del vínculo: creado, pero el usuario IAM aún no está provisionado (o su resultado aún no volvió). Un vínculo PENDIENTE no designa titular.
_Avoid_: pendiente de aprobación, draft

**ACTIVE**:
Estado del vínculo una vez que IAM confirmó la provisión (CREATED). El titular existe como cuenta IAM con rol OWNER y el vínculo está cerrado.
_Avoid_: vinculado, activo

**FAILED**:
Estado terminal del vínculo cuando la provisión fue rechazada (p. ej. correo ya registrado) o colisionó con un titular activo en la unidad. Conserva el motivo; nunca queda ACTIVE sobre una cuenta ajena.
_Avoid_: fallido, error, cancelado

**Ciclo del vínculo**:
`PENDIENTE → ACTIVE` (provisión confirmada) o `PENDIENTE → FAILED` (rechazo o colisión). Las transiciones las mueve la provisión/retorno de IAM (vía el consumer de completitud en Residential); el vínculo no transita a ACTIVE sin confirmación de IAM.
_Avoid_: máquina de estados del vínculo
