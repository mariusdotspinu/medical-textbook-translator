-- Dry runs used to write their fake output (expanded text with injected diacritics) into
-- translation memory. Lookups match on content, language and glossary version, not model,
-- so a later live run would have been served that fake text instead of calling the API.
-- Dry runs no longer touch the memory; this removes what earlier ones left behind.
-- 'dry-run' is DryRunTranslationAdapter.name().

delete from translation_unit where model = 'dry-run';
