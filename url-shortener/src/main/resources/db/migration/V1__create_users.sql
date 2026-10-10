-- =============================================
--  V1: Schema ban đầu — users + user_roles
--  Khớp với entity User (trước đây do Hibernate ddl-auto sinh ra).
-- =============================================

CREATE TABLE users (
    id             UUID         NOT NULL,
    username       VARCHAR(50)  NOT NULL,
    email          VARCHAR(255) NOT NULL,
    password       VARCHAR(255) NOT NULL,
    full_name      VARCHAR(100),
    status         VARCHAR(20)  NOT NULL,
    email_verified BOOLEAN      NOT NULL,
    last_login_at  TIMESTAMP(6),
    created_at     TIMESTAMP(6) NOT NULL,
    updated_at     TIMESTAMP(6) NOT NULL,

    CONSTRAINT users_pkey         PRIMARY KEY (id),
    CONSTRAINT idx_users_username UNIQUE (username),
    CONSTRAINT idx_users_email    UNIQUE (email),
    CONSTRAINT users_status_check CHECK (status IN ('ACTIVE', 'INACTIVE', 'BANNED'))
);

CREATE INDEX idx_users_status ON users (status);

CREATE TABLE user_roles (
    user_id UUID        NOT NULL,
    role    VARCHAR(30) NOT NULL,

    CONSTRAINT user_roles_pkey       PRIMARY KEY (user_id, role),
    CONSTRAINT fk_user_roles_user    FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT user_roles_role_check CHECK (role IN ('ROLE_USER', 'ROLE_ADMIN'))
);

CREATE INDEX idx_user_roles_user_id ON user_roles (user_id);
