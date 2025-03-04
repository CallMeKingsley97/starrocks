// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Inc.

package com.starrocks.external.hive;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.starrocks.external.PartitionUtil.toPartitionValues;

public class HivePartitionName {
    private final String databaseName;
    private final String tableName;
    private final List<String> partitionValues;

    // partition name eg: "year=2020/month=10/day=10"
    private final Optional<String> partitionNames;

    public HivePartitionName(String dbName, String tableName, List<String> partitionValues) {
        this(dbName, tableName, partitionValues, Optional.empty());
    }

    public HivePartitionName(String databaseName,
                             String tableName,
                             List<String> partitionValues,
                             Optional<String> partitionNames) {
        this.databaseName = databaseName;
        this.tableName = tableName;
        this.partitionValues = partitionValues;
        this.partitionNames = partitionNames;
    }

    public static HivePartitionName of(String dbName, String tblName, List<String> partitionValues) {
        return new HivePartitionName(dbName, tblName, partitionValues);
    }

    public static HivePartitionName of(String dbName, String tblName, String partitionNames) {
        return new HivePartitionName(dbName, tblName, toPartitionValues(partitionNames), Optional.of(partitionNames));
    }

    public String getTableName() {
        return tableName;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public List<String> getPartitionValues() {
        return partitionValues;
    }

    public Optional<String> getPartitionNames() {
        return partitionNames;
    }

    public boolean approximateMatchTable(String db, String tblName) {
        return this.databaseName.equals(db) && this.tableName.equals(tblName);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        HivePartitionName other = (HivePartitionName) o;
        return Objects.equals(databaseName, other.databaseName) &&
                Objects.equals(tableName, other.tableName) &&
                Objects.equals(partitionValues, other.partitionValues);
    }

    @Override
    public int hashCode() {
        return Objects.hash(databaseName, tableName, partitionValues);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("HivePartitionName{");
        sb.append("databaseName='").append(databaseName).append('\'');
        sb.append(", tableName='").append(tableName).append('\'');
        sb.append(", partitionValues=").append(partitionValues);
        sb.append(", partitionNames=").append(partitionNames);
        sb.append('}');
        return sb.toString();
    }
}