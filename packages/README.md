# Bhash package index / संकुल सूची

[`index.json`](index.json) is a versioned, reviewable discovery index. It maps an
exact package name/version to a complete Git commit, package directory and
manifest/source fingerprints. It does not execute packages or grant native-code
capabilities. The compiler's default discovery URL points to this file on main.

यह समीक्षा योग्य सूची निश्चित नाम/संस्करण को Git commit, directory और fingerprints
से जोड़ती है। यह code नहीं चलाती और native capability नहीं देती।

[English publishing guide](../docs/package-distribution.en.md) ·
[हिन्दी मार्गदर्शिका](../tutorial/package-distribution/README.md)

## Contributions / योगदान

1. Commit a library with an exact semantic version, public Bhash declarations,
   `LICENSE` and preferably `README.md`.
2. Run `package publish` against a local copy of the index. Publish permanent
   release assets in the library repository if downloadable archives are wanted.
3. Open a PR containing only the new entry and its documentation. Reviewers check
   source ownership/license, immutable commit availability, description, version,
   fingerprints, transitive dependencies and any provider artifacts.
4. Existing name/version entries cannot change. Publish another version for any
   change. The index owner controls namespace allocation through repository reviews;
   a proposed name does not automatically establish ownership.

पहले library commit करें, फिर स्थानीय index में नई entry बनाएँ और PR दें। स्रोत,
license, commit, dependencies और providers की समीक्षा आवश्यक है। पुराने संस्करण
की entry न बदलें; नया संस्करण दें। नाम का अधिकार index owner की समीक्षा से तय है।

The example [`bhash-text-utils`](examples/text-utils/) is pinned to a permanent
`package-bhash-text-utils-1.0.1` tag's commit. Its distribution name is ASCII;
the imported namespace `उपयोगीपाठ` remains Devanagari. Index hashes are integrity
checks, not signatures or a guarantee that a package is safe to run.

उदाहरण का Git commit स्थायी tag से सुरक्षित है। वितरण का नाम ASCII और आयात का
नाम देवनागरी है। Hash जाँच publisher signature या execution सुरक्षा की गारंटी नहीं है।
