alter table traces
    add column reservation_id uuid not null default gen_random_uuid();

alter table traces
    alter column reservation_id drop default;
