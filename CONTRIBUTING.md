# Contributing to Bhash

For questions or collaboration, join the [Bhash Discord community](https://discord.gg/qC5zdJy6Qw) or read [SUPPORT.md](SUPPORT.md).

Issues, language-design feedback, tests, documentation and pull requests are welcome.
Please describe the problem and include a small Bhash example where possible.

## Contribution licensing

Unless you explicitly state otherwise, contributions intentionally submitted for
inclusion in Bhash are licensed under the Apache License, Version 2.0, as described
in Section 5 of [LICENSE](LICENSE). You retain your copyright; no copyright assignment
is required by this contribution policy.

Submit only material you are authorized to contribute. Preserve existing notices
and identify any third-party code and its license. Discuss contributions proposed
under different licensing terms with the maintainers before submitting them.

## Development

Build and run the regression tests with JDK 17+ and Maven: `mvn verify`.
CLR tests also require the .NET 10 SDK. Browser checks require the .NET WebAssembly
workload and Chromium; see [browser testing](docs/wasm-browser.md).
Update the language documentation and examples when changing syntax or semantics.

Build every example application with `python scripts/build_examples.py` after packaging
the compiler. See [the example build guide](docs/building-examples.md) for target-specific
outputs and native applicability. Build-script regression checks run with
`python -m unittest discover -s scripts -p "test_build_examples.py"`.
