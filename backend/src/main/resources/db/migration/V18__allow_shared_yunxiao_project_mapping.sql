-- Multiple local governance projects may share one Yunxiao execution space.
ALTER TABLE yunxiao_project_mapping
    DROP INDEX uk_yunxiao_project_mapping_external;
