-- One flight number can fly more than one leg on a day (AV 105: BOG-CUZ, then CUZ-LPB).
-- `schedule` keeps the first leg, so rows written before this still read as they did;
-- the rest go here, as a json array, and are absent for an ordinary single-leg flight.
ALTER TABLE flight_records ADD COLUMN other_legs jsonb;
