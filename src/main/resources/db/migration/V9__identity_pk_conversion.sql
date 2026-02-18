-- ============================================================
-- V9: Convert SERIAL PKs to GENERATED ALWAYS AS IDENTITY
-- Applies to: public.users, public.repository_history
-- No JPA changes needed — entities already use GenerationType.IDENTITY.
-- ============================================================

-- users.id
ALTER TABLE public.users ALTER COLUMN id DROP DEFAULT;
ALTER TABLE public.users ALTER COLUMN id
    ADD GENERATED ALWAYS AS IDENTITY (START WITH 1 INCREMENT BY 1);

-- repository_history.id
ALTER TABLE public.repository_history ALTER COLUMN id DROP DEFAULT;
ALTER TABLE public.repository_history ALTER COLUMN id
    ADD GENERATED ALWAYS AS IDENTITY (START WITH 1 INCREMENT BY 1);

-- Drop orphaned BIGSERIAL sequences
DROP SEQUENCE IF EXISTS public.users_id_seq;
DROP SEQUENCE IF EXISTS public.repository_history_id_seq;
