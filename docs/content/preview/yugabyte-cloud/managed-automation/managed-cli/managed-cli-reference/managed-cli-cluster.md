---
title: cluster resource
headerTitle: ybm cluster
linkTitle: cluster
description: YugabyteDB Managed CLI reference Cluster resource.
headcontent: Manage clusters
menu:
  preview_yugabyte-cloud:
    identifier: managed-cli-cluster
    parent: managed-cli-reference
    weight: 20
type: docs
---

Use the `cluster` resource to perform operations on a YugabyteDB Managed cluster, including the following:

- create, update, and delete clusters
- pause and resume clusters
- get information about clusters
- download the cluster certificate
- encrypt clusters and manage encryption

## Syntax

```text
Usage: ybm cluster [command] [flags]
```

## Examples

Create a local single-node cluster:

```sh
ybm cluster create \
  --cluster-name test-cluster \
  --credentials username=admin,password=password123
```

Create a multi-node cluster:

```sh
ybm cluster create \
  --cluster-name test-cluster \
  --credentials username=admin,password=password123 \
  --cloud-provider AWS \
  --node-config num-cores=2,disk-size-gb=500 \
  --region-info region=aws.us-east-2.us-east-2a,vpc=aws-us-east-2 \
  --region-info region=aws.us-east-2.us-east-2b,vpc=aws-us-east-2 \
  --region-info region=aws.us-east-2.us-east-2c,vpc=aws-us-east-2 \
  --fault-tolerance=zone
```

## Commands

### create

Create a cluster.

| Flag | Description |
| :--- | :--- |
| --cluster-name | Required. Name for the cluster. |
| --credentials | Required. Database credentials for the default user, provided as key-value pairs.<br>Arguments:<ul><li>username</li><li>password</li></ul> |
| --cloud-provider | Cloud provider. `AWS` (default) or `GCP`.
| --cluster-type | Deployment type. `SYNCHRONOUS` or `GEO_PARTITIONED`. |
| --node-config | Number of vCPUs and disk size per node for the cluster, provided as key-value pairs.<br>Arguments:<ul><li>num-cores - number of vCPUs per node</li><li>disk-size-gb - disk size in GB per node</li></ul>If specified, num-cores is required, disk-size-gb is optional. |
| --region-info | Region details for multi-region cluster, provided as key-value pairs.<br>Arguments:<ul><li>region-name - name of the region specified as cloud.region</li><li>num-nodes - number of nodes for the region</li><li>vpc - name of the VPC</li></ul>Specify one `--region-info` flag for each region in the cluster.<br>If specified, region and num-nodes is required, vpc is optional. |
| --cluster-tier | Type of cluster. `Sandbox` or `Dedicated`. |
| --fault-tolerance | Fault tolerance for the cluster. `NONE`, `ZONE`, or `REGION`. |
| --database-version | Database version to use for the cluster. `Stable` or `Preview`. |
| --encryption-spec | customer managed key (CMK) credentials for encryption at rest, provided as key-value pairs.<br>Arguments:<ul><li>cloud-provider - cloud provider (`AWS`); required</li><li>aws-access-key - access key ID (AWS only); required for AWS</li><li>aws-secret-key - secret access key (AWS only)</li></ul>If not provided, you are prompted for the secret access key.</br>Secret access key can also be configured using the YBM_AWS_SECRET_KEY [environment variable](../../managed-cli-overview/#environment-variables). |

### cert download

Download the [cluster certificate](../../../../cloud-secure-clusters/cloud-authentication/) to a specified location.

| Flag | Description |
| :--- | :--- |
| --force | Overwrite the output file if it exists. |
| --out | Full path with file name of the location to which to download the cluster certificate file. Default is `stdout`. |

### delete

Delete the specified cluster.

| Flag | Description |
| :--- | :--- |
| --cluster-name | Name of the cluster. |

### describe

Fetch detailed information about the specified cluster.

| Flag | Description |
| :--- | :--- |
| --cluster-name | Name of the cluster. |

### encryption list

List the encryption at rest configuration for the specified cluster.

| Flag | Description |
| :--- | :--- |
| --cluster-name | Required. The name of the cluster. |

### encryption update

Update the credentials to use for the customer managed key (CMK) used to encrypt the specified cluster.

| Flag | Description |
| :--- | :--- |
| --cluster-name | Required. Name of the cluster. |
| --encryption-spec | CMK credentials, provided as key-value pairs.<br>Arguments:<ul><li>cloud-provider - cloud provider (`AWS`); required</li><li>aws-access-key - access key ID (AWS only); required for AWS</li><li>aws-secret-key - secret access key (AWS only)</li></ul>If not provided, you are prompted for the secret access key.</br>Secret access key can also be configured using the YBM_AWS_SECRET_KEY [environment variable](../../managed-cli-overview/#environment-variables). |

### list

List all the clusters to which you have access.

| Flag | Description |
| :--- | :--- |
| --cluster-name | The name of the cluster to filter. |

### pause

Pause the specified cluster.

| Flag | Description |
| :--- | :--- |
| --cluster-name | Required. Name of the cluster to pause. |

### resume

Resume the specified cluster.

| Flag | Description |
| :--- | :--- |
| --cluster-name | Required. Name of the cluster to resume. |

### update

Update the specified cluster.

| Flag | Description |
| :--- | :--- |
| --cluster-name | Required. Name of the cluster to update. |
| --cloud-provider | Cloud provider. `AWS` or `GCP`. |
| --cluster-type | Deployment type. `SYNCHRONOUS` or `GEO_PARTITIONED`. |
| --node-config | Number of vCPUs and disk size per node for the cluster, provided as key-value pairs.<br>Arguments:<ul><li>num-cores - number of vCPUs per node</li><li>disk-size-gb - disk size in GB per node</li></ul>If specified, num-cores is required, disk-size-gb is optional. |
| --region-info | Region details for multi-region cluster, provided as key-value pairs.<br>Arguments:<ul><li>region-name - name of the region specified as cloud.region</li><li>num-nodes - number of nodes for the region</li><li>vpc - name of the VPC</li></ul>Specify one `--region-info` flag for each region in the cluster.<br>If specified, region and num-nodes is required, vpc is optional. |
| --cluster-tier | Type of cluster. `Sandbox` or `Dedicated`. |
| --fault-tolerance | Fault tolerance for the cluster. `NONE`, `ZONE`, or `REGION`. |
| --database-version | Database version to use for the cluster. `Stable` or `Preview`. |
