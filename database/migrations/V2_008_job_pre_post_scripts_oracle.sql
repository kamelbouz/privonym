-- Optional SQL scripts executed before and after a Privonym Job.
-- Safe to run once after V2_007.

CREATE TABLE job_script (
    job_script_id NUMBER(19,0) NOT NULL,
    job_id NUMBER(19,0) NOT NULL,
    script_phase VARCHAR2(4 CHAR) NOT NULL,
    file_name VARCHAR2(255 CHAR) NOT NULL,
    script_content CLOB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT job_script_pk PRIMARY KEY (job_script_id),
    CONSTRAINT job_script_job_fk FOREIGN KEY (job_id)
        REFERENCES anonymization_job(job_id) ON DELETE CASCADE,
    CONSTRAINT job_script_phase_ck CHECK (script_phase IN ('PRE','POST')),
    CONSTRAINT job_script_job_phase_uk UNIQUE (job_id, script_phase)
);

CREATE SEQUENCE job_script_seq START WITH 1 INCREMENT BY 1 NOCACHE NOCYCLE;

CREATE INDEX job_script_job_ix ON job_script(job_id);

