#!/bin/bash
# A local stand-in for the database and the REST layer of the Supabase project, for tests:
# a throw-away PostgreSQL with schema.sql applied, and PostgREST in front of it.
#
#   start.sh          start (creating everything on first use), apply schema.sql, run the SQL tests
#   start.sh reset    throw the database away first
#   start.sh stop
#
# Needs PostgreSQL 15 or newer (initdb, pg_ctl, psql) and PostgREST. On Linux x86-64 PostgREST is
# downloaded when missing; elsewhere point POSTGREST at the binary. Settings (environment):
#   PG_BIN      folder with the PostgreSQL programs (found through pg_config when not given)
#   STACK_DIR   where the data lives (default: $TMPDIR/mps-cloudstack)
#   POSTGREST   path of the postgrest binary
#
# The unit tests of the app (CloudApiTest) find the stack at http://127.0.0.1:54330 and are
# skipped when it is not running.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
SCHEMA="$HERE/../schema.sql"
ROOT="${STACK_DIR:-${TMPDIR:-/tmp}/mps-cloudstack}"
DATA="$ROOT/pgdata"
PORT=54329
REST_PORT=54330
SECRET="mps-local-test-secret-0123456789abcdef0123456789"

BIN="${PG_BIN:-}"
if [ -z "$BIN" ]; then
  if command -v pg_config > /dev/null; then BIN="$(pg_config --bindir)"; fi
  for candidate in /usr/lib/postgresql/*/bin; do [ -x "$candidate/initdb" ] && BIN="$candidate"; done
fi
if [ ! -x "$BIN/initdb" ]; then echo "PostgreSQL not found: set PG_BIN"; exit 1; fi
PSQL="$BIN/psql -q -v ON_ERROR_STOP=1 -h /tmp -p $PORT -U postgres"
REST="${POSTGREST:-$ROOT/postgrest}"

stop_rest() { [ -f "$ROOT/postgrest.pid" ] && kill "$(cat "$ROOT/postgrest.pid")" 2> /dev/null || true; rm -f "$ROOT/postgrest.pid"; }

if [ "$1" = "stop" ]; then
  stop_rest
  "$BIN/pg_ctl" -D "$DATA" -m fast stop 2> /dev/null || true
  exit 0
fi

mkdir -p "$ROOT"
if [ ! -x "$REST" ]; then
  if [ "$(uname -sm)" != "Linux x86_64" ]; then echo "PostgREST not found: set POSTGREST"; exit 1; fi
  curl -sL --max-time 180 -o "$ROOT/postgrest.tar.xz" \
    https://github.com/PostgREST/postgrest/releases/download/v16.5/postgrest-v16.5-linux-static-x86-64.tar.xz
  tar -xJf "$ROOT/postgrest.tar.xz" -C "$ROOT"
fi

if [ ! -d "$DATA" ]; then
  "$BIN/initdb" -D "$DATA" -U postgres --auth=trust --locale=C.UTF-8 -E UTF8 > "$ROOT/initdb.log"
fi
if ! "$BIN/pg_isready" -q -h /tmp -p $PORT; then
  "$BIN/pg_ctl" -D "$DATA" -l "$ROOT/pg.log" -w \
    -o "-p $PORT -c listen_addresses=127.0.0.1 -c unix_socket_directories=/tmp -c shared_buffers=32MB -c max_connections=30 -c fsync=off" start > /dev/null
fi

if [ "$1" = "reset" ]; then
  stop_rest
  $PSQL -d postgres -c "drop database if exists mps"
  $PSQL -d postgres -c "drop role if exists authenticator; drop role if exists anon; drop role if exists authenticated; drop role if exists service_role" 2> /dev/null || true
fi
if ! $PSQL -d postgres -tAc "select 1 from pg_database where datname='mps'" | grep -q 1; then
  $PSQL -d postgres -c "create database mps"
  $PSQL -d mps -f "$HERE/stubs.sql"
fi
$PSQL -d mps -f "$SCHEMA" 2>&1 | grep -v "NOTICE" || true
echo "schema applied"
$PSQL -d mps -f "$HERE/tests.sql" 2>&1 | grep -E "FAILED|ERROR|PASSED"

stop_rest
cat > "$ROOT/postgrest.conf" <<CONF
db-uri = "postgres://authenticator:local@127.0.0.1:$PORT/mps"
db-schemas = "public"
db-anon-role = "anon"
jwt-secret = "$SECRET"
server-host = "127.0.0.1"
server-port = $REST_PORT
db-pool = 4
CONF
nohup "$REST" "$ROOT/postgrest.conf" > "$ROOT/postgrest.log" 2>&1 &
echo $! > "$ROOT/postgrest.pid"
for i in $(seq 1 40); do
  if curl -s -o /dev/null --max-time 2 "http://127.0.0.1:$REST_PORT/"; then echo "STACK-READY rest=http://127.0.0.1:$REST_PORT"; exit 0; fi
  sleep 0.25
done
echo "PostgREST did not start"; tail -5 "$ROOT/postgrest.log"; exit 1
