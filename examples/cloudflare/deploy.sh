#!/bin/bash
# the to-do list on Cloudflare: its Worker's script and the thin page's linked, copied beside the Worker's entry, then
# `wrangler dev` (the default) or `deploy` (a token in the environment: CLOUDFLARE_API_TOKEN, CLOUDFLARE_ACCOUNT_ID)
set -euo pipefail
cd "$(dirname "$0")/../.."
# the thin page: the Laminar one (the default), or the DOM one with no library (PAGE=dom)
page=$([ "${PAGE:-laminar}" = dom ] && echo remote-dom || echo remote)
sbt -Dplatforms=js examplesCloudflareJS/fullLinkJS examplesRemoteJS/fullLinkJS examplesRemoteDomJS/fullLinkJS
cp examples/cloudflare/.js/target/scala-3.3.8/okay-faces-examples-cloudflare-opt/main.js examples/cloudflare/worker/main.js
cp "examples/$page/.js/target/scala-3.3.8/okay-faces-examples-$page-opt/main.js" examples/cloudflare/worker/page.js.txt
cd examples/cloudflare/worker
npx -y wrangler@4 "${1:-dev}" "${@:2}"
