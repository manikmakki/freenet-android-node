# Phase 4 Contract Fixture

`test_contract_mock_aligned.wasm` is compiled from the existing Freenet core
fixture at `tests/test-contract-mock-aligned`. Freenet uses that fixture to
compare its production Wasmtime engine with the mock executor's contract
semantics.

The packaged artifact is built from the Freenet baseline recorded in
`docs/BASELINE.md`. Its SHA-256 digest is:

```text
fed7e208fb711822f060f030f856e97580cd9b1fc02f79e7a6dcac690bbc982c
```

Reproduce and replace it inside the pinned development container:

```bash
docker compose run --rm dev scripts/prepare-contract-fixture.sh
```

The preparation script rejects a digest that differs from the recorded
baseline, preventing an unnoticed fixture change from altering the Android
feasibility test.
