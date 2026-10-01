#!/bin/sh
# ./burst.sh <BASE_URL> [extra burst.py args]
exec python3 "$(dirname "$0")/burst.py" "$@"
