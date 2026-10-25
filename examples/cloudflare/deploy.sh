#!/bin/bash
# the to-do list on Cloudflare: linked, copied beside the Worker's entry, then `wrangler dev` (the default) or `deploy`
# (a token in the environment: CLOUDFLARE_API_TOKEN, CLOUDFLARE_ACCOUNT_ID)
set -euo pipefail
cd "$(dirname "$0")/../.."
sbt -Dplatforms=js examplesCloudflareJS/fullLinkJS
cp examples/cloudflare/.js/target/scala-3.3.8/okay-faces-examples-cloudflare-opt/main.js examples/cloudflare/worker/main.js
cd examples/cloudflare/worker
npx -y wrangler@4 "${1:-dev}" "${@:2}"
