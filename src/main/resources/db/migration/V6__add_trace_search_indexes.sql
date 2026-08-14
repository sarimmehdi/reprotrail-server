create index traces_project_available_started_idx
    on traces (project_id, started_at desc, session_id desc)
    where storage_state = 'available';

create index traces_project_available_package_started_idx
    on traces (project_id, package_name, started_at desc, session_id desc)
    where storage_state = 'available';
