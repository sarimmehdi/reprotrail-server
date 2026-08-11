alter table traces
    drop constraint traces_storage_state_known;

alter table traces
    add constraint traces_storage_state_known
        check (storage_state in ('pending', 'available', 'failed', 'deleting', 'delete_failed'));
