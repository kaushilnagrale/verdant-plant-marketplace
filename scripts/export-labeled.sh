#!/usr/bin/env bash
psql "postgresql://verdant:verdant@localhost:5433/verdant" -At -F, -c "
  SELECT l.raw_title, COALESCE(cp.botanical_name, 'NONE')
    FROM mapping_decisions d
    JOIN listings l ON l.id = d.listing_id
    LEFT JOIN canonical_products cp ON cp.id = d.canonical_product_id
   WHERE d.decided_by = 'HUMAN'
" >> app/src/test/resources/labeled/listing-titles.csv
