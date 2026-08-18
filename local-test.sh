
  # 1. Migrations, once.
  FLYWAY_URL=jdbc:postgresql://localhost:5432/matchmaker \
  FLYWAY_USER=matchmaker FLYWAY_PASSWORD=matchmaker ./flyway.sh

  # 2. API on :8080.
  mill -j 4 --ticker false matchmaker.api.runMain com.vivi.matchmaker.api.LocalServer &

  # 3. UI on :5173.
  mill -j 4 --ticker false matchmaker.ui.fastLinkJS
  ln -sf ../../out/matchmaker/ui/fastLinkJS.dest/main.js matchmaker/ui/main.js
  python3 -m http.server 5173 --directory matchmaker/ui &
echo "Open http://localhost:5173/index.local.html "


