# Contributing to HoloScan

Thanks for helping. Issues and pull requests are welcome.

## Good places to start

- **iPhone app.** `Embedder.mlpackage` (Core ML, in the release assets) takes the same input
  as the Android model and gives the same embeddings. It can search the same index
  (`android/app/src/main/assets/index.f16` + `cards.json`). The behaviour to port is in
  `android/.../CardLock.kt` (scan lock and auto-add), `Backup.kt` (CSV zip format, so
  collections move between platforms) and `Prices.kt`. The preprocessing contract is in
  `docs/TRAINING.md` §3.
- **Other trading card games.** `ml/` turns card images and metadata into an embedding index.
  A new game needs a data source (card list + images), its own paths in `ml/config.py`, and the
  same steps as in the README (embed, optionally fine-tune, export). Keep each game's index and
  card list separate so the apps can offer a game picker.
- Missing cards or images, price source fixes, UI and accessibility improvements.

For anything large, please open an issue first so we can agree on the approach.

## Ground rules

- Don't commit card images or other copyrighted artwork. The repo ships only metadata,
  embeddings and code; images are fetched locally by the pipeline.
- No ads, trackers, accounts or paid services: the app works without a backend.
- Run the tests before a pull request: `cd android && ./gradlew :app:testDebugUnitTest`.

## License of contributions

HoloScan is licensed under the [PolyForm Noncommercial License 1.0.0](LICENSE.md).
By submitting a contribution, you agree that it is licensed under the same terms,
and that you have the right to submit it. Please sign off your commits
(`git commit -s`, the [Developer Certificate of Origin](https://developercertificate.org)).
