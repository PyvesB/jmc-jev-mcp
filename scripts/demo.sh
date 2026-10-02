#!/bin/sh
# Prints every Jev classification of src/test/resources/recordings/wldf.jfr.
set -e

if [ -z "$JEV_KEY" ]; then
	echo "JEV_KEY must be set to run the demo." >&2
	exit 1
fi

cd "$(dirname "$0")/.."
exec mvn -q test -Pdemo -Dspotless.check.skip=true "$@"
