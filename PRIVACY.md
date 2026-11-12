# HoloScan privacy policy

Effective date: 11 November 2026

HoloScan is a trading card scanner for Android, made as a free, noncommercial
project by YourAmaryllis. This policy explains what the app does with your data.
In short, **HoloScan does not collect, store or share any personal data.**

## What stays on your phone

- **Camera.** The camera is used only to identify the card you hold in the frame.
  Each frame is processed on the phone by the built-in recognition model and then
  discarded. No photo or video is saved, and none leaves the phone.
- **Your collection and settings** are stored in the app's private storage on your
  phone. They are not uploaded anywhere. Uninstalling the app deletes them.
- **Export / import.** If you export your collection, HoloScan writes a zip of CSV
  files to the location you choose. What happens to that file afterwards (for
  example, copying it to another phone) is up to you.

## Network use

HoloScan works offline. It uses the internet only when you tap
**Refresh prices** in the Collection screen. It then requests current prices for
the cards in your collection from these public price services:

- [TCGdex](https://tcgdex.dev) (`api.tcgdex.net`)
- [TCGCSV](https://tcgcsv.com) (`tcgcsv.com`)

These requests contain only card identifiers (for example `sv1-116`), no account,
name, device identifier or location. Like any website, these services can see the
IP address the request comes from. Their own privacy policies apply to that.

## What HoloScan does not do

- No account or sign-in.
- No analytics, crash reporting, advertising or tracking SDKs.
- No location, contacts, microphone or other permissions besides the camera and
  internet access (for price refresh).
- No sale or sharing of data with anyone.

## Children

HoloScan does not knowingly collect personal information from anyone, including
children, because it does not collect personal information at all.

## Changes

If this policy changes, the new version will be published at this address with a
new effective date. The full history is in this repository's commit log.

## Contact

Questions or concerns: open an issue at
<https://github.com/YourAmaryllis/HoloScan/issues>.
