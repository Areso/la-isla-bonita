-- World settings: simple key/value store.
CREATE TABLE world (
    id    TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

INSERT INTO world (id, value) VALUES ('starting_year', '1900');
INSERT INTO world (id, value) VALUES ('ending_year', '2025');

-- Everyone who has ever lived on the island. Dates are ISO-8601 (YYYY-MM-DD).
CREATE TABLE people (
    id               INTEGER PRIMARY KEY,
    name             TEXT    NOT NULL,
    sex              TEXT    NOT NULL CHECK (sex IN ('M', 'F')),
    dob              TEXT    NOT NULL,
    dod              TEXT,
    father           INTEGER REFERENCES people (id),
    mother           INTEGER REFERENCES people (id),
    -- Deferred: a couple is inserted in one transaction, each pointing at the other.
    spouse           INTEGER REFERENCES people (id) DEFERRABLE INITIALLY DEFERRED,
    date_of_marriage TEXT
);

CREATE INDEX idx_people_dod ON people (dod);
CREATE INDEX idx_people_father ON people (father);
CREATE INDEX idx_people_mother ON people (mother);
CREATE INDEX idx_people_name ON people (name);
