# Third-party licenses

Send itself is MIT licensed (see `../LICENSE`). It bundles these fonts, all under the
[SIL Open Font License 1.1](https://openfontlicense.org), whose full text is in this folder:

| Font | Used for | License file |
|---|---|---|
| Instrument Serif | Large display headlines | `OFL-InstrumentSerif.txt` |
| Geist | Interface text | `OFL-Geist.txt` |
| Geist Mono | Addresses, codes, speeds | `OFL-GeistMono.txt` |

The Windows app embeds static instances of Geist built from the variable font by `tools/make-fonts.py`.
Gradle and NuGet dependencies (AndroidX, Jetpack Compose, Kotlin coroutines) are Apache-2.0 licensed
and are downloaded at build time, not stored in this repository.
