// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Inc.

package com.starrocks.external.hive;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.HiveTable;
import com.starrocks.catalog.HudiTable;
import com.starrocks.catalog.Type;
import com.starrocks.common.IdGenerator;
import com.starrocks.connector.ConnectorTableId;
import com.starrocks.connector.exception.StarRocksConnectorException;
import com.starrocks.external.ColumnTypeConverter;
import com.starrocks.external.hive.text.TextFileFormatDesc;
import org.apache.avro.Schema;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.metastore.api.ColumnStatisticsObj;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfo;
import org.apache.hudi.avro.HoodieAvroUtils;
import org.apache.hudi.common.table.HoodieTableConfig;
import org.apache.hudi.common.table.HoodieTableMetaClient;
import org.apache.hudi.common.table.TableSchemaResolver;
import org.apache.hudi.common.util.Option;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.starrocks.catalog.HudiTable.HUDI_BASE_PATH;
import static com.starrocks.catalog.HudiTable.HUDI_TABLE_COLUMN_NAMES;
import static com.starrocks.catalog.HudiTable.HUDI_TABLE_COLUMN_TYPES;
import static com.starrocks.catalog.HudiTable.HUDI_TABLE_INPUT_FOAMT;
import static com.starrocks.catalog.HudiTable.HUDI_TABLE_SERDE_LIB;
import static com.starrocks.catalog.HudiTable.HUDI_TABLE_TYPE;
import static com.starrocks.external.ColumnTypeConverter.fromHudiType;
import static com.starrocks.server.CatalogMgr.ResourceMappingCatalog.toResourceName;
import static java.util.Objects.requireNonNull;
import static org.apache.hadoop.hive.common.StatsSetupConst.ROW_COUNT;
import static org.apache.hadoop.hive.common.StatsSetupConst.TOTAL_SIZE;
import static org.apache.hadoop.hive.serde2.typeinfo.TypeInfoUtils.getTypeInfoFromTypeString;

public class HiveMetastoreApiConverter {

    public static final IdGenerator<ConnectorTableId> CONNECTOR_ID_GENERATOR = ConnectorTableId.createGenerator();

    public static Database toDatabase(org.apache.hadoop.hive.metastore.api.Database database) {
        if (database == null || database.getName() == null) {
            throw new StarRocksConnectorException("Hive database [%s] doesn't exist");
        }
        return new Database(CONNECTOR_ID_GENERATOR.getNextId().asInt(), database.getName());
    }

    public static HiveTable toHiveTable(Table table, String catalogName) {
        validateHiveTableType(table.getTableType());

        HiveTable.Builder tableBuilder = HiveTable.builder()
                .setId(CONNECTOR_ID_GENERATOR.getNextId().asInt())
                .setTableName(table.getTableName())
                .setCatalogName(catalogName)
                .setResourceName(toResourceName(catalogName, "hive"))
                .setHiveDbName(table.getDbName())
                .setHiveTableName(table.getTableName())
                .setPartitionColumnNames(table.getPartitionKeys().stream()
                        .map(FieldSchema::getName)
                        .collect(Collectors.toList()))
                .setDataColumnNames(table.getSd().getCols().stream()
                        .map(FieldSchema::getName)
                        .collect(Collectors.toList()))
                .setFullSchema(toFullSchemasForHiveTable(table))
                .setTableLocation(table.getSd().getLocation())
                .setCreateTime(table.getCreateTime());
        return tableBuilder.build();
    }

    public static HudiTable toHudiTable(Table table, String catalogName) {
        String hudiBasePath = table.getSd().getLocation();
        Configuration conf = new Configuration();
        HoodieTableMetaClient metaClient =
                HoodieTableMetaClient.builder().setConf(conf).setBasePath(hudiBasePath).build();
        HoodieTableConfig hudiTableConfig = metaClient.getTableConfig();
        TableSchemaResolver schemaUtil = new TableSchemaResolver(metaClient);
        Schema hudiSchema;
        try {
            hudiSchema = HoodieAvroUtils.createHoodieWriteSchema(schemaUtil.getTableAvroSchema());
        } catch (Exception e) {
            throw new StarRocksConnectorException("Cannot get hudi table schema.");
        }

        List<String> partitionColumnNames = toPartitionColumnNamesForHudiTable(table, hudiTableConfig);

        HudiTable.Builder tableBuilder = HudiTable.builder()
                .setId(CONNECTOR_ID_GENERATOR.getNextId().asInt())
                .setTableName(table.getTableName())
                .setCatalogName(catalogName)
                .setResourceName(toResourceName(catalogName, "hudi"))
                .setHiveDbName(table.getDbName())
                .setHiveTableName(table.getTableName())
                .setFullSchema(toFullSchemasForHudiTable(hudiSchema))
                .setPartitionColNames(partitionColumnNames)
                .setDataColNames(toDataColumnNamesForHudiTable(hudiSchema, partitionColumnNames))
                .setHudiProperties(toHudiProperties(table, metaClient, hudiSchema))
                .setCreateTime(table.getCreateTime());

        return tableBuilder.build();
    }

    public static Partition toPartition(StorageDescriptor sd, Map<String, String> params) {
        requireNonNull(sd, "StorageDescriptor is null");
        Partition.Builder partitionBuilder = Partition.builder()
                .setParams(params)
                .setFullPath(sd.getLocation())
                .setInputFormat(toRemoteFileInputFormat(sd.getInputFormat()))
                .setTextFileFormatDesc(toTextFileFormatDesc(sd.getSerdeInfo().getParameters()))
                .setSplittable(RemoteFileInputFormat.isSplittable(sd.getInputFormat()));

        return partitionBuilder.build();
    }

    public static List<Column> toFullSchemasForHiveTable(Table table) {
        List<FieldSchema> fieldSchemas = getAllFieldSchemas(table);
        List<Column> fullSchema = Lists.newArrayList();
        for (FieldSchema fieldSchema : fieldSchemas) {
            Type type = ColumnTypeConverter.fromHiveType(fieldSchema.getType());
            Column column = new Column(fieldSchema.getName(), type, true);
            fullSchema.add(column);
        }
        return fullSchema;
    }

    public static List<Column> toFullSchemasForHudiTable(Schema hudiSchema) {
        List<Schema.Field> allHudiColumns = hudiSchema.getFields();
        List<Column> fullSchema = Lists.newArrayList();
        for (Schema.Field fieldSchema : allHudiColumns) {
            Type srType = fromHudiType(fieldSchema.schema());
            Column column = new Column(fieldSchema.name(), srType, true);
            fullSchema.add(column);
        }
        return fullSchema;
    }

    public static List<FieldSchema> getAllFieldSchemas(Table table) {
        ImmutableList.Builder<FieldSchema> allColumns =  ImmutableList.builder();
        List<FieldSchema> unHivePartColumns = table.getSd().getCols();
        List<FieldSchema> partHiveColumns = table.getPartitionKeys();
        return allColumns.addAll(unHivePartColumns).addAll(partHiveColumns).build();
    }

    public static List<String> toPartitionColumnNamesForHudiTable(Table table, HoodieTableConfig tableConfig) {
        List<String> partitionColumnNames = Lists.newArrayList();
        Option<String[]> hudiPartitionFields = tableConfig.getPartitionFields();

        if (hudiPartitionFields.isPresent()) {
            partitionColumnNames.addAll(Arrays.asList(hudiPartitionFields.get()));
        } else if (!table.getPartitionKeys().isEmpty()) {
            for (FieldSchema fieldSchema : table.getPartitionKeys()) {
                String partField = fieldSchema.getName();
                partitionColumnNames.add(partField);
            }
        }
        return partitionColumnNames;
    }

    public static List<String> toDataColumnNamesForHudiTable(Schema schema, List<String> partitionColumnNames) {
        List<String> dataColumnNames = Lists.newArrayList();
        for (Schema.Field hudiField : schema.getFields()) {
            if (partitionColumnNames.stream().noneMatch(p -> p.equals(hudiField.name()))) {
                dataColumnNames.add(hudiField.name());
            }
        }
        return dataColumnNames;
    }

    public static Map<String, String> toHudiProperties(Table metastoreTable,
                                                       HoodieTableMetaClient metaClient,
                                                       Schema tableSchema) {
        Map<String, String> hudiProperties = Maps.newHashMap();

        String hudiBasePath = metastoreTable.getSd().getLocation();
        if (!Strings.isNullOrEmpty(hudiBasePath)) {
            hudiProperties.put(HUDI_BASE_PATH, hudiBasePath);
        }
        String serdeLib = metastoreTable.getSd().getSerdeInfo().getSerializationLib();
        if (!Strings.isNullOrEmpty(serdeLib)) {
            hudiProperties.put(HUDI_TABLE_SERDE_LIB, serdeLib);
        }
        String inputFormat = metastoreTable.getSd().getInputFormat();
        if (!Strings.isNullOrEmpty(inputFormat)) {
            hudiProperties.put(HUDI_TABLE_INPUT_FOAMT, inputFormat);
        }

        hudiProperties.put(HUDI_TABLE_TYPE, metaClient.getTableType().name());

        StringBuilder columnNamesBuilder = new StringBuilder();
        StringBuilder columnTypesBuilder = new StringBuilder();
        List<FieldSchema> allFields = metastoreTable.getSd().getCols();
        allFields.addAll(metastoreTable.getPartitionKeys());

        boolean isFirst = true;
        for (Schema.Field hudiField : tableSchema.getFields()) {
            if (!isFirst) {
                columnNamesBuilder.append(",");
                columnTypesBuilder.append(":");
            }

            Optional<FieldSchema> field = allFields.stream()
                    .filter(f -> f.getName().equals(hudiField.name().toLowerCase(Locale.ROOT))).findFirst();
            if (!field.isPresent()) {
                throw new StarRocksConnectorException("Hudi column [" + hudiField.name() + "] not exists in hive metastore.");
            }

            TypeInfo fieldInfo = getTypeInfoFromTypeString(field.get().getType());
            columnNamesBuilder.append(field.get().getName());
            columnTypesBuilder.append(fieldInfo.getTypeName());
            isFirst = false;
        }
        hudiProperties.put(HUDI_TABLE_COLUMN_NAMES, columnNamesBuilder.toString());
        hudiProperties.put(HUDI_TABLE_COLUMN_TYPES, columnTypesBuilder.toString());

        return hudiProperties;
    }

    public static RemoteFileInputFormat toRemoteFileInputFormat(String inputFormat) {
        if (!HudiTable.isHudiTable(inputFormat)) {
            return RemoteFileInputFormat.fromHdfsInputFormatClass(inputFormat);
        } else {
            // Currently, we only support parquet on hudi format.
            return RemoteFileInputFormat.PARQUET;
        }
    }

    public static TextFileFormatDesc toTextFileFormatDesc(Map<String, String> serdeParams) {
        // Get properties 'field.delim', 'line.delim', 'collection.delim' and 'mapkey.delim' from StorageDescriptor
        // Detail refer to:
        // https://github.com/apache/hive/blob/90428cc5f594bd0abb457e4e5c391007b2ad1cb8/serde/src/gen/thrift/gen-javabean/org/apache/hadoop/hive/serde/serdeConstants.java#L34-L40

        // Here is for compatibility with Hive 2.x version.
        // There is a typo in Hive 2.x version, and fixed in Hive 3.x version.
        // https://issues.apache.org/jira/browse/HIVE-16922
        String collectionDelim;
        if (serdeParams.containsKey("colelction.delim")) {
            collectionDelim = serdeParams.get("colelction.delim");
        } else {
            collectionDelim = serdeParams.getOrDefault("collection.delim", "\002");
        }

        return new TextFileFormatDesc(
                serdeParams.getOrDefault("field.delim", "\001"),
                serdeParams.getOrDefault("line.delim", "\n"),
                collectionDelim,
                serdeParams.getOrDefault("mapkey.delim", "\003"));
    }

    public static HiveCommonStats toHiveCommonStats(Map<String, String> params) {
        long numRows = getLongParam(ROW_COUNT, params);
        long totalSize = getLongParam(TOTAL_SIZE, params);
        return new HiveCommonStats(numRows, totalSize);
    }

    public static Map<String, Map<String, HiveColumnStats>> toPartitionColumnStatistics(
            Map<String, List<ColumnStatisticsObj>> partitionNameToColumnStats,
            Map<String, Long> partToRowNum) {
        return partitionNameToColumnStats.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .collect(Collectors.toMap(Map.Entry::getKey,
                        entry -> toSinglePartitionColumnStats(
                                entry.getValue(),
                                partToRowNum.getOrDefault(entry.getKey(), -1L))));
    }

    public static Map<String, HiveColumnStats> toSinglePartitionColumnStats(
            List<ColumnStatisticsObj> statisticsObjs,
            long partitionRowNum) {
        return statisticsObjs.stream().collect(Collectors.toMap(
                        ColumnStatisticsObj::getColName,
                        statisticsObj -> toHiveColumnStatistics(statisticsObj, partitionRowNum)));
    }

    public static HiveColumnStats toHiveColumnStatistics(ColumnStatisticsObj columnStatisticsObj, long rowNums) {
        HiveColumnStats hiveColumnStatistics = new HiveColumnStats();
        hiveColumnStatistics.initialize(columnStatisticsObj.getStatsData(), rowNums);
        return hiveColumnStatistics;
    }

    public static void validateHiveTableType(String hiveTableType) {
        if (hiveTableType == null) {
            throw new StarRocksConnectorException("Unknown hive table type.");
        }
        switch (hiveTableType.toUpperCase(Locale.ROOT)) {
            case "VIRTUAL_VIEW": // hive view table not supported
                throw new StarRocksConnectorException("Hive view table is not supported.");
            case "EXTERNAL_TABLE": // hive external table supported
            case "MANAGED_TABLE": // basic hive table supported
            case "MATERIALIZED_VIEW": // hive materialized view table supported
                break;
            default:
                throw new StarRocksConnectorException("unsupported hive table type [" + hiveTableType + "].");
        }
    }

    private static long getLongParam(String key, Map<String, String> parameters) {
        if (parameters == null) {
            return -1;
        }

        String value = parameters.get(key);
        if (value == null) {
            return -1;
        }

        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exc) {
            // ignore
        }
        return -1;
    }

}
