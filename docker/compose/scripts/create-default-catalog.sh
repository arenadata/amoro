#!/bin/sh

set -eu

AMS_API_BASE_URL="${AMS_API_BASE_URL:-http://amoro:1630/api/ams/v1}"
ADMIN_USER="${AMORO_ADMIN_USER:-admin}"
ADMIN_PASSWORD="${AMORO_ADMIN_PASSWORD:-admin}"
CATALOG_NAME="${AMORO_DEFAULT_CATALOG_NAME:-local_hadoop}"
CATALOG_TYPE="${AMORO_DEFAULT_CATALOG_TYPE:-hadoop}"
OPTIMIZER_GROUP="${AMORO_DEFAULT_OPTIMIZER_GROUP:-localContainer}"
WAREHOUSE="${AMORO_DEFAULT_CATALOG_WAREHOUSE:-file:///var/lib/amoro/warehouse}"
TABLE_FORMAT="${AMORO_DEFAULT_TABLE_FORMAT:-MIXED_ICEBERG}"
STORAGE_TYPE="${AMORO_DEFAULT_STORAGE_TYPE:-Hadoop}"
STORAGE_S3_REGION="${AMORO_DEFAULT_S3_REGION:-us-east-1}"
STORAGE_S3_ENDPOINT="${AMORO_DEFAULT_S3_ENDPOINT:-}"
S3_PATH_STYLE_ACCESS="${AMORO_DEFAULT_S3_PATH_STYLE_ACCESS:-true}"
AUTH_TYPE="${AMORO_DEFAULT_AUTH_TYPE:-SIMPLE}"
AUTH_SIMPLE_HADOOP_USERNAME="${AMORO_DEFAULT_SIMPLE_HADOOP_USERNAME:-${ADMIN_USER}}"
AUTH_AK_ACCESS_KEY="${AMORO_DEFAULT_AK_ACCESS_KEY:-}"
AUTH_AK_SECRET_KEY="${AMORO_DEFAULT_AK_SECRET_KEY:-}"
SEED_ENABLED="${AMORO_SEED_ENABLED:-true}"
SEED_DATABASE="${AMORO_SEED_DATABASE:-demo}"
SEED_TABLE_ONE="${AMORO_SEED_TABLE_ONE:-users}"
SEED_TABLE_TWO="${AMORO_SEED_TABLE_TWO:-orders}"
SEED_SNAPSHOT_TARGET="${AMORO_SEED_SNAPSHOT_TARGET:-5}"

HEALTHCHECK_MAX_ATTEMPTS="${AMS_HEALTHCHECK_MAX_ATTEMPTS:-60}"
HEALTHCHECK_SLEEP_SECONDS="${AMS_HEALTHCHECK_SLEEP_SECONDS:-2}"
TERMINAL_POLL_MAX_ATTEMPTS="${AMORO_TERMINAL_POLL_MAX_ATTEMPTS:-120}"
TERMINAL_POLL_SLEEP_SECONDS="${AMORO_TERMINAL_POLL_SLEEP_SECONDS:-1}"

TMP_DIR="$(mktemp -d)"
COOKIE_JAR="${TMP_DIR}/cookies.txt"
trap 'rm -rf "${TMP_DIR}"' EXIT

wait_for_ams() {
  attempt=1
  while [ "${attempt}" -le "${HEALTHCHECK_MAX_ATTEMPTS}" ]; do
    status="$(curl -sS -o /dev/null -w "%{http_code}" "${AMS_API_BASE_URL}/health/status" || true)"
    if [ "${status}" = "200" ]; then
      echo "AMS health check passed."
      return 0
    fi

    echo "Waiting for AMS (${attempt}/${HEALTHCHECK_MAX_ATTEMPTS}), last status=${status}"
    attempt=$((attempt + 1))
    sleep "${HEALTHCHECK_SLEEP_SECONDS}"
  done

  echo "AMS health check timeout."
  return 1
}

login() {
  response_file="${TMP_DIR}/login.json"
  status="$(
    curl -sS -o "${response_file}" -w "%{http_code}" \
      -H "Content-Type: application/json" \
      -H "X-Request-Source: Web" \
      -c "${COOKIE_JAR}" \
      -X POST \
      "${AMS_API_BASE_URL}/login" \
      -d "{\"user\":\"${ADMIN_USER}\",\"password\":\"${ADMIN_PASSWORD}\"}"
  )"

  if [ "${status}" != "200" ]; then
    echo "Failed to login, status=${status}"
    cat "${response_file}"
    return 1
  fi

  if ! grep -Eq '"code"[[:space:]]*:[[:space:]]*200' "${response_file}"; then
    echo "Unexpected login response:"
    cat "${response_file}"
    return 1
  fi
}

catalog_exists() {
  response_file="${TMP_DIR}/catalogs.json"
  status="$(
    curl -sS -o "${response_file}" -w "%{http_code}" \
      -H "X-Request-Source: Web" \
      -b "${COOKIE_JAR}" \
      "${AMS_API_BASE_URL}/catalogs"
  )"

  if [ "${status}" != "200" ]; then
    echo "Failed to list catalogs, status=${status}"
    cat "${response_file}"
    return 1
  fi

  grep -Eq "\"catalogName\"[[:space:]]*:[[:space:]]*\"${CATALOG_NAME}\"" "${response_file}"
}

normalize_storage_type() {
  storage_type_normalized="$(printf '%s' "${STORAGE_TYPE}" | tr '[:upper:]' '[:lower:]')"
  case "${storage_type_normalized}" in
    s3)
      echo "S3"
      ;;
    hadoop|hdfs)
      echo "Hadoop"
      ;;
    *)
      echo "Unsupported storage type: ${STORAGE_TYPE}" >&2
      return 1
      ;;
  esac
}

normalize_auth_type() {
  auth_type_normalized="$(printf '%s' "${AUTH_TYPE}" | tr '[:upper:]' '[:lower:]')"
  case "${auth_type_normalized}" in
    simple)
      echo "SIMPLE"
      ;;
    ak/sk|ak_sk|aksk)
      echo "AK/SK"
      ;;
    *)
      echo "Unsupported auth type: ${AUTH_TYPE}" >&2
      return 1
      ;;
  esac
}

validate_catalog_bootstrap_config() {
  storage_type="$1"
  auth_type="$2"

  if [ -z "${CATALOG_NAME}" ] || [ -z "${CATALOG_TYPE}" ]; then
    echo "Catalog name/type must be set."
    return 1
  fi

  if [ "${storage_type}" = "S3" ]; then
    if [ -z "${STORAGE_S3_ENDPOINT}" ] || [ -z "${STORAGE_S3_REGION}" ]; then
      echo "S3 storage requires AMORO_DEFAULT_S3_ENDPOINT and AMORO_DEFAULT_S3_REGION."
      return 1
    fi
  fi

  if [ "${auth_type}" = "SIMPLE" ] && [ -z "${AUTH_SIMPLE_HADOOP_USERNAME}" ]; then
    echo "SIMPLE auth requires AMORO_DEFAULT_SIMPLE_HADOOP_USERNAME."
    return 1
  fi

  if [ "${auth_type}" = "AK/SK" ]; then
    if [ -z "${AUTH_AK_ACCESS_KEY}" ] || [ -z "${AUTH_AK_SECRET_KEY}" ]; then
      echo "AK/SK auth requires AMORO_DEFAULT_AK_ACCESS_KEY and AMORO_DEFAULT_AK_SECRET_KEY."
      return 1
    fi
  fi
}

create_catalog() {
  payload_file="${TMP_DIR}/catalog-create.json"
  response_file="${TMP_DIR}/catalog-create-response.json"
  catalog_type_normalized="$(printf '%s' "${CATALOG_TYPE}" | tr '[:upper:]' '[:lower:]')"
  storage_type="$(normalize_storage_type)"
  auth_type="$(normalize_auth_type)"

  validate_catalog_bootstrap_config "${storage_type}" "${auth_type}"

  if [ "${storage_type}" = "S3" ] && [ "${auth_type}" = "AK/SK" ]; then
    cat > "${payload_file}" <<EOF
{
  "name": "${CATALOG_NAME}",
  "type": "${catalog_type_normalized}",
  "optimizerGroup": "${OPTIMIZER_GROUP}",
  "tableFormatList": [
    "${TABLE_FORMAT}"
  ],
  "storageConfig": {
    "storage.type": "S3",
    "storage.s3.region": "${STORAGE_S3_REGION}",
    "storage.s3.endpoint": "${STORAGE_S3_ENDPOINT}"
  },
  "authConfig": {
    "auth.type": "AK/SK",
    "auth.ak_sk.access_key": "${AUTH_AK_ACCESS_KEY}",
    "auth.ak_sk.secret_key": "${AUTH_AK_SECRET_KEY}"
  },
  "properties": {
    "warehouse": "${WAREHOUSE}",
    "s3.path-style-access": "${S3_PATH_STYLE_ACCESS}"
  },
  "tableProperties": {}
}
EOF
  elif [ "${storage_type}" = "S3" ]; then
    cat > "${payload_file}" <<EOF
{
  "name": "${CATALOG_NAME}",
  "type": "${catalog_type_normalized}",
  "optimizerGroup": "${OPTIMIZER_GROUP}",
  "tableFormatList": [
    "${TABLE_FORMAT}"
  ],
  "storageConfig": {
    "storage.type": "S3",
    "storage.s3.region": "${STORAGE_S3_REGION}",
    "storage.s3.endpoint": "${STORAGE_S3_ENDPOINT}"
  },
  "authConfig": {
    "auth.type": "SIMPLE",
    "auth.simple.hadoop_username": "${AUTH_SIMPLE_HADOOP_USERNAME}"
  },
  "properties": {
    "warehouse": "${WAREHOUSE}",
    "s3.path-style-access": "${S3_PATH_STYLE_ACCESS}"
  },
  "tableProperties": {}
}
EOF
  elif [ "${auth_type}" = "AK/SK" ]; then
    cat > "${payload_file}" <<EOF
{
  "name": "${CATALOG_NAME}",
  "type": "${catalog_type_normalized}",
  "optimizerGroup": "${OPTIMIZER_GROUP}",
  "tableFormatList": [
    "${TABLE_FORMAT}"
  ],
  "storageConfig": {
    "storage.type": "Hadoop",
    "hadoop.core.site": "",
    "hadoop.hdfs.site": ""
  },
  "authConfig": {
    "auth.type": "AK/SK",
    "auth.ak_sk.access_key": "${AUTH_AK_ACCESS_KEY}",
    "auth.ak_sk.secret_key": "${AUTH_AK_SECRET_KEY}"
  },
  "properties": {
    "warehouse": "${WAREHOUSE}"
  },
  "tableProperties": {}
}
EOF
  else
    cat > "${payload_file}" <<EOF
{
  "name": "${CATALOG_NAME}",
  "type": "${catalog_type_normalized}",
  "optimizerGroup": "${OPTIMIZER_GROUP}",
  "tableFormatList": [
    "${TABLE_FORMAT}"
  ],
  "storageConfig": {
    "storage.type": "Hadoop",
    "hadoop.core.site": "",
    "hadoop.hdfs.site": ""
  },
  "authConfig": {
    "auth.type": "SIMPLE",
    "auth.simple.hadoop_username": "${AUTH_SIMPLE_HADOOP_USERNAME}"
  },
  "properties": {
    "warehouse": "${WAREHOUSE}"
  },
  "tableProperties": {}
}
EOF
  fi

  echo "Create catalog request: name=${CATALOG_NAME}, type=${catalog_type_normalized}, storage=${storage_type}, auth=${auth_type}."

  status="$(
    curl -sS -o "${response_file}" -w "%{http_code}" \
      -H "Content-Type: application/json" \
      -H "X-Request-Source: Web" \
      -b "${COOKIE_JAR}" \
      -X POST \
      "${AMS_API_BASE_URL}/catalogs" \
      --data @"${payload_file}"
  )"

  if [ "${status}" = "200" ] && grep -Eq '"code"[[:space:]]*:[[:space:]]*200' "${response_file}"; then
    echo "Catalog ${CATALOG_NAME} created."
    return 0
  fi

  if grep -Eq "Duplicate catalog name" "${response_file}"; then
    echo "Catalog ${CATALOG_NAME} already exists."
    return 0
  fi

  echo "Failed to create catalog, status=${status}"
  cat "${response_file}"
  return 1
}

json_escape() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

execute_terminal_sql() {
  sql="$1"
  response_file="${TMP_DIR}/terminal-execute.json"
  log_file="${TMP_DIR}/terminal-logs.json"
  result_file="${TMP_DIR}/terminal-result.json"
  escaped_sql="$(json_escape "${sql}")"

  status="$(
    curl -sS -o "${response_file}" -w "%{http_code}" \
      -H "Content-Type: application/json" \
      -H "X-Request-Source: Web" \
      -b "${COOKIE_JAR}" \
      -X POST \
      "${AMS_API_BASE_URL}/terminal/catalogs/${CATALOG_NAME}/execute" \
      -d "{\"sql\":\"${escaped_sql}\"}"
  )"

  if [ "${status}" != "200" ] || ! grep -Eq '"code"[[:space:]]*:[[:space:]]*200' "${response_file}"; then
    echo "Failed to submit terminal sql, status=${status}, sql=${sql}"
    cat "${response_file}"
    return 1
  fi

  session_id="$(
    sed -n 's/.*"sessionId":"\([^"]*\)".*/\1/p' "${response_file}" | head -n 1
  )"
  if [ -z "${session_id}" ]; then
    echo "Cannot extract terminal session id for sql: ${sql}"
    cat "${response_file}"
    return 1
  fi

  attempt=1
  while [ "${attempt}" -le "${TERMINAL_POLL_MAX_ATTEMPTS}" ]; do
    log_status_http="$(
      curl -sS -o "${log_file}" -w "%{http_code}" \
        -H "X-Request-Source: Web" \
        -b "${COOKIE_JAR}" \
        "${AMS_API_BASE_URL}/terminal/${session_id}/logs"
    )"

    if [ "${log_status_http}" != "200" ]; then
      echo "Failed to poll terminal logs, status=${log_status_http}, sql=${sql}"
      cat "${log_file}"
      return 1
    fi

    log_status="$(
      sed -n 's/.*"logStatus":"\([^"]*\)".*/\1/p' "${log_file}" | head -n 1
    )"

    if [ "${log_status}" = "Finished" ]; then
      result_status_http="$(
        curl -sS -o "${result_file}" -w "%{http_code}" \
          -H "X-Request-Source: Web" \
          -b "${COOKIE_JAR}" \
          "${AMS_API_BASE_URL}/terminal/${session_id}/result"
      )"
      if [ "${result_status_http}" != "200" ]; then
        echo "Failed to fetch terminal result, status=${result_status_http}, sql=${sql}"
        cat "${result_file}" || true
        return 1
      fi
      return 0
    fi

    if [ "${log_status}" = "Failed" ] || [ "${log_status}" = "Canceled" ] || [ "${log_status}" = "Expired" ]; then
      echo "Terminal sql failed with status=${log_status}, sql=${sql}"
      cat "${log_file}"
      curl -sS -o "${result_file}" \
        -H "X-Request-Source: Web" \
        -b "${COOKIE_JAR}" \
        "${AMS_API_BASE_URL}/terminal/${session_id}/result" || true
      if [ -s "${result_file}" ]; then
        cat "${result_file}"
      fi
      return 1
    fi

    attempt=$((attempt + 1))
    sleep "${TERMINAL_POLL_SLEEP_SECONDS}"
  done

  echo "Terminal sql timeout, sql=${sql}"
  cat "${log_file}" || true
  return 1
}

extract_first_result_cell() {
  tr -d '\n' < "${TMP_DIR}/terminal-result.json" \
    | sed -n 's/.*"rowData":\[\[\([^]]*\)\]\].*/\1/p' \
    | sed 's/^"//; s/"$//'
}

query_table_row_count() {
  table_name="$1"
  execute_terminal_sql "select count(*) as c from ${SEED_DATABASE}.${table_name}"
  row_count="$(extract_first_result_cell)"
  case "${row_count}" in
    ''|*[!0-9]*)
      echo "Invalid row count ${row_count} for ${SEED_DATABASE}.${table_name}"
      return 1
      ;;
  esac
  echo "${row_count}"
}

query_table_snapshot_count() {
  table_name="$1"
  operation="${2:-all}"
  response_file="${TMP_DIR}/table-snapshots-${table_name}.json"
  status="$(
    curl -sS -o "${response_file}" -w "%{http_code}" \
      -H "X-Request-Source: Web" \
      -b "${COOKIE_JAR}" \
      "${AMS_API_BASE_URL}/tables/catalogs/${CATALOG_NAME}/dbs/${SEED_DATABASE}/tables/${table_name}/snapshots?page=1&pageSize=1&operation=${operation}"
  )"

  if [ "${status}" != "200" ]; then
    echo "Failed to get snapshot list of ${SEED_DATABASE}.${table_name}, status=${status}"
    cat "${response_file}"
    return 1
  fi

  if ! grep -Eq '"code"[[:space:]]*:[[:space:]]*200' "${response_file}"; then
    echo "Unexpected snapshot response for ${SEED_DATABASE}.${table_name}:"
    cat "${response_file}"
    return 1
  fi

  snapshot_count="$(
    tr -d '\n' < "${response_file}" \
      | sed -n 's/.*"total":\([0-9][0-9]*\).*/\1/p'
  )"
  case "${snapshot_count}" in
    ''|*[!0-9]*)
      echo "Invalid snapshot count ${snapshot_count} for ${SEED_DATABASE}.${table_name}"
      return 1
      ;;
  esac
  echo "${snapshot_count}"
}

seed_rows_for_users() {
  start_id="$1"
  end_id="$2"
  current_id="${start_id}"
  while [ "${current_id}" -le "${end_id}" ]; do
    execute_terminal_sql \
      "insert into ${SEED_DATABASE}.${SEED_TABLE_ONE} values (${current_id}, 'user_${current_id}', current_timestamp())"
    current_id=$((current_id + 1))
  done
}

seed_rows_for_orders() {
  start_id="$1"
  end_id="$2"
  current_id="${start_id}"
  while [ "${current_id}" -le "${end_id}" ]; do
    execute_terminal_sql \
      "insert into ${SEED_DATABASE}.${SEED_TABLE_TWO} values (${current_id}, ${current_id}, cast(${current_id} as decimal(18,2)), current_timestamp())"
    current_id=$((current_id + 1))
  done
}

seed_table_snapshots() {
  table_name="$1"
  current_snapshots="$(query_table_snapshot_count "${table_name}" "non-optimizing")"
  if [ "${current_snapshots}" -ge "${SEED_SNAPSHOT_TARGET}" ]; then
    echo "Table ${SEED_DATABASE}.${table_name} already has ${current_snapshots} non-optimizing snapshots, skip data seed."
    return 0
  fi

  current_rows="$(query_table_row_count "${table_name}")"
  snapshots_to_add=$((SEED_SNAPSHOT_TARGET - current_snapshots))
  start_id=$((current_rows + 1))
  end_id=$((current_rows + snapshots_to_add))

  if [ "${table_name}" = "${SEED_TABLE_ONE}" ]; then
    seed_rows_for_users "${start_id}" "${end_id}"
  else
    seed_rows_for_orders "${start_id}" "${end_id}"
  fi

  final_rows="$(query_table_row_count "${table_name}")"
  final_data_snapshots="$(query_table_snapshot_count "${table_name}" "non-optimizing")"
  final_all_snapshots="$(query_table_snapshot_count "${table_name}" "all")"
  echo "Table ${SEED_DATABASE}.${table_name} after seed: rows=${final_rows}, non-optimizing snapshots=${final_data_snapshots}, all snapshots=${final_all_snapshots}"
}

seed_minimal_objects() {
  seed_enabled_normalized="$(printf '%s' "${SEED_ENABLED}" | tr '[:upper:]' '[:lower:]')"
  if [ "${seed_enabled_normalized}" != "true" ]; then
    echo "Catalog seed is disabled."
    return 0
  fi

  case "${SEED_SNAPSHOT_TARGET}" in
    ''|*[!0-9]*|0)
      echo "AMORO_SEED_SNAPSHOT_TARGET must be a positive integer, got: ${SEED_SNAPSHOT_TARGET}"
      return 1
      ;;
  esac

  execute_terminal_sql "create database if not exists ${SEED_DATABASE}"
  execute_terminal_sql \
    "create table if not exists ${SEED_DATABASE}.${SEED_TABLE_ONE} (id bigint, name string, created_at timestamp) using iceberg"
  execute_terminal_sql \
    "create table if not exists ${SEED_DATABASE}.${SEED_TABLE_TWO} (order_id bigint, user_id bigint, amount decimal(18,2), created_at timestamp) using iceberg"
  seed_table_snapshots "${SEED_TABLE_ONE}"
  seed_table_snapshots "${SEED_TABLE_TWO}"
  execute_terminal_sql "show tables in ${SEED_DATABASE}"
  echo "Seeded database ${SEED_DATABASE} and tables ${SEED_TABLE_ONE}, ${SEED_TABLE_TWO} with target snapshots ${SEED_SNAPSHOT_TARGET}."
}

wait_for_ams
login

if catalog_exists; then
  echo "Catalog ${CATALOG_NAME} already exists, skipping catalog creation."
else
  create_catalog

  if ! catalog_exists; then
    echo "Catalog ${CATALOG_NAME} is still missing after create request."
    exit 1
  fi
fi

seed_minimal_objects

echo "Catalog bootstrap completed."
