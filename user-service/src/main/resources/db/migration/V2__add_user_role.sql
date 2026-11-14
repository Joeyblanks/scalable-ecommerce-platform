-- Existing users become CUSTOMER. ADMINs are only created by the startup bootstrap.
ALTER TABLE users ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'CUSTOMER';
ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN ('CUSTOMER', 'ADMIN'));
