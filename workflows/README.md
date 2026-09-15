# Workflows

`build.yml` lives here rather than in `.github/workflows/` because
`.github/` is a protected write path for the tool that wrote this repo onto the
Windows box — the same reason Tombot and SupplyChain carry theirs this way.

To activate it:

```
mkdir -p .github/workflows
mv workflows/build.yml .github/workflows/build.yml
```

The `verify` job runs everything in `tools/`, including the Chromium behaviour
suite, and uploads the rendered skin screenshots as an artifact. The `build` job
is the first thing to ever put this code through a Kotlin compiler.
