---
title: "Paimon"
url: paimon-format
aliases:
    - "formats/paimon"
menu:
    main:
        parent: Formats
        weight: 200
---
<!--
 - Licensed to the Apache Software Foundation (ASF) under one or more
 - contributor license agreements.  See the NOTICE file distributed with
 - this work for additional information regarding copyright ownership.
 - The ASF licenses this file to You under the Apache License, Version 2.0
 - (the "License"); you may not use this file except in compliance with
 - the License.  You may obtain a copy of the License at
 -
 -   http://www.apache.org/licenses/LICENSE-2.0
 -
 - Unless required by applicable law or agreed to in writing, software
 - distributed under the License is distributed on an "AS IS" BASIS,
 - WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 - See the License for the specific language governing permissions and
 - limitations under the License.
 -->
# Paimon Format

Paimon format refers to [Apache Paimon](https://paimon.apache.org/) table.
Paimon is a streaming data lake platform with high-speed data ingestion, changelog tracking and efficient real-time analytics.

By registering Paimon's catalog with Amoro, users can view information such as Schema, Options, Files, Snapshots, DDLs, Compaction information, and more for Paimon tables.
Furthermore, they can operate on Paimon tables using Spark SQL in the Terminal. The current supported catalog types and file system types for Paimon are all supported.

For registering catalog operation steps, please refer to [Managing Catalogs](../managing-catalogs/).

## Maintenance

AMS can schedule Paimon maintenance when the process is built with `-Psupport-paimon-format` or `-Psupport-all-formats`. Spark optimizer images need the same profile so partial and full compaction can run on optimizer workers. Snapshot expiration, partition expiration, and orphan cleanup run inside AMS.

Each action is off until the table option is set. `gc.enabled=false` turns off the three cleanup actions. Compaction file selection and snapshot or partition retention stay on native Paimon options (`compaction.min.file-num`, `snapshot.num-retained.max`, `snapshot.time-retained`, `partition.expiration-time`). A manual button on the table page skips the interval and still requires the action to be enabled. Optimizer group and quota use `self-optimizing.group` and `self-optimizing.quota`.

| Table option | Default | Action |
| --- | --- | --- |
| `maintenance.partial-compact.enabled` | false | Partial compaction (Paimon minor strategy) |
| `maintenance.partial-compact.interval` | plugin default `1h` | Optional per-table interval |
| `maintenance.full-compact.enabled` | false | Full compaction |
| `maintenance.full-compact.interval` | plugin default `1d` | Optional per-table interval |
| `maintenance.clean-orphan-files.enabled` | false | Orphan file cleanup |
| `maintenance.clean-orphan-files.min-existing-time` | `2d` | Minimum age of orphan files |
| `maintenance.expire-snapshots.enabled` | false | Snapshot expiration |
| `maintenance.expire-partitions.enabled` | false | Partition expiration |
| `gc.enabled` | true | Master switch for the three cleanup actions |

Supported compaction bucket modes are fixed hash buckets and unaware buckets. Dynamic bucket, postpone bucket, sort compaction, and data-evolution compaction fail the process with an explicit message. Set options with `ALTER TABLE ... SET`.

## Maintenance

AMS can schedule Paimon maintenance when the process is built with `-Psupport-paimon-format` or `-Psupport-all-formats`. Spark optimizer images need the same profile so partial and full compaction can run on optimizer workers. Snapshot expiration, partition expiration, and orphan cleanup run inside AMS.

Each action is off until the table option is set. `gc.enabled=false` turns off the three cleanup actions. Compaction file selection and snapshot or partition retention stay on native Paimon options (`compaction.min.file-num`, `snapshot.num-retained.max`, `snapshot.time-retained`, `partition.expiration-time`). A manual button on the table page skips the interval and still requires the action to be enabled. Optimizer group and quota use `self-optimizing.group` and `self-optimizing.quota`.

| Table option | Default | Action |
| --- | --- | --- |
| `maintenance.partial-compact.enabled` | false | Partial compaction (Paimon minor strategy) |
| `maintenance.partial-compact.interval` | plugin default `1h` | Optional per-table interval |
| `maintenance.full-compact.enabled` | false | Full compaction |
| `maintenance.full-compact.interval` | plugin default `1d` | Optional per-table interval |
| `maintenance.clean-orphan-files.enabled` | false | Orphan file cleanup |
| `maintenance.clean-orphan-files.min-existing-time` | `2d` | Minimum age of orphan files |
| `maintenance.expire-snapshots.enabled` | false | Snapshot expiration |
| `maintenance.expire-partitions.enabled` | false | Partition expiration |
| `gc.enabled` | true | Master switch for the three cleanup actions |

Supported compaction bucket modes are fixed hash buckets and unaware buckets. Dynamic bucket, postpone bucket, sort compaction, and data-evolution compaction fail the process with an explicit message. Set options with `ALTER TABLE ... SET`.

{{< hint info >}}
If you want to use S3 or OSS, please download the 
[S3](https://repo.maven.apache.org/maven2/org/apache/paimon/paimon-s3/0.5.0-incubating/paimon-s3-0.5.0-incubating.jar), 
[OSS](https://repo.maven.apache.org/maven2/org/apache/paimon/paimon-oss/0.5.0-incubating/paimon-oss-0.5.0-incubating.jar) 
package and put it in the 'lib' directory of the Amoro installation package.
{{< /hint >}}
