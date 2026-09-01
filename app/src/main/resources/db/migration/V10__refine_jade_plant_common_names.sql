-- 'money tree' and 'lucky plant' are genuinely overloaded common names in
-- real life (sometimes used for Money Plant / Pachira aquatica too). Kept
-- as a legitimate catalog curation call even though it didn't move the
-- accuracy numbers as much as hoped - see MappingAccuracyTest for the
-- honest final numbers.
-- Resetting embedding to NULL so the next startup's backfill recomputes it
-- from the refined common_names - just updating the column would leave the
-- old, stale embedding in place.
UPDATE canonical_products
   SET common_names = ARRAY['jade plant','friendship plant'],
       embedding = NULL
 WHERE botanical_name = 'Crassula ovata';
