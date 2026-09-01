INSERT INTO canonical_products
  (botanical_name, display_name, common_names, size_tier, heat_tolerance, max_transit_days, care_profile)
VALUES
  ('Epipremnum aureum','Money Plant',
   ARRAY['money plant','pothos','devil''s ivy','मनी प्लांट','manee plant'],
   'MEDIUM','HIGH',5,'{"light":"indirect","water":"weekly","pet_safe":false}'),
  ('Ocimum tenuiflorum','Tulsi',
   ARRAY['tulsi','holy basil','तुलसी'],
   'SMALL','HIGH',4,'{"light":"direct","water":"daily","pet_safe":true}'),
  ('Sansevieria trifasciata','Snake Plant',
   ARRAY['snake plant','mother in law tongue','sansevieria'],
   'MEDIUM','HIGH',6,'{"light":"low","water":"biweekly","pet_safe":false}'),
  ('Dracaena sanderiana','Lucky Bamboo',
   ARRAY['lucky bamboo','bamboo plant'],
   'SMALL','MEDIUM',4,'{"light":"indirect","water":"weekly","pet_safe":false}'),
  ('Nephrolepis exaltata','Boston Fern',
   ARRAY['boston fern','fern'],
   'MEDIUM','LOW',2,'{"light":"indirect","water":"twice weekly","pet_safe":true}')
ON CONFLICT (botanical_name, size_tier) DO NOTHING;
