-- One row per simulated year.
CREATE TABLE year_log (
    year             INTEGER PRIMARY KEY,
    population_start INTEGER NOT NULL,
    births           INTEGER NOT NULL,
    deaths           INTEGER NOT NULL,
    marriages        INTEGER NOT NULL,
    population_end   INTEGER NOT NULL
);
