# Amoro local docker compose environment

This directory provides a local environment for running and debugging apache amoro with docker compose.

## Services

- `postgres`: metadata database for AMS.
- `amoro`: Amoro Management Service (dashboard + thrift endpoints).
- `optimizer-group-init`: one-shot init job that creates/updates `localContainer` optimizer group in metadata DB.
- `catalog-init`: one-shot init job that logs in to AMS and creates a minimal local catalog (`local_hadoop`) if missing.
- `catalog-init-s3` (optional, profile `s3`): one-shot init job that creates MinIO-backed S3 catalog (`local_s3`) and seeds demo data.
- `optimizer`: standalone optimizer process connected to AMS.
- `minio` + `minio-init` (optional, profile `s3`): object storage for S3-style catalog/storage debugging.

## Prerequisites

- Docker Engine 24+ with Docker Compose plugin.
- Build context is repository root (compose builds `docker/amoro/Dockerfile`).
- Java 17 and Maven 3.9+

## Start

From repository root:
This stack uses only the local image by default (`apache/amoro:local`), built from source in this repository:

```bash
mvn clean package -DskipTests
docker compose -f docker/compose/docker-compose.yml up -d --build
```

Start with optional S3 stack:

```bash
docker compose -f docker/compose/docker-compose.yml --profile s3 up -d --build
```

## Endpoints

- AMS Web UI: `http://localhost:1630`
- AMS table thrift: `localhost:1260`
- AMS optimizing thrift: `localhost:1261`
- PostgreSQL: `localhost:5432`
- MinIO API (optional): `http://localhost:9000`
- MinIO Console (optional): `http://localhost:9001`

Default credentials:

- AMS: `admin / admin`
- PostgreSQL user: `amoro / amoro`
- MinIO (optional): `minio / minio123`

Default auto-created catalog:

- Name: `local_hadoop`
- Type: `hadoop` (Filesystem)
- Table format: `MIXED_ICEBERG`
- Warehouse: `file:///var/lib/amoro/warehouse`

Additional auto-created catalog when profile `s3` is enabled:

- Name: `local_s3`
- Type: `ams` (Amoro Metastore)
- Storage type: `S3`
- Warehouse: `s3://amoro-warehouse`
- Endpoint: `http://minio:9000` (path-style access)

Default seeded objects:

- Database: `demo`
- Tables: `demo.users`, `demo.orders`
- Data commits: script tops up each table to `5` non-optimizing snapshots by default (`AMORO_SEED_SNAPSHOT_TARGET=5`).

## Useful Commands

Stop and remove containers with volumes:

```bash
docker compose -f docker/compose/docker-compose.yml down -v
```