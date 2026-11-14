-- Datos de prueba locales (solo corre con SEED_MODE=always). Idempotente.
-- IDs compartidos con el resto de servicios:
--   1 admin@edifika.com (ADMIN) | 2 propietario@edifika.com (OWNER, unidad 1)
--   3 ana.perez@edifika.com (OWNER, unidad 2) | 4 luis.gomez@edifika.com (TENANT, unidad 3)
--   5 maria.rojas@edifika.com (OWNER, unidad 4, morosa)
-- Contrasena de todos: Edifika#2026 (hash BCrypt generado por el propio IAM).

INSERT INTO roles (id, name) VALUES (1, 'ADMIN'), (2, 'OWNER'), (3, 'TENANT') ON CONFLICT DO NOTHING;

INSERT INTO users (id, created_at, updated_at, document_number, document_type, email, full_name, password_hash, phone, status) VALUES
  (1, now(), now(), '10000001', 'DNI', 'admin@edifika.com',       'Administrador Edifika', '$2a$10$ehwahR7DLCI1e8srQrTtTeSQMrjrUCqyUlSGRDT.8nBdcQMUZ3Quq', '900000001', 'ACTIVE'),
  (2, now(), now(), '10000002', 'DNI', 'propietario@edifika.com', 'Propietario Demo',      '$2a$10$ehwahR7DLCI1e8srQrTtTeSQMrjrUCqyUlSGRDT.8nBdcQMUZ3Quq', '900000002', 'ACTIVE'),
  (3, now(), now(), '70000003', 'DNI', 'ana.perez@edifika.com',   'Ana Perez',             '$2a$10$ehwahR7DLCI1e8srQrTtTeSQMrjrUCqyUlSGRDT.8nBdcQMUZ3Quq', '987000003', 'ACTIVE'),
  (4, now(), now(), '70000004', 'DNI', 'luis.gomez@edifika.com',  'Luis Gomez',            '$2a$10$ehwahR7DLCI1e8srQrTtTeSQMrjrUCqyUlSGRDT.8nBdcQMUZ3Quq', '987000004', 'ACTIVE'),
  (5, now(), now(), '70000005', 'DNI', 'maria.rojas@edifika.com', 'Maria Rojas',           '$2a$10$ehwahR7DLCI1e8srQrTtTeSQMrjrUCqyUlSGRDT.8nBdcQMUZ3Quq', '987000005', 'ACTIVE')
ON CONFLICT DO NOTHING;

INSERT INTO user_roles (user_id, role_id) VALUES (1, 1), (2, 2), (3, 2), (4, 3), (5, 2) ON CONFLICT DO NOTHING;

SELECT setval(pg_get_serial_sequence('roles', 'id'), (SELECT MAX(id) FROM roles));
SELECT setval(pg_get_serial_sequence('users', 'id'), (SELECT MAX(id) FROM users));
