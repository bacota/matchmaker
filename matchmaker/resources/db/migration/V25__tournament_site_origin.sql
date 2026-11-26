-- The game site a tournament was made on: the origin of the request that created it, when that was
-- a configured site (UI_SITES). NULL is the main domain, and every tournament made before this.
-- Read only to choose the site its mail links to. See design/game-sites-plan.md.
ALTER TABLE tournament ADD COLUMN site_origin text;
