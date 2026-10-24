#!/bin/sh
set -eu
cd "$(dirname "$0")"
node --experimental-strip-types --import 'data:text/javascript,import { register } from "node:module"; import { pathToFileURL } from "node:url"; register("./src/api/tests/ts-loader.mjs", pathToFileURL("./"));' --test ./src/api/tests/*.test.mjs
